use lopdf::{Dictionary, Document, Object, ObjectId};

/// A page's static geometry: its content streams (already concatenated+decoded),
/// its resource dictionary (for font/image/xobject lookups) and its box/rotation.
pub struct PageInfo {
    pub content: Vec<u8>,
    pub resources: Dictionary,
    pub media_box: [f32; 4],
    pub rotate: i64,
}

pub struct PdfDoc {
    pub doc: Document,
    pub page_ids: Vec<ObjectId>,
}

impl PdfDoc {
    pub fn open(bytes: Vec<u8>) -> Result<Self, String> {
        let doc = Document::load_mem(&bytes).map_err(|e| e.to_string())?;
        let page_ids: Vec<ObjectId> = doc.get_pages().values().cloned().collect();
        Ok(Self { doc, page_ids })
    }

    pub fn page_count(&self) -> usize {
        self.page_ids.len()
    }

    /// Walks the page's ancestor chain to resolve an inherited attribute
    /// (MediaBox, Resources, Rotate all inherit from the Pages tree per the spec).
    fn inherited<'a>(&'a self, mut id: ObjectId, key: &[u8]) -> Option<&'a Object> {
        loop {
            let dict = self.doc.get_dictionary(id).ok()?;
            if let Ok(val) = dict.get(key) {
                return Some(val);
            }
            match dict.get(b"Parent").ok().and_then(|p| p.as_reference().ok()) {
                Some(parent) if parent != id => id = parent,
                _ => return None,
            }
        }
    }

    pub fn page_info(&self, index: usize) -> Result<PageInfo, String> {
        let page_id = *self.page_ids.get(index).ok_or("page index out of range")?;
        let page_dict = self.doc.get_dictionary(page_id).map_err(|e| e.to_string())?;

        let media_box = self
            .inherited(page_id, b"MediaBox")
            .and_then(|o| self.doc.dereference(o).ok())
            .and_then(|(_, o)| o.as_array().ok())
            .map(|arr| {
                let v: Vec<f32> = arr
                    .iter()
                    .map(|n| n.as_float().unwrap_or_else(|_| n.as_i64().unwrap_or(0) as f32))
                    .collect();
                if v.len() == 4 {
                    [v[0], v[1], v[2], v[3]]
                } else {
                    [0.0, 0.0, 612.0, 792.0]
                }
            })
            .unwrap_or([0.0, 0.0, 612.0, 792.0]);

        let rotate = self
            .inherited(page_id, b"Rotate")
            .and_then(|o| o.as_i64().ok())
            .unwrap_or(0)
            .rem_euclid(360);

        let resources: Dictionary = self
            .inherited(page_id, b"Resources")
            .and_then(|o| self.doc.dereference(o).ok())
            .and_then(|(_, o)| o.as_dict().ok().cloned())
            .unwrap_or_default();

        // Not `self.doc.get_page_content`: on any decompression failure it silently falls back
        // to the stream's still-encoded raw bytes rather than erroring — and its decoder only
        // knows FlateDecode/LZWDecode, not ASCII85Decode. A still-armored content stream looks
        // enough like printable PDF syntax that our tokenizer doesn't fail outright either; it
        // just quietly chews through nonsense operators and never finds the real content.
        let mut content = Vec::new();
        for stream_id in self.doc.get_page_contents(page_id) {
            if let Ok(stream) = self.doc.get_object(stream_id).and_then(lopdf::Object::as_stream) {
                content.extend(decode_content_stream(stream));
                content.push(b'\n');
            }
        }

        let _ = page_dict;
        Ok(PageInfo {
            content,
            resources,
            media_box,
            rotate,
        })
    }
}

/// Applies a content stream's own filter chain (in order), the same handful we support for
/// images: FlateDecode and ASCII85Decode. Anything else (or a step that fails outright) just
/// passes the data through as-is — a best-effort fallback, matching what lopdf's own
/// `get_page_content` does on failure, rather than erroring out of rendering the whole page.
fn decode_content_stream(stream: &lopdf::Stream) -> Vec<u8> {
    use std::io::Read;
    let filters = stream.filters().unwrap_or_default();
    let mut data = stream.content.clone();
    for filter in &filters {
        data = match filter.as_str() {
            "FlateDecode" => {
                let mut out = Vec::with_capacity(data.len() * 3);
                match flate2::read::ZlibDecoder::new(&data[..]).read_to_end(&mut out) {
                    Ok(_) => out,
                    Err(_) => return data,
                }
            }
            "ASCII85Decode" => crate::content::ascii85_decode(&data).unwrap_or(data),
            _ => data,
        };
    }
    data
}
