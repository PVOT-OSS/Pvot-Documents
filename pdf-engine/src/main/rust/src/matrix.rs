/// A PDF-style affine matrix [a b c d e f], mapping (x, y) -> (a*x + c*y + e, b*x + d*y + f).
/// Points are row vectors multiplied on the left (`p * M`), matching the PDF spec's own
/// convention, which is what makes `concat` below read the same way the spec's "CTM' = M x CTM"
/// composition rule does.
#[derive(Clone, Copy, Debug)]
pub struct Matrix {
    pub a: f32,
    pub b: f32,
    pub c: f32,
    pub d: f32,
    pub e: f32,
    pub f: f32,
}

impl Matrix {
    pub const IDENTITY: Matrix = Matrix {
        a: 1.0,
        b: 0.0,
        c: 0.0,
        d: 1.0,
        e: 0.0,
        f: 0.0,
    };

    pub fn new(a: f32, b: f32, c: f32, d: f32, e: f32, f: f32) -> Self {
        Self { a, b, c, d, e, f }
    }

    /// Returns `self x other`, i.e. applying `self` first, then `other` — the matrix a new CTM
    /// becomes after a `cm` operator with `self` as the operand matrix and `other` as the prior
    /// CTM.
    pub fn concat(self, other: Matrix) -> Matrix {
        Matrix {
            a: self.a * other.a + self.b * other.c,
            b: self.a * other.b + self.b * other.d,
            c: self.c * other.a + self.d * other.c,
            d: self.c * other.b + self.d * other.d,
            e: self.e * other.a + self.f * other.c + other.e,
            f: self.e * other.b + self.f * other.d + other.f,
        }
    }

    pub fn apply(&self, x: f32, y: f32) -> (f32, f32) {
        (self.a * x + self.c * y + self.e, self.b * x + self.d * y + self.f)
    }

    /// Approximate uniform scale factor carried by this matrix — used to turn a text-space font
    /// size into an effective device-independent size for the (non-outline) glyph drawing done on
    /// the Kotlin side.
    pub fn scale_magnitude(&self) -> f32 {
        let sx = (self.a * self.a + self.b * self.b).sqrt();
        let sy = (self.c * self.c + self.d * self.d).sqrt();
        (sx + sy) / 2.0
    }
}
