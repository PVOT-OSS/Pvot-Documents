use crate::wire::*;

struct Glyph {
    text: String,
    x: f32,
    y: f32,
    font_size: f32,
    advance: f32,
}

/// Re-reads a page's already-produced wire buffer (the same bytes handed to Kotlin for drawing)
/// and pulls out just the `DRAW_GLYPH` ops, in document order. Reusing the render wire as the
/// single source of truth keeps text extraction, search and on-screen drawing from ever
/// disagreeing with each other about glyph position.
fn extract_glyphs(wire: &[u8]) -> Vec<Glyph> {
    let mut glyphs = Vec::new();
    let mut i = 0usize;
    macro_rules! f32_at {
        ($o:expr) => {{
            let b: [u8; 4] = wire[$o..$o + 4].try_into().unwrap_or([0; 4]);
            f32::from_le_bytes(b)
        }};
    }
    while i < wire.len() {
        let op = wire[i];
        i += 1;
        match op {
            OP_SET_FILL_COLOR | OP_SET_STROKE_COLOR => i += 4,
            OP_SET_LINE_WIDTH => i += 4,
            OP_MOVE_TO | OP_LINE_TO => i += 8,
            OP_CURVE_TO => i += 24,
            OP_CLOSE_SUBPATH | OP_SAVE_STATE | OP_RESTORE_STATE | OP_END_PATH => {}
            OP_FILL_PATH | OP_FILL_AND_STROKE => i += 1,
            OP_STROKE_PATH => {}
            OP_SET_CLIP_RECT => i += 16,
            OP_DRAW_GLYPH => {
                if i + 19 > wire.len() { break; }
                let x = f32_at!(i);
                let y = f32_at!(i + 4);
                let font_size = f32_at!(i + 8);
                let advance = f32_at!(i + 12);
                // Byte at i+16 is the bold/italic style flags, not needed for text extraction.
                let len = u16::from_le_bytes([wire[i + 17], wire[i + 18]]) as usize;
                let start = i + 19;
                let end = (start + len).min(wire.len());
                let text = String::from_utf8_lossy(&wire[start..end]).into_owned();
                glyphs.push(Glyph { text, x, y, font_size, advance });
                i = end;
            }
            OP_DRAW_IMAGE => i += 26, // 6 f32 (CTM) + u16 imageIndex
            OP_END => break,
            _ => break,
        }
    }
    glyphs
}

/// Case-insensitive substring search over a page's glyph stream. Returns one bounding rect
/// (`[x, y, w, h]`, page user-space, y-up) per match, covering the glyphs the match spans.
pub fn search_page(wire: &[u8], query: &str) -> Vec<[f32; 4]> {
    if query.is_empty() {
        return Vec::new();
    }
    let glyphs = extract_glyphs(wire);
    let query_lower = query.to_lowercase();
    let mut full_text = String::new();
    let mut glyph_of_char: Vec<usize> = Vec::new();
    for (gi, g) in glyphs.iter().enumerate() {
        for ch in g.text.to_lowercase().chars() {
            full_text.push(ch);
            glyph_of_char.push(gi);
        }
    }

    let mut hits = Vec::new();
    let chars: Vec<char> = full_text.chars().collect();
    let query_chars: Vec<char> = query_lower.chars().collect();
    if query_chars.is_empty() || chars.len() < query_chars.len() {
        return hits;
    }
    let mut start = 0;
    while start + query_chars.len() <= chars.len() {
        if chars[start..start + query_chars.len()] == query_chars[..] {
            let first_glyph = glyph_of_char[start];
            let last_glyph = glyph_of_char[start + query_chars.len() - 1];
            let mut min_x = f32::MAX;
            let mut max_x = f32::MIN;
            let mut min_y = f32::MAX;
            let mut max_y = f32::MIN;
            for g in &glyphs[first_glyph..=last_glyph] {
                min_x = min_x.min(g.x);
                max_x = max_x.max(g.x + g.advance);
                min_y = min_y.min(g.y - g.font_size * 0.25);
                max_y = max_y.max(g.y + g.font_size * 0.85);
            }
            hits.push([min_x, min_y, max_x - min_x, max_y - min_y]);
            start += query_chars.len();
        } else {
            start += 1;
        }
    }
    hits
}
