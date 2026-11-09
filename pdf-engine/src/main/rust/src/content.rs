use crate::font::{load_font, Font};
use crate::matrix::Matrix;
use crate::tokenizer::{Token, Tokenizer};
use crate::wire::*;
use lopdf::{Dictionary, Document, Object as LObject};
use std::collections::HashMap;

#[derive(Clone, Debug)]
enum Operand {
    Number(f64),
    Name(String),
    Str(Vec<u8>),
    Array(Vec<Operand>),
}

impl Operand {
    fn as_f32(&self) -> f32 {
        match self {
            Operand::Number(n) => *n as f32,
            _ => 0.0,
        }
    }
}

#[derive(Clone)]
struct GState {
    ctm: Matrix,
    fill: [u8; 4],
    stroke: [u8; 4],
    line_width: f32,
    // Text state persists across q/Q per the spec's own rules are actually: text state is NOT
    // part of the graphics state stack for Tf/Tc/Tw/etc. (only inside BT..ET does it apply), but
    // CTM changes inside q/Q do affect subsequent text, so nothing extra is needed here.
}

pub struct DecodedImage {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

pub struct RenderResult {
    pub wire: Vec<u8>,
    pub images: Vec<DecodedImage>,
}

struct Interp<'a> {
    doc: &'a Document,
    writer: WireWriter,
    stack: Vec<GState>,
    gs: GState,
    cur_fill_set: [u8; 4],
    cur_stroke_set: [u8; 4],
    cur_line_width: f32,
    path_open: bool,
    path_bbox: Option<[f32; 4]>,
    // Text state
    tm: Matrix,
    tlm: Matrix,
    font: Option<std::rc::Rc<Font>>,
    font_size: f32,
    char_space: f32,
    word_space: f32,
    h_scale: f32,
    leading: f32,
    rise: f32,
    render_mode: i64,
    font_cache: HashMap<Vec<u8>, std::rc::Rc<Font>>,
    images: Vec<DecodedImage>,
    depth: u32,
}

const BASE: Matrix = Matrix::IDENTITY;

pub fn render_page(doc: &Document, content: &[u8], resources: &Dictionary) -> RenderResult {
    let mut interp = Interp {
        doc,
        writer: WireWriter::new(),
        stack: Vec::new(),
        gs: GState {
            ctm: BASE,
            fill: [0, 0, 0, 255],
            stroke: [0, 0, 0, 255],
            line_width: 1.0,
        },
        cur_fill_set: [255, 255, 255, 0],
        cur_stroke_set: [255, 255, 255, 0],
        cur_line_width: -1.0,
        path_open: false,
        path_bbox: None,
        tm: Matrix::IDENTITY,
        tlm: Matrix::IDENTITY,
        font: None,
        font_size: 0.0,
        char_space: 0.0,
        word_space: 0.0,
        h_scale: 1.0,
        leading: 0.0,
        rise: 0.0,
        render_mode: 0,
        font_cache: HashMap::new(),
        images: Vec::new(),
        depth: 0,
    };
    interp.run(content, resources);
    RenderResult { wire: interp.writer.finish(), images: interp.images }
}

impl<'a> Interp<'a> {
    fn run(&mut self, content: &[u8], resources: &Dictionary) {
        if self.depth > 12 {
            return; // guard against pathological/cyclic Form XObject recursion
        }
        self.depth += 1;
        let mut tok = Tokenizer::new(content);
        let mut operands: Vec<Operand> = Vec::new();
        while let Some(t) = tok.next_token() {
            match t {
                Token::Number(n) => operands.push(Operand::Number(n)),
                Token::Name(n) => operands.push(Operand::Name(n)),
                Token::StringLit(s) => operands.push(Operand::Str(s)),
                Token::ArrayStart => {
                    let arr = self.read_array(&mut tok);
                    operands.push(Operand::Array(arr));
                }
                Token::ArrayEnd => {}
                Token::DictStart => {
                    self.skip_dict(&mut tok);
                }
                Token::DictEnd => {}
                Token::Operator(op) => {
                    self.exec(&op, &operands, resources, &mut tok);
                    operands.clear();
                }
            }
        }
        self.depth -= 1;
    }

