mod content;
mod doc;
mod encodings;
mod font;
mod matrix;
mod search;
mod tokenizer;
mod wire;

use content::DecodedImage;
use doc::PdfDoc;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jfloat, jfloatArray, jint, jlong};
use jni::JNIEnv;
use std::collections::HashMap;

/// A document handle bundles the parsed PDF with a per-page cache of the last decoded images
/// (searching/re-rendering a page re-runs the content interpreter, but we don't want to decode
/// the same embedded images twice in a row just to answer `nativeGetImage*`).
struct Handle {
    pdf: PdfDoc,
    images: HashMap<usize, Vec<DecodedImage>>,
}

fn handle_mut<'a>(ptr: jlong) -> Option<&'a mut Handle> {
    if ptr == 0 {
        return None;
    }
    Some(unsafe { &mut *(ptr as *mut Handle) })
}

fn float_array<'local>(env: &mut JNIEnv<'local>, data: &[f32]) -> jfloatArray {
    match env.new_float_array(data.len() as i32) {
        Ok(arr) => {
            let _ = env.set_float_array_region(&arr, 0, data);
            arr.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeOpen<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytes: JByteArray<'local>,
) -> jlong {
    let data = match env.convert_byte_array(&bytes) {
        Ok(d) => d,
        Err(_) => return 0,
    };
    match PdfDoc::open(data) {
        Ok(pdf) => {
            let handle = Box::new(Handle { pdf, images: HashMap::new() });
            Box::into_raw(handle) as jlong
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeClose<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle as *mut Handle));
    }
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativePageCount<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    handle_mut(handle).map(|h| h.pdf.page_count() as jint).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativePageSize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
) -> jfloatArray {
    let out: [jfloat; 3] = handle_mut(handle)
        .and_then(|h| h.pdf.page_info(page_index as usize).ok())
        .map(|info| {
            [
                info.media_box[2] - info.media_box[0],
                info.media_box[3] - info.media_box[1],
                info.rotate as f32,
            ]
        })
        .unwrap_or([612.0, 792.0, 0.0]);
    float_array(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeRenderPage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
) -> JByteArray<'local> {
    let empty = || env.new_byte_array(0).unwrap();
    let Some(h) = handle_mut(handle) else { return empty() };
    let Ok(info) = h.pdf.page_info(page_index as usize) else { return empty() };
    let result = content::render_page(&h.pdf.doc, &info.content, &info.resources);
    h.images.insert(page_index as usize, result.images);
    let len = result.wire.len() as i32;
    match env.new_byte_array(len) {
        Ok(arr) => {
            let signed: Vec<i8> = result.wire.iter().map(|&b| b as i8).collect();
            let _ = env.set_byte_array_region(&arr, 0, &signed);
            arr
        }
        Err(_) => empty(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeImageCount<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
) -> jint {
    handle_mut(handle)
        .and_then(|h| h.images.get(&(page_index as usize)))
        .map(|v| v.len() as jint)
        .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeImageInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
    image_index: jint,
) -> jfloatArray {
    let out: [jfloat; 2] = handle_mut(handle)
        .and_then(|h| h.images.get(&(page_index as usize)))
        .and_then(|v| v.get(image_index as usize))
        .map(|img| [img.width as f32, img.height as f32])
        .unwrap_or([0.0, 0.0]);
    float_array(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeImagePixels<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
    image_index: jint,
) -> JByteArray<'local> {
    let empty = || env.new_byte_array(0).unwrap();
    let Some(h) = handle_mut(handle) else { return empty() };
    let Some(img) = h.images.get(&(page_index as usize)).and_then(|v| v.get(image_index as usize)) else {
        return empty();
    };
    let len = img.rgba.len() as i32;
    match env.new_byte_array(len) {
        Ok(arr) => {
            let signed: Vec<i8> = img.rgba.iter().map(|&b| b as i8).collect();
            let _ = env.set_byte_array_region(&arr, 0, &signed);
            arr
        }
        Err(_) => empty(),
    }
}

#[no_mangle]
pub extern "system" fn Java_org_prauga_pdfengine_PdfNative_nativeSearchPage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    page_index: jint,
    query: JString<'local>,
) -> jfloatArray {
    let query_str: String = env.get_string(&query).map(|s| s.into()).unwrap_or_default();
    let hits: Vec<f32> = handle_mut(handle)
        .and_then(|h| {
            let info = h.pdf.page_info(page_index as usize).ok()?;
            let result = content::render_page(&h.pdf.doc, &info.content, &info.resources);
            h.images.insert(page_index as usize, result.images);
            Some(search::search_page(&result.wire, &query_str))
        })
        .map(|rects| rects.into_iter().flatten().collect())
        .unwrap_or_default();
    float_array(&mut env, &hits)
}
