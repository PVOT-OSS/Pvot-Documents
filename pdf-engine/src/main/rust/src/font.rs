use crate::encodings::{glyph_name_to_unicode, win_ansi};
use crate::tokenizer::{Token, Tokenizer};
use lopdf::{Dictionary, Document, Object};
use std::collections::HashMap;

/// Enough of a PDF font to lay out and extract text correctly: per-code advance widths (in
/// 1/1000 em, the units content-stream text-showing operators work in), a code -> Unicode
/// mapping for selection/search, and whether codes in this font's strings are one or two bytes
/// wide (Type0/CID fonts with Identity-H encoding, the overwhelmingly common case for CJK and for
/// subsetted embedded fonts in general).
pub struct Font {
    pub widths: HashMap<u32, f32>,
    pub default_width: f32,
    pub to_unicode: HashMap<u32, String>,
    pub simple_encoding: HashMap<u8, char>,
    pub two_byte_codes: bool,
    pub is_bold: bool,
    pub is_italic: bool,
}

impl Font {
    pub fn width_of(&self, code: u32) -> f32 {
        *self.widths.get(&code).unwrap_or(&self.default_width)
    }

    pub fn text_of(&self, code: u32) -> String {
        if let Some(s) = self.to_unicode.get(&code) {
            return s.clone();
        }
        if !self.two_byte_codes {
            if let Some(c) = self.simple_encoding.get(&(code as u8)) {
                return c.to_string();
            }
            if let Some(c) = win_ansi(code as u8) {
                return c.to_string();
            }
        }
        String::new()
    }

    /// Splits a PDF string operand into this font's character codes (1 or 2 bytes each).
    pub fn codes_of(&self, bytes: &[u8]) -> Vec<u32> {
        if self.two_byte_codes {
            bytes
                .chunks(2)
                .map(|c| {
                    if c.len() == 2 {
                        ((c[0] as u32) << 8) | c[1] as u32
                    } else {
                        c[0] as u32
                    }
                })
                .collect()
        } else {
            bytes.iter().map(|&b| b as u32).collect()
        }
    }
}

pub fn load_font(doc: &Document, dict: &Dictionary) -> Font {
    let subtype = dict
        .get(b"Subtype")
        .and_then(|o| o.as_name())
        .unwrap_or(b"")
        .to_vec();
    let is_type0 = subtype == b"Type0";

    // Subsetted embedded fonts keep a descriptive BaseFont name (e.g. "ABCDEF+Arial-BoldMT",
    // "TimesNewRomanPS-ItalicMT") even though we don't parse the embedded outline program itself,
    // so a name-based heuristic is the cheap way to recover weight/slant for on-screen rendering.
    let base_font_lower = dict
        .get(b"BaseFont")
        .and_then(|o| o.as_name())
        .map(|n| String::from_utf8_lossy(n).to_lowercase())
        .unwrap_or_default();
    let is_bold = base_font_lower.contains("bold");
    let is_italic = base_font_lower.contains("italic") || base_font_lower.contains("oblique");

    let mut widths = HashMap::new();
    let mut default_width = 500.0;
    let mut two_byte_codes = false;

    if is_type0 {
        two_byte_codes = true; // Identity-H/V assumed; the common case for subsetted CID fonts.
        if let Ok(descendants) = dict.get(b"DescendantFonts").and_then(|o| {
            doc.dereference(o).map(|(_, o)| o)
        }) {
            if let Ok(arr) = descendants.as_array() {
                if let Some(first) = arr.first() {
                    if let Ok((_, desc_obj)) = doc.dereference(first) {
                        if let Ok(desc) = desc_obj.as_dict() {
                            default_width = desc
                                .get(b"DW")
                                .and_then(|o| o.as_float().map(|v| v as f64).or_else(|_| o.as_i64().map(|v| v as f64)))
                                .unwrap_or(1000.0) as f32;
                            if let Ok(w) = desc.get(b"W").and_then(|o| doc.dereference(o)) {
                                parse_cid_widths(&w.1, &mut widths);
                            }
                        }
                    }
                }
            }
        }
    } else {
        default_width = dict
            .get(b"MissingWidth")
            .and_then(|o| o.as_float().map(|v| v as f64).or_else(|_| o.as_i64().map(|v| v as f64)))
            .unwrap_or(0.0) as f32;
        let first_char = dict
            .get(b"FirstChar")
            .and_then(|o| o.as_i64())
            .unwrap_or(0);
        if let Ok(w) = dict.get(b"Widths").and_then(|o| doc.dereference(o)) {
            if let Ok(arr) = w.1.as_array() {
                for (i, v) in arr.iter().enumerate() {
                    if let Ok(width) = v.as_float().map(|x| x as f64).or_else(|_| v.as_i64().map(|x| x as f64)) {
                        widths.insert((first_char + i as i64) as u32, width as f32);
                    }
                }
            }
        }
        if widths.is_empty() {
            default_width = 500.0;
        }
    }

    let simple_encoding = if is_type0 {
        HashMap::new()
    } else {
        load_simple_encoding(doc, dict)
    };

    let to_unicode = dict
        .get(b"ToUnicode")
        .and_then(|o| doc.dereference(o))
        .and_then(|(_, o)| doc.get_object(o.as_reference().unwrap_or((0, 0))).or(Ok(o)))
        .ok()
        .and_then(|o| o.as_stream().ok().map(|s| s.decompressed_content().unwrap_or_default()))
        .map(|bytes| parse_to_unicode_cmap(&bytes))
        .unwrap_or_default();

    Font {
        widths,
        default_width,
        to_unicode,
        simple_encoding,
        two_byte_codes,
        is_bold,
        is_italic,
    }
}