    fn read_array(&mut self, tok: &mut Tokenizer) -> Vec<Operand> {
        let mut out = Vec::new();
        loop {
            match tok.next_token() {
                Some(Token::Number(n)) => out.push(Operand::Number(n)),
                Some(Token::Name(n)) => out.push(Operand::Name(n)),
                Some(Token::StringLit(s)) => out.push(Operand::Str(s)),
                Some(Token::ArrayStart) => {
                    let nested = self.read_array(tok);
                    out.push(Operand::Array(nested));
                }
                Some(Token::ArrayEnd) | None => break,
                _ => {}
            }
        }
        out
    }

    fn skip_dict(&mut self, tok: &mut Tokenizer) {
        let mut depth = 1;
        while depth > 0 {
            match tok.next_token() {
                Some(Token::DictStart) => depth += 1,
                Some(Token::DictEnd) => depth -= 1,
                None => break,
                _ => {}
            }
        }
    }

    fn sync_fill(&mut self) {
        if self.gs.fill != self.cur_fill_set {
            self.writer.op(OP_SET_FILL_COLOR);
            self.writer.u8(self.gs.fill[0]);
            self.writer.u8(self.gs.fill[1]);
            self.writer.u8(self.gs.fill[2]);
            self.writer.u8(self.gs.fill[3]);
            self.cur_fill_set = self.gs.fill;
        }
    }

    fn sync_stroke(&mut self) {
        if self.gs.stroke != self.cur_stroke_set {
            self.writer.op(OP_SET_STROKE_COLOR);
            self.writer.u8(self.gs.stroke[0]);
            self.writer.u8(self.gs.stroke[1]);
            self.writer.u8(self.gs.stroke[2]);
            self.writer.u8(self.gs.stroke[3]);
            self.cur_stroke_set = self.gs.stroke;
        }
        if (self.gs.line_width - self.cur_line_width).abs() > 0.001 {
            self.writer.op(OP_SET_LINE_WIDTH);
            self.writer.f32(self.gs.line_width);
            self.cur_line_width = self.gs.line_width;
        }
    }

