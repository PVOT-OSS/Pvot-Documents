//! Binary wire format for a rendered page, handed to Kotlin as a single `ByteArray` and decoded
//! by `PdfWireReader` on the other side (`pdf-engine/src/main/java/.../PdfWireFormat.kt` — keep
//! both in sync, this is the only spec for the format). Little-endian throughout.
//!
//! All coordinates are in the page's own PDF user space (origin bottom-left, y-up, units =
//! 1/72in) after the content stream's own `cm`/`Tm` transforms have been applied — NOT yet
//! flipped or scaled for a device canvas. The Kotlin renderer applies the page-height flip and
//! current zoom scale itself, once, for every op, rather than Rust baking in a transform that
//! would have to be recomputed per zoom level.
//!
//! Opcodes:
//! - 0  SET_FILL_COLOR    u8 r, u8 g, u8 b, u8 a
//! - 1  SET_STROKE_COLOR  u8 r, u8 g, u8 b, u8 a
//! - 2  SET_LINE_WIDTH    f32 width
//! - 3  MOVE_TO           f32 x, f32 y
//! - 4  LINE_TO           f32 x, f32 y
//! - 5  CURVE_TO          f32 x1, f32 y1, f32 x2, f32 y2, f32 x3, f32 y3
//! - 6  CLOSE_SUBPATH
//! - 7  FILL_PATH         u8 evenOdd
//! - 8  STROKE_PATH
//! - 9  FILL_AND_STROKE   u8 evenOdd
//! - 10 SET_CLIP_RECT     f32 x, f32 y, f32 w, f32 h
//! - 11 SAVE_STATE
//! - 12 RESTORE_STATE
//! - 13 DRAW_GLYPH        f32 x, f32 y, f32 fontSize, f32 advance, u8 style (bit0 bold, bit1
//!   italic), u16 utf8Len, [u8; utf8Len]
//! - 14 DRAW_IMAGE        f32 a, f32 b, f32 c, f32 d, f32 e, f32 f, u16 imageIndex — the CTM
//!   mapping the image's unit square to page space (not just a bounding box: images placed via a
//!   rotated or skewed `cm`, common for phone-scanned pages, need the real transform or they come
//!   out as an unrotated, non-uniformly stretched mess)
//! - 15 END_PATH          discards the path built since the last paint/clear op, without
//!   painting it (the `n` operator — typically following `re W n` to clip without filling).
//!   Every op that consumes the accumulated path (a paint op or this one) MUST be emitted, or
//!   the next path's segments get appended onto this one's leftover contours on the Kotlin side.
//! - 255 END

pub const OP_SET_FILL_COLOR: u8 = 0;
pub const OP_SET_STROKE_COLOR: u8 = 1;
pub const OP_SET_LINE_WIDTH: u8 = 2;
pub const OP_MOVE_TO: u8 = 3;
pub const OP_LINE_TO: u8 = 4;
pub const OP_CURVE_TO: u8 = 5;
pub const OP_CLOSE_SUBPATH: u8 = 6;
pub const OP_FILL_PATH: u8 = 7;
pub const OP_STROKE_PATH: u8 = 8;
pub const OP_FILL_AND_STROKE: u8 = 9;
pub const OP_SET_CLIP_RECT: u8 = 10;
pub const OP_SAVE_STATE: u8 = 11;
pub const OP_RESTORE_STATE: u8 = 12;
pub const OP_DRAW_GLYPH: u8 = 13;
pub const OP_DRAW_IMAGE: u8 = 14;
pub const OP_END_PATH: u8 = 15;
pub const OP_END: u8 = 255;

#[derive(Default)]
pub struct WireWriter {
    pub buf: Vec<u8>,
}

impl WireWriter {
    pub fn new() -> Self {
        Self { buf: Vec::with_capacity(4096) }
    }

    pub fn op(&mut self, op: u8) {
        self.buf.push(op);
    }

    pub fn u8(&mut self, v: u8) {
        self.buf.push(v);
    }

    pub fn u16(&mut self, v: u16) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    pub fn f32(&mut self, v: f32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    pub fn bytes(&mut self, v: &[u8]) {
        self.buf.extend_from_slice(v);
    }

    pub fn finish(mut self) -> Vec<u8> {
        self.op(OP_END);
        self.buf
    }
}