fn load_simple_encoding(doc: &Document, dict: &Dictionary) -> HashMap<u8, char> {
    let mut map: HashMap<u8, char> = HashMap::new();
    for code in 0u16..=255 {
        if let Some(c) = win_ansi(code as u8) {
            map.insert(code as u8, c);
        }
    }
    if let Ok(enc_obj) = dict.get(b"Encoding").and_then(|o| doc.dereference(o)) {
        if let Ok(diffs_dict) = enc_obj.1.as_dict() {
            if let Ok(diffs) = diffs_dict.get(b"Differences").and_then(|o| doc.dereference(o)) {
                if let Ok(arr) = diffs.1.as_array() {
                    let mut current_code: u32 = 0;
                    for item in arr {
                        match item {
                            Object::Integer(n) => current_code = *n as u32,
                            Object::Real(n) => current_code = *n as u32,
                            Object::Name(name) => {
                                let name_str = String::from_utf8_lossy(name);
                                if let Some(c) = glyph_name_to_unicode(&name_str) {
                                    if current_code <= 255 {
                                        map.insert(current_code as u8, c);
                                    }
                                }
                                current_code += 1;
                            }
                            _ => {}
                        }
                    }
                }
            }
        }
    }
    map
}

/// `/W` array for CID fonts: either `c [w1 w2 ...]` (consecutive CIDs starting at c) or
/// `cFirst cLast w` (a run of CIDs sharing one width).
fn parse_cid_widths(w: &Object, out: &mut HashMap<u32, f32>) {
    let Ok(arr) = w.as_array() else { return };
    let mut i = 0;
    while i < arr.len() {
        let Ok(first) = arr[i].as_i64() else {
            i += 1;
            continue;
        };
        if i + 1 >= arr.len() {
            break;
        }
        if let Ok(sub) = arr[i + 1].as_array() {
            for (j, width) in sub.iter().enumerate() {
                if let Ok(wv) = width.as_float().map(|v| v as f64).or_else(|_| width.as_i64().map(|v| v as f64)) {
                    out.insert((first + j as i64) as u32, wv as f32);
                }
            }
            i += 2;
        } else if i + 2 < arr.len() {
            if let (Ok(last), Ok(width)) = (
                arr[i + 1].as_i64(),
                arr[i + 2]
                    .as_float()
                    .map(|v| v as f64)
                    .or_else(|_| arr[i + 2].as_i64().map(|v| v as f64)),
            ) {
                for c in first..=last {
                    out.insert(c as u32, width as f32);
                }
            }
            i += 3;
        } else {
            break;
        }
    }
}

/// `/ToUnicode` CMaps are themselves small PDF content-stream-like programs using
/// `beginbfchar`/`endbfchar` and `beginbfrange`/`endbfrange` blocks of hex-string pairs.
fn parse_to_unicode_cmap(data: &[u8]) -> HashMap<u32, String> {
    let mut map = HashMap::new();
    let mut tok = Tokenizer::new(data);
    let mut pending: Vec<Vec<u8>> = Vec::new();
    let mut in_char = false;
    let mut in_range = false;

    while let Some(t) = tok.next_token() {
        match t {
            Token::Operator(op) => match op.as_str() {
                "beginbfchar" => {
                    in_char = true;
                    pending.clear();
                }
                "endbfchar" => in_char = false,
                "beginbfrange" => {
                    in_range = true;
                    pending.clear();
                }
                "endbfrange" => in_range = false,
                _ => {}
            },
            Token::StringLit(bytes) => {
                if in_char {
                    pending.push(bytes);
                    if pending.len() == 2 {
                        let code = be_bytes_to_u32(&pending[0]);
                        let unicode = utf16be_to_string(&pending[1]);
                        map.insert(code, unicode);
                        pending.clear();
                    }
                } else if in_range {
                    pending.push(bytes);
                    if pending.len() == 3 {
                        let lo = be_bytes_to_u32(&pending[0]);
                        let hi = be_bytes_to_u32(&pending[1]);
                        let base = be_bytes_to_u32(&pending[2]);
                        for (offset, code) in (lo..=hi).enumerate() {
                            if let Some(ch) = char::from_u32(base + offset as u32) {
                                map.insert(code, ch.to_string());
                            }
                        }
                        pending.clear();
                    }
                }
            }
            Token::ArrayStart => {
                if in_range && pending.len() == 2 {
                    let lo = be_bytes_to_u32(&pending[0]);
                    let mut code = lo;
                    loop {
                        match tok.next_token() {
                            Some(Token::StringLit(bytes)) => {
                                map.insert(code, utf16be_to_string(&bytes));
                                code += 1;
                            }
                            Some(Token::ArrayEnd) | None => break,
                            _ => {}
                        }
                    }
                    pending.clear();
                }
            }
            _ => {}
        }
    }
    map
}

fn be_bytes_to_u32(bytes: &[u8]) -> u32 {
    bytes.iter().fold(0u32, |acc, &b| (acc << 8) | b as u32)
}

fn utf16be_to_string(bytes: &[u8]) -> String {
    let units: Vec<u16> = bytes
        .chunks(2)
        .filter(|c| c.len() == 2)
        .map(|c| ((c[0] as u16) << 8) | c[1] as u16)
        .collect();
    String::from_utf16_lossy(&units)
}