    fn exec(&mut self, op: &str, ops: &[Operand], resources: &Dictionary, tok: &mut Tokenizer) {
        match op {
            "q" => {
                self.stack.push(self.gs.clone());
                self.writer.op(OP_SAVE_STATE);
            }
            "Q" => {
                if let Some(prev) = self.stack.pop() {
                    self.gs = prev;
                }
                self.writer.op(OP_RESTORE_STATE);
            }
            "cm" => {
                if ops.len() == 6 {
                    let m = Matrix::new(
                        ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(),
                        ops[3].as_f32(), ops[4].as_f32(), ops[5].as_f32(),
                    );
                    self.gs.ctm = m.concat(self.gs.ctm);
                }
            }
            "w" => if let Some(w) = ops.first() { self.gs.line_width = w.as_f32(); },
            "g" => if let Some(v) = ops.first() { self.gs.fill = gray(v.as_f32()); },
            "G" => if let Some(v) = ops.first() { self.gs.stroke = gray(v.as_f32()); },
            "rg" => if ops.len() == 3 { self.gs.fill = rgb(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32()); },
            "RG" => if ops.len() == 3 { self.gs.stroke = rgb(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32()); },
            "k" => if ops.len() == 4 { self.gs.fill = cmyk(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(), ops[3].as_f32()); },
            "K" => if ops.len() == 4 { self.gs.stroke = cmyk(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(), ops[3].as_f32()); },
            "sc" | "scn" => self.gs.fill = color_from_components(ops),
            "SC" | "SCN" => self.gs.stroke = color_from_components(ops),
            "m" => if ops.len() == 2 { self.open_path(); self.moveto(ops[0].as_f32(), ops[1].as_f32()); },
            "l" => if ops.len() == 2 { self.open_path(); self.lineto(ops[0].as_f32(), ops[1].as_f32()); },
            "c" => if ops.len() == 6 { self.open_path(); self.curveto(ops); },
            "v" => if ops.len() == 4 {
                // v: current point is the first control point.
                self.open_path();
                self.writer.op(OP_CURVE_TO);
                // Reuse current point as (x1,y1) by writing it via last moveto — approximate by
                // treating it as a 3-point curve degenerating control 1 to the end point's own
                // start; acceptable visual approximation for the rare `v` operator.
                let (x1, y1) = (ops[0].as_f32(), ops[1].as_f32());
                let (x2, y2) = (ops[0].as_f32(), ops[1].as_f32());
                let (x3, y3) = (ops[2].as_f32(), ops[3].as_f32());
                let _ = (x1, y1);
                self.write_curve(x2, y2, x2, y2, x3, y3);
            },
            "y" => if ops.len() == 4 {
                self.open_path();
                let (x1, y1) = (ops[0].as_f32(), ops[1].as_f32());
                let (x3, y3) = (ops[2].as_f32(), ops[3].as_f32());
                self.write_curve(x1, y1, x3, y3, x3, y3);
            },
            "h" => { self.writer.op(OP_CLOSE_SUBPATH); },
            "re" => if ops.len() == 4 {
                self.open_path();
                let (x, y, w, h) = (ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(), ops[3].as_f32());
                self.moveto(x, y);
                self.lineto(x + w, y);
                self.lineto(x + w, y + h);
                self.lineto(x, y + h);
                self.writer.op(OP_CLOSE_SUBPATH);
            },
            "f" | "F" => { self.sync_fill(); self.writer.op(OP_FILL_PATH); self.writer.u8(0); self.path_open = false; },
            "f*" => { self.sync_fill(); self.writer.op(OP_FILL_PATH); self.writer.u8(1); self.path_open = false; },
            "S" => { self.sync_stroke(); self.writer.op(OP_STROKE_PATH); self.path_open = false; },
            "s" => { self.writer.op(OP_CLOSE_SUBPATH); self.sync_stroke(); self.writer.op(OP_STROKE_PATH); self.path_open = false; },
            "B" => { self.sync_fill(); self.sync_stroke(); self.writer.op(OP_FILL_AND_STROKE); self.writer.u8(0); self.path_open = false; },
            "B*" => { self.sync_fill(); self.sync_stroke(); self.writer.op(OP_FILL_AND_STROKE); self.writer.u8(1); self.path_open = false; },
            "b" => { self.writer.op(OP_CLOSE_SUBPATH); self.sync_fill(); self.sync_stroke(); self.writer.op(OP_FILL_AND_STROKE); self.writer.u8(0); self.path_open = false; },
            "b*" => { self.writer.op(OP_CLOSE_SUBPATH); self.sync_fill(); self.sync_stroke(); self.writer.op(OP_FILL_AND_STROKE); self.writer.u8(1); self.path_open = false; },
            "W" | "W*" => {
                // Clip path: approximated with the current path's bounding box, rather than a
                // true non-rectangular clip region — close enough for the overwhelmingly common
                // case of `re W n` clipping to a rectangle; a real clip path is future work.
                if let Some([x0, y0, x1, y1]) = self.path_bbox {
                    self.writer.op(OP_SET_CLIP_RECT);
                    self.writer.f32(x0);
                    self.writer.f32(y0);
                    self.writer.f32(x1 - x0);
                    self.writer.f32(y1 - y0);
                }
            }
            "n" => { self.writer.op(OP_END_PATH); self.path_open = false; }
            "BT" => { self.tm = Matrix::IDENTITY; self.tlm = Matrix::IDENTITY; }
            "ET" => {}
            "Tf" => if ops.len() == 2 {
                if let Operand::Name(name) = &ops[0] {
                    self.font = Some(self.load_font_cached(name.as_bytes(), resources));
                }
                self.font_size = ops[1].as_f32();
            },
            "Tc" => if let Some(v) = ops.first() { self.char_space = v.as_f32(); },
            "Tw" => if let Some(v) = ops.first() { self.word_space = v.as_f32(); },
            "Tz" => if let Some(v) = ops.first() { self.h_scale = v.as_f32() / 100.0; },
            "TL" => if let Some(v) = ops.first() { self.leading = v.as_f32(); },
            "Ts" => if let Some(v) = ops.first() { self.rise = v.as_f32(); },
            "Tr" => if let Some(v) = ops.first() { self.render_mode = v.as_f32() as i64; },
            "Td" => if ops.len() == 2 {
                let m = Matrix::new(1.0, 0.0, 0.0, 1.0, ops[0].as_f32(), ops[1].as_f32());
                self.tlm = m.concat(self.tlm);
                self.tm = self.tlm;
            },
            "TD" => if ops.len() == 2 {
                self.leading = -ops[1].as_f32();
                let m = Matrix::new(1.0, 0.0, 0.0, 1.0, ops[0].as_f32(), ops[1].as_f32());
                self.tlm = m.concat(self.tlm);
                self.tm = self.tlm;
            },
            "Tm" => if ops.len() == 6 {
                self.tlm = Matrix::new(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(), ops[3].as_f32(), ops[4].as_f32(), ops[5].as_f32());
                self.tm = self.tlm;
            },
            "T*" => {
                let m = Matrix::new(1.0, 0.0, 0.0, 1.0, 0.0, -self.leading);
                self.tlm = m.concat(self.tlm);
                self.tm = self.tlm;
            },
            "Tj" => if let Some(Operand::Str(s)) = ops.first() { self.show_text(s); },
            "'" => {
                let m = Matrix::new(1.0, 0.0, 0.0, 1.0, 0.0, -self.leading);
                self.tlm = m.concat(self.tlm);
                self.tm = self.tlm;
                if let Some(Operand::Str(s)) = ops.first() { self.show_text(s); }
            },
            "\"" => if ops.len() == 3 {
                self.word_space = ops[0].as_f32();
                self.char_space = ops[1].as_f32();
                let m = Matrix::new(1.0, 0.0, 0.0, 1.0, 0.0, -self.leading);
                self.tlm = m.concat(self.tlm);
                self.tm = self.tlm;
                if let Operand::Str(s) = &ops[2] { self.show_text(s); }
            },
            "TJ" => if let Some(Operand::Array(arr)) = ops.first() {
                for item in arr {
                    match item {
                        Operand::Str(s) => self.show_text(s),
                        Operand::Number(n) => {
                            let adjust = (-*n as f32) / 1000.0 * self.font_size * self.h_scale;
                            let m = Matrix::new(1.0, 0.0, 0.0, 1.0, adjust, 0.0);
                            self.tm = m.concat(self.tm);
                        }
                        _ => {}
                    }
                }
            },
            "Do" => if let Some(Operand::Name(name)) = ops.first() {
                self.do_xobject(name, resources);
            },
            "BI" => { self.skip_inline_image(tok); },
            "gs" | "cs" | "CS" | "sh" | "d" | "j" | "J" | "M" | "ri" | "i" | "MP" | "DP" | "BMC" | "BDC" | "EMC" => {}
            _ => {}
        }
    }

