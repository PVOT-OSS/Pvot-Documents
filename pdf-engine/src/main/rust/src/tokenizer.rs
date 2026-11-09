/// A minimal PDF content-stream tokenizer. Content streams use the same object syntax as the
/// rest of a PDF (numbers, names, strings, arrays, dicts) plus bare-word operators, so this is a
/// small PostScript-ish lexer rather than a reuse of `lopdf`'s own parser (which is built around
/// whole indirect objects / xref-aware parsing, not a stream of operators interleaved with
/// operands).
#[derive(Debug, Clone)]
pub enum Token {
    Number(f64),
    Name(String),
    StringLit(Vec<u8>),
    ArrayStart,
    ArrayEnd,
    DictStart,
    DictEnd,
    Operator(String),
}

pub struct Tokenizer<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> Tokenizer<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn peek(&self) -> Option<u8> {
        self.data.get(self.pos).copied()
    }

    fn is_whitespace(b: u8) -> bool {
        matches!(b, b' ' | b'\t' | b'\r' | b'\n' | b'\x0c' | b'\0')
    }

    fn is_delim(b: u8) -> bool {
        matches!(
            b,
            b'(' | b')' | b'<' | b'>' | b'[' | b']' | b'{' | b'}' | b'/' | b'%'
        )
    }

    fn skip_whitespace_and_comments(&mut self) {
        loop {
            match self.peek() {
                Some(b) if Self::is_whitespace(b) => self.pos += 1,
                Some(b'%') => {
                    while let Some(b) = self.peek() {
                        self.pos += 1;
                        if b == b'\n' || b == b'\r' {
                            break;
                        }
                    }
                }
                _ => break,
            }
        }
    }

    fn read_name(&mut self) -> String {
        self.pos += 1; // skip '/'
        let start = self.pos;
        while let Some(b) = self.peek() {
            if Self::is_whitespace(b) || Self::is_delim(b) {
                break;
            }
            self.pos += 1;
        }
        let raw = &self.data[start..self.pos];
        decode_name(raw)
    }

    fn read_literal_string(&mut self) -> Vec<u8> {
        self.pos += 1; // skip '('
        let mut out = Vec::new();
        let mut depth = 1;
        while let Some(b) = self.peek() {
            self.pos += 1;
            match b {
                b'(' => {
                    depth += 1;
                    out.push(b);
                }
                b')' => {
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                    out.push(b);
                }
                b'\\' => {
                    if let Some(next) = self.peek() {
                        self.pos += 1;
                        match next {
                            b'n' => out.push(b'\n'),
                            b'r' => out.push(b'\r'),
                            b't' => out.push(b'\t'),
                            b'b' => out.push(0x08),
                            b'f' => out.push(0x0c),
                            b'(' | b')' | b'\\' => out.push(next),
                            b'\n' => {}
                            b'\r' => {
                                if self.peek() == Some(b'\n') {
                                    self.pos += 1;
                                }
                            }
                            b'0'..=b'7' => {
                                let mut val = (next - b'0') as u32;
                                for _ in 0..2 {
                                    if let Some(d @ b'0'..=b'7') = self.peek() {
                                        val = val * 8 + (d - b'0') as u32;
                                        self.pos += 1;
                                    } else {
                                        break;
                                    }
                                }
                                out.push(val as u8);
                            }
                            other => out.push(other),
                        }
                    }
                }
                other => out.push(other),
            }
        }
        out
    }

    fn read_hex_string(&mut self) -> Vec<u8> {
        self.pos += 1; // skip '<'
        let mut hex_digits = Vec::new();
        while let Some(b) = self.peek() {
            self.pos += 1;
            if b == b'>' {
                break;
            }
            if b.is_ascii_hexdigit() {
                hex_digits.push(b);
            }
        }
        if hex_digits.len() % 2 == 1 {
            hex_digits.push(b'0');
        }
        hex_digits
            .chunks(2)
            .map(|c| {
                let s = std::str::from_utf8(c).unwrap_or("0");
                u8::from_str_radix(s, 16).unwrap_or(0)
            })
            .collect()
    }

    fn read_number_or_operator(&mut self) -> Token {
        let start = self.pos;
        let mut is_number = true;
        let mut seen_digit = false;
        while let Some(b) = self.peek() {
            if Self::is_whitespace(b) || Self::is_delim(b) {
                break;
            }
            if !(b.is_ascii_digit() || b == b'.' || b == b'-' || b == b'+' || b == b'e' || b == b'E') {
                is_number = false;
            }
            if b.is_ascii_digit() {
                seen_digit = true;
            }
            self.pos += 1;
        }
        let raw = std::str::from_utf8(&self.data[start..self.pos]).unwrap_or("");
        if is_number && seen_digit {
            if let Ok(n) = raw.parse::<f64>() {
                return Token::Number(n);
            }
        }
        Token::Operator(raw.to_string())
    }

    pub fn next_token(&mut self) -> Option<Token> {
        self.skip_whitespace_and_comments();
        let b = self.peek()?;
        let tok = match b {
            b'/' => Token::Name(self.read_name()),
            b'(' => Token::StringLit(self.read_literal_string()),
            b'<' => {
                if self.data.get(self.pos + 1) == Some(&b'<') {
                    self.pos += 2;
                    Token::DictStart
                } else {
                    Token::StringLit(self.read_hex_string())
                }
            }
            b'>' => {
                if self.data.get(self.pos + 1) == Some(&b'>') {
                    self.pos += 2;
                    Token::DictEnd
                } else {
                    self.pos += 1;
                    return self.next_token();
                }
            }
            b'[' => {
                self.pos += 1;
                Token::ArrayStart
            }
            b']' => {
                self.pos += 1;
                Token::ArrayEnd
            }
            b'{' | b'}' => {
                self.pos += 1;
                return self.next_token();
            }
            _ => self.read_number_or_operator(),
        };
        Some(tok)
    }
}

fn decode_name(raw: &[u8]) -> String {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0;
    while i < raw.len() {
        if raw[i] == b'#' && i + 2 < raw.len() {
            let hex = std::str::from_utf8(&raw[i + 1..i + 3]).unwrap_or("");
            if let Ok(v) = u8::from_str_radix(hex, 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(raw[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}
