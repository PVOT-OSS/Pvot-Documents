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

        let content = self
            .doc
            .get_page_content(page_id)
            .map_err(|e| e.to_string())?;

        let _ = page_dict;
        Ok(PageInfo {
            content,
            resources,
            media_box,
            rotate,
        })
    }
}