    fn open_path(&mut self) {
        if !self.path_open {
            self.path_bbox = None;
        }
        self.path_open = true;
    }

    fn grow_bbox(&mut self, x: f32, y: f32) {
        self.path_bbox = Some(match self.path_bbox {
            Some([x0, y0, x1, y1]) => [x0.min(x), y0.min(y), x1.max(x), y1.max(y)],
            None => [x, y, x, y],
        });
    }

    fn moveto(&mut self, x: f32, y: f32) {
        let (dx, dy) = self.gs.ctm.apply(x, y);
        self.grow_bbox(dx, dy);
        self.writer.op(OP_MOVE_TO);
        self.writer.f32(dx);
        self.writer.f32(dy);
    }

    fn lineto(&mut self, x: f32, y: f32) {
        let (dx, dy) = self.gs.ctm.apply(x, y);
        self.grow_bbox(dx, dy);
        self.writer.op(OP_LINE_TO);
        self.writer.f32(dx);
        self.writer.f32(dy);
    }

    fn curveto(&mut self, ops: &[Operand]) {
        self.write_curve(ops[0].as_f32(), ops[1].as_f32(), ops[2].as_f32(), ops[3].as_f32(), ops[4].as_f32(), ops[5].as_f32());
    }

    fn write_curve(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, x3: f32, y3: f32) {
        let (dx1, dy1) = self.gs.ctm.apply(x1, y1);
        let (dx2, dy2) = self.gs.ctm.apply(x2, y2);
        let (dx3, dy3) = self.gs.ctm.apply(x3, y3);
        self.grow_bbox(dx1, dy1);
        self.grow_bbox(dx2, dy2);
        self.grow_bbox(dx3, dy3);
        self.writer.op(OP_CURVE_TO);
        self.writer.f32(dx1); self.writer.f32(dy1);
        self.writer.f32(dx2); self.writer.f32(dy2);
        self.writer.f32(dx3); self.writer.f32(dy3);
    }

    fn load_font_cached(&mut self, name: &[u8], resources: &Dictionary) -> std::rc::Rc<Font> {
        if let Some(f) = self.font_cache.get(name) {
            return f.clone();
        }
        let font = self.resolve_font_dict(name, resources)
            .map(|dict| load_font(self.doc, &dict))
            .unwrap_or(Font {
                widths: HashMap::new(),
                default_width: 500.0,
                to_unicode: HashMap::new(),
                simple_encoding: HashMap::new(),
                two_byte_codes: false,
                is_bold: false,
                is_italic: false,
            });
        let rc = std::rc::Rc::new(font);
        self.font_cache.insert(name.to_vec(), rc.clone());
        rc
    }

