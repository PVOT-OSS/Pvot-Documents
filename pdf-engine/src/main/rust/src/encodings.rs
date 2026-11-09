/// WinAnsiEncoding (CP1252), byte code -> Unicode scalar. This is the base encoding for the
/// overwhelming majority of non-symbolic Western-text PDFs; `/Differences` entries (parsed in
/// `font.rs`) override individual codes on top of this table, and an embedded `/ToUnicode` CMap
/// (also in `font.rs`) overrides the whole thing when present.
pub fn win_ansi(code: u8) -> Option<char> {
    let c = code as u32;
    let mapped = match code {
        0x00..=0x7f => c,
        0x80 => 0x20AC,
        0x82 => 0x201A,
        0x83 => 0x0192,
        0x84 => 0x201E,
        0x85 => 0x2026,
        0x86 => 0x2020,
        0x87 => 0x2021,
        0x88 => 0x02C6,
        0x89 => 0x2030,
        0x8A => 0x0160,
        0x8B => 0x2039,
        0x8C => 0x0152,
        0x8E => 0x017D,
        0x91 => 0x2018,
        0x92 => 0x2019,
        0x93 => 0x201C,
        0x94 => 0x201D,
        0x95 => 0x2022,
        0x96 => 0x2013,
        0x97 => 0x2014,
        0x98 => 0x02DC,
        0x99 => 0x2122,
        0x9A => 0x0161,
        0x9B => 0x203A,
        0x9C => 0x0153,
        0x9E => 0x017E,
        0x9F => 0x0178,
        0xA0 => 0x00A0,
        0x81 | 0x8D | 0x8F | 0x90 | 0x9D => return None,
        0xA1..=0xFF => c,
    };
    char::from_u32(mapped)
}

/// A small subset of the standard Adobe glyph list, enough for the glyph names that actually
/// appear in `/Differences` arrays in the wild (accented Latin letters, common punctuation).
/// Falls back to parsing `uniXXXX` / `uXXXX` names, which covers everything else a real-world
/// producer tends to emit.
pub fn glyph_name_to_unicode(name: &str) -> Option<char> {
    if let Some(hex) = name.strip_prefix("uni") {
        if hex.len() >= 4 {
            if let Ok(v) = u32::from_str_radix(&hex[0..4], 16) {
                return char::from_u32(v);
            }
        }
    }
    if let Some(hex) = name.strip_prefix('u') {
        if hex.len() >= 4 && hex.chars().all(|c| c.is_ascii_hexdigit()) {
            if let Ok(v) = u32::from_str_radix(hex, 16) {
                return char::from_u32(v);
            }
        }
    }
    let c = match name {
        "space" => ' ',
        "exclam" => '!',
        "quotedbl" => '"',
        "numbersign" => '#',
        "dollar" => '$',
        "percent" => '%',
        "ampersand" => '&',
        "quotesingle" | "quoteright" => '\'',
        "parenleft" => '(',
        "parenright" => ')',
        "asterisk" => '*',
        "plus" => '+',
        "comma" => ',',
        "hyphen" | "minus" => '-',
        "period" => '.',
        "slash" => '/',
        "zero" => '0',
        "one" => '1',
        "two" => '2',
        "three" => '3',
        "four" => '4',
        "five" => '5',
        "six" => '6',
        "seven" => '7',
        "eight" => '8',
        "nine" => '9',
        "colon" => ':',
        "semicolon" => ';',
        "less" => '<',
        "equal" => '=',
        "greater" => '>',
        "question" => '?',
        "at" => '@',
        "bracketleft" => '[',
        "backslash" => '\\',
        "bracketright" => ']',
        "asciicircum" => '^',
        "underscore" => '_',
        "grave" | "quoteleft" => '`',
        "braceleft" => '{',
        "bar" => '|',
        "braceright" => '}',
        "asciitilde" => '~',
        "bullet" => '\u{2022}',
        "endash" => '\u{2013}',
        "emdash" => '\u{2014}',
        "quotedblleft" => '\u{201C}',
        "quotedblright" => '\u{201D}',
        "ellipsis" => '\u{2026}',
        "fi" => '\u{FB01}',
        "fl" => '\u{FB02}',
        _ if name.len() == 1 => name.chars().next().unwrap(),
        _ => return None,
    };
    Some(c)
}