    /// Resolves `resources`'s `/Font /<name>` entry to the font's dictionary, dereferencing the
    /// `/Font` dict and the per-name entry independently (an owned object's `dereference` just
    /// hands the same object straight back, so chaining it through a temporary doesn't work).
    fn resolve_font_dict(&self, name: &[u8], resources: &Dictionary) -> Option<Dictionary> {
        let font_dict_obj = resources.get(b"Font").ok()?;
        let (_, font_dict_obj) = self.doc.dereference(font_dict_obj).ok()?;
        let fonts = font_dict_obj.as_dict().ok()?;
        let font_obj = fonts.get(name).ok()?;
        let (_, font_obj) = self.doc.dereference(font_obj).ok()?;
        font_obj.as_dict().ok().cloned()
    }

    fn show_text(&mut self, bytes: &[u8]) {
        let Some(font) = self.font.clone() else { return };
        if self.render_mode == 3 {
            // Invisible text (common for OCR text layers over a scanned-image background):
            // still advance the text matrix so later text lines up, but draw nothing.
        }
        for code in font.codes_of(bytes) {
            let w0 = font.width_of(code) / 1000.0;
            let text = font.text_of(code);
            let is_space = bytes.len() == 1 && bytes[0] == b' ' && code == 32;

            // Trm = [Tfs*Th 0 0 Tfs 0 Trise] x Tm x CTM — the full glyph-space -> device matrix.
            let glyph_scale = Matrix::new(self.font_size * self.h_scale, 0.0, 0.0, self.font_size, 0.0, self.rise);
            let trm = glyph_scale.concat(self.tm).concat(self.gs.ctm);
            let (ox, oy) = trm.apply(0.0, 0.0);
            let advance_user = w0 * self.font_size + self.char_space + if is_space { self.word_space } else { 0.0 };
            let advance_dev = advance_user * self.h_scale * self.gs.ctm.scale_magnitude() * self.tm.scale_magnitude();

            if self.render_mode != 3 && !text.is_empty() {
                self.sync_fill();
                let bytes = text.as_bytes();
                self.writer.op(OP_DRAW_GLYPH);
                self.writer.f32(ox);
                self.writer.f32(oy);
                self.writer.f32(self.font_size * self.gs.ctm.scale_magnitude() * self.tm.scale_magnitude());
                self.writer.f32(advance_dev);
                let style = (font.is_bold as u8) | ((font.is_italic as u8) << 1);
                self.writer.u8(style);
                self.writer.u16(bytes.len() as u16);
                self.writer.bytes(bytes);
            }

            let advance_m = Matrix::new(1.0, 0.0, 0.0, 1.0, advance_user * self.h_scale, 0.0);
            self.tm = advance_m.concat(self.tm);
        }
    }

    fn do_xobject(&mut self, name: &str, resources: &Dictionary) {
        let Ok(xobjects) = resources.get(b"XObject").and_then(|o| self.doc.dereference(o)) else { return };
        let Ok(xdict) = xobjects.1.as_dict() else { return };
        let Ok(xobj_ref) = xdict.get(name.as_bytes()) else { return };
        let Ok((_, xobj)) = self.doc.dereference(xobj_ref) else { return };
        let Ok(stream) = xobj.as_stream() else { return };
        let subtype = stream.dict.get(b"Subtype").and_then(|o| o.as_name()).unwrap_or(b"").to_vec();

        if subtype == b"Form" {
            let form_resources = stream.dict
                .get(b"Resources")
                .and_then(|o| self.doc.dereference(o))
                .ok()
                .and_then(|(_, o)| o.as_dict().ok().cloned())
                .unwrap_or_else(|| resources.clone());
            let saved_gs = self.gs.clone();
            if let Ok(m) = stream.dict.get(b"Matrix").and_then(|o| o.as_array()) {
                if m.len() == 6 {
                    let fm = Matrix::new(
                        num(&m[0]), num(&m[1]), num(&m[2]), num(&m[3]), num(&m[4]), num(&m[5]),
                    );
                    self.gs.ctm = fm.concat(self.gs.ctm);
                }
            }
            self.writer.op(OP_SAVE_STATE);
            if let Ok(data) = stream.decompressed_content() {
                self.run(&data, &form_resources);
            }
            self.writer.op(OP_RESTORE_STATE);
            self.gs = saved_gs;
        } else if subtype == b"Image" {
            self.draw_image(stream);
        }
    }

    fn draw_image(&mut self, stream: &lopdf::Stream) {
        let width = stream.dict.get(b"Width").and_then(|o| o.as_i64()).unwrap_or(0) as u32;
        let height = stream.dict.get(b"Height").and_then(|o| o.as_i64()).unwrap_or(0) as u32;
        if width == 0 || height == 0 || width as u64 * height as u64 > 64_000_000 {
            return;
        }
        let bpc = stream.dict.get(b"BitsPerComponent").and_then(|o| o.as_i64()).unwrap_or(8);
        let filter_is_jpx = stream
            .dict
            .get(b"Filter")
            .map(|f| filter_contains(f, b"JPXDecode"))
            .unwrap_or(false);
        if filter_is_jpx || bpc != 8 {
            return; // JPEG2000 and non-8bpc images are a follow-up.
        }
        let is_mask = stream.dict.get(b"ImageMask").and_then(|o| o.as_bool()).unwrap_or(false);
        if is_mask {
            return;
        }
        let filter_is_dct = stream
            .dict
            .get(b"Filter")
            .map(|f| filter_contains(f, b"DCTDecode"))
            .unwrap_or(false);
        let (channels, data) = if filter_is_dct {
            // Scanned pages (photos of signed documents, IDs) are almost always a single
            // full-page DCTDecode (JPEG) image — skipping these left every such PDF entirely
            // blank. `/Filter` can still list e.g. FlateDecode ahead of DCTDecode (some
            // generators re-compress the already-JPEG-encoded stream), so peel those off first.
            let Some(jpeg_bytes) = self.raw_filtered_bytes_before_dct(stream) else { return };
            let mut decoder = zune_jpeg::JpegDecoder::new(std::io::Cursor::new(jpeg_bytes.as_slice()));
            let Ok(pixels) = decoder.decode() else { return };
            let Some(info) = decoder.info() else { return };
            if info.width as u32 != width || info.height as u32 != height || pixels.is_empty() {
                return;
            }
            let ch = pixels.len() / (width as usize * height as usize);
            if ch == 0 {
                return;
            }
            (ch, pixels)
        } else {
            let color_space = stream
                .dict
                .get(b"ColorSpace")
                .and_then(|o| self.doc.dereference(o))
                .ok()
                .map(|(_, o)| o.as_name().map(|n| n.to_vec()).unwrap_or_default())
                .unwrap_or_default();
            let channels: usize = if color_space == b"DeviceGray" || color_space == b"CalGray" { 1 }
                else if color_space == b"DeviceCMYK" { 4 }
                else { 3 };
            // `lopdf::Stream::decompressed_content` unconditionally refuses any stream whose
            // `/Subtype` is `/Image` (it returns `Error::Type`) — image data needs predictor/
            // colorspace handling it doesn't do, so it pushes that to the caller rather than
            // half-doing it. We do our own Flate + PNG-predictor decode instead.
            let Some(data) = self.decompress_image_stream(stream, channels, width as usize) else { return };
            (channels, data)
        };
        let expected = width as usize * height as usize * channels;
        if data.len() < expected {
            return;
        }
        // `/SMask` is a separate grayscale image giving this one's per-pixel alpha — common for
        // any PNG-sourced image with transparency (logos, icons, drop shadows). Skipping it makes
        // the image fully opaque, which turns a transparent-background graphic into a solid block
        // that can blot out whatever page content was meant to show through (and, since the block
        // takes its color from whatever garbage pixels sat in the "transparent" area of the source
        // PNG, it rarely even looks like a sensible solid color).
        let smask: Option<Vec<u8>> = stream
            .dict
            .get(b"SMask")
            .and_then(|o| self.doc.dereference(o))
            .ok()
            .and_then(|(_, o)| o.as_stream().ok())
            .and_then(|smask_stream| {
                let sw = smask_stream.dict.get(b"Width").and_then(|o| o.as_i64()).unwrap_or(0) as u32;
                let sh = smask_stream.dict.get(b"Height").and_then(|o| o.as_i64()).unwrap_or(0) as u32;
                if sw != width || sh != height {
                    return None; // mismatched-size masks are a rare edge case; fall back to opaque.
                }
                let sbpc = smask_stream.dict.get(b"BitsPerComponent").and_then(|o| o.as_i64()).unwrap_or(8);
                if sbpc != 8 {
                    return None;
                }
                self.decompress_image_stream(smask_stream, 1, sw as usize)
            })
            .filter(|data| data.len() >= (width as usize * height as usize));

        let mut rgba = vec![0u8; width as usize * height as usize * 4];
        for i in 0..(width as usize * height as usize) {
            let (r, g, b) = match channels {
                1 => { let v = data[i]; (v, v, v) }
                4 => {
                    let c = data[i * 4] as f32 / 255.0;
                    let m = data[i * 4 + 1] as f32 / 255.0;
                    let y = data[i * 4 + 2] as f32 / 255.0;
                    let k = data[i * 4 + 3] as f32 / 255.0;
                    (
                        (255.0 * (1.0 - c) * (1.0 - k)) as u8,
                        (255.0 * (1.0 - m) * (1.0 - k)) as u8,
                        (255.0 * (1.0 - y) * (1.0 - k)) as u8,
                    )
                }
                _ => (data[i * 3], data[i * 3 + 1], data[i * 3 + 2]),
            };
            rgba[i * 4] = r;
            rgba[i * 4 + 1] = g;
            rgba[i * 4 + 2] = b;
            rgba[i * 4 + 3] = smask.as_ref().map(|m| m[i]).unwrap_or(255);
        }
        let idx = self.images.len() as u16;
        self.images.push(DecodedImage { width, height, rgba });

        // Hand the raw CTM to Kotlin rather than reducing it to a bounding box — a `cm` with a
        // rotation or shear component (routine for phone-scanned pages, where the capture is
        // landscape but the page is portrait) needs the real transform, not just where its
        // corners land.
        self.writer.op(OP_DRAW_IMAGE);
        self.writer.f32(self.gs.ctm.a);
        self.writer.f32(self.gs.ctm.b);
        self.writer.f32(self.gs.ctm.c);
        self.writer.f32(self.gs.ctm.d);
        self.writer.f32(self.gs.ctm.e);
        self.writer.f32(self.gs.ctm.f);
        self.writer.u16(idx);
    }

    /// Applies any filters ahead of a `DCTDecode` in the chain (some generators re-compress an
    /// already-JPEG stream with FlateDecode, or ASCII85-armor it for safe embedding in a
    /// text-based PDF producer) and returns the resulting raw JPEG bytes, ready for `zune_jpeg`.
    /// Filters are applied in the order they're listed, per spec.
    fn raw_filtered_bytes_before_dct(&self, stream: &lopdf::Stream) -> Option<Vec<u8>> {
        use std::io::Read;
        let filters = stream.filters().ok()?;
        let mut data = stream.content.clone();
        for filter in &filters {
            match filter.as_str() {
                "FlateDecode" => {
                    let mut out = Vec::with_capacity(data.len() * 3);
                    flate2::read::ZlibDecoder::new(&data[..]).read_to_end(&mut out).ok()?;
                    data = out;
                }
                "ASCII85Decode" => data = ascii85_decode(&data)?,
                "DCTDecode" => return Some(data),
                _ => return None,
            }
        }
        None // DCTDecode never showed up — not actually a JPEG stream.
    }

    /// Flate-decodes an image stream's raw content ourselves and reverses a PNG predictor if
    /// present, since `lopdf::Stream::decompressed_content` refuses to touch `/Subtype /Image`
    /// streams at all. `channels`/`columns` give the predictor its bytes-per-pixel and row width.
    fn decompress_image_stream(&self, stream: &lopdf::Stream, channels: usize, columns: usize) -> Option<Vec<u8>> {
        use std::io::Read;
        let filters = stream.filters().ok()?;
        let mut data = stream.content.clone();
        for filter in &filters {
            data = match filter.as_str() {
                "FlateDecode" => {
                    let mut out = Vec::with_capacity(data.len() * 3);
                    flate2::read::ZlibDecoder::new(&data[..]).read_to_end(&mut out).ok()?;
                    out
                }
                "ASCII85Decode" => ascii85_decode(&data)?,
                _ => return None, // LZW/RunLength images are a follow-up.
            };
        }
        let params = stream
            .dict
            .get(b"DecodeParms")
            .and_then(|o| self.doc.dereference(o))
            .ok()
            .and_then(|(_, o)| o.as_dict().ok().cloned());
        if let Some(params) = params {
            let predictor = params.get(b"Predictor").and_then(|o| o.as_i64()).unwrap_or(1);
            if (10..=15).contains(&predictor) {
                data = lopdf::filters::png::decode_frame(&data, channels.max(1), columns).ok()?;
            }
        }
        Some(data)
    }

    fn skip_inline_image(&mut self, tok: &mut Tokenizer) {
        // BI ... ID <raw data> EI — raw data isn't tokenizable, so scan for "EI" in the
        // underlying bytes ourselves; inline images are uncommon and skipped visually for now.
        while let Some(t) = tok.next_token() {
            if let Token::Operator(op) = &t {
                if op == "ID" || op == "EI" {
                    break;
                }
            }
        }
    }
}

fn num(o: &LObject) -> f32 {
    o.as_float().unwrap_or_else(|_| o.as_i64().unwrap_or(0) as f32)
}

/// Decodes an `ASCII85Decode` stream (5 printable chars -> 4 bytes, base 85, with `z` as
/// shorthand for 4 zero bytes and an optional trailing `~>`). Some PDF producers wrap an
/// already-compressed/JPEG stream in this just to keep it text-safe for their own pipeline.
pub fn ascii85_decode(input: &[u8]) -> Option<Vec<u8>> {
    let mut out = Vec::with_capacity(input.len() * 4 / 5);
    let mut group = [0u8; 5];
    let mut group_len = 0usize;
    for &byte in input {
        if byte == b'~' {
            break;
        }
        if byte.is_ascii_whitespace() {
            continue;
        }
        if byte == b'z' && group_len == 0 {
            out.extend_from_slice(&[0, 0, 0, 0]);
            continue;
        }
        if !(b'!'..=b'u').contains(&byte) {
            return None;
        }
        group[group_len] = byte - b'!';
        group_len += 1;
        if group_len == 5 {
            let value = group.iter().fold(0u32, |acc, &d| acc.wrapping_mul(85).wrapping_add(d as u32));
            out.extend_from_slice(&value.to_be_bytes());
            group_len = 0;
        }
    }
    if group_len > 0 {
        // A short final group is padded with 'u' (84, the max digit) before decoding, then only
        // the first (group_len - 1) of the 4 output bytes are kept.
        for slot in group.iter_mut().skip(group_len) {
            *slot = 84;
        }
        let value = group.iter().fold(0u32, |acc, &d| acc.wrapping_mul(85).wrapping_add(d as u32));
        out.extend_from_slice(&value.to_be_bytes()[..group_len - 1]);
    }
    Some(out)
}

fn filter_contains(f: &LObject, name: &[u8]) -> bool {
    match f {
        LObject::Name(n) => n.as_slice() == name,
        LObject::Array(arr) => arr.iter().any(|o| filter_contains(o, name)),
        _ => false,
    }
}

fn gray(v: f32) -> [u8; 4] { let b = (v.clamp(0.0, 1.0) * 255.0) as u8; [b, b, b, 255] }
fn rgb(r: f32, g: f32, b: f32) -> [u8; 4] {
    [(r.clamp(0.0,1.0)*255.0) as u8, (g.clamp(0.0,1.0)*255.0) as u8, (b.clamp(0.0,1.0)*255.0) as u8, 255]
}
fn cmyk(c: f32, m: f32, y: f32, k: f32) -> [u8; 4] {
    rgb((1.0-c)*(1.0-k), (1.0-m)*(1.0-k), (1.0-y)*(1.0-k))
}

/// `sc`/`scn`/`SC`/`SCN` with 1/3/4 numeric components we treat as Gray/RGB/CMYK; a trailing
/// `Name` operand (a pattern name) has no flat color, so it falls back to black rather than
/// attempting real pattern/shading support.
fn color_from_components(ops: &[Operand]) -> [u8; 4] {
    let nums: Vec<f32> = ops.iter().filter_map(|o| match o {
        Operand::Number(n) => Some(*n as f32),
        _ => None,
    }).collect();
    match nums.len() {
        1 => gray(nums[0]),
        3 => rgb(nums[0], nums[1], nums[2]),
        4 => cmyk(nums[0], nums[1], nums[2], nums[3]),
        _ => [0, 0, 0, 255],
    }
}
