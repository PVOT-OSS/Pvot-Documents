# Third-party notices

## PdfBox-Android

The PDF viewer (`app/src/main/java/org/prauga/documents/viewer/PdfView.kt`) renders through
[PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), a port of Apache PDFBox, Apache
License 2.0. It renders entirely in-process, which is why it's used instead of the platform's
sandboxed PDF renderer. The license text is in `LICENSES/Apache-2.0.txt`.

## Tabler Icons

The icons in `app/src/main/res/drawable/` (`ic_chevron_down.xml`, `ic_chevron_up.xml`,
`ic_download.xml`, `ic_file_text.xml`, `ic_folder.xml`, `ic_folder_plus.xml`, `ic_more.xml`,
`ic_pencil.xml`, `ic_recent.xml`, `ic_restore.xml`, `ic_search.xml`, `ic_settings.xml`,
`ic_share.xml`, `ic_tools.xml`, `ic_trash.xml`, `ic_x.xml`) are converted from
[Tabler Icons](https://tabler.io/icons), Copyright (c) 2020-2026 Paweł Kuna, MIT License. The
license text is in `LICENSES/MIT.txt`.

## lopdf

The Rust PDF engine (`pdf-engine/src/main/rust/`) uses [lopdf](https://github.com/J-F-Liu/lopdf)
for low-level PDF object/xref parsing and stream decompression, MIT License. Everything above
that layer — the content-stream interpreter, font/encoding handling, text extraction and search —
is our own code; see `CLAUDE.md` for why PdfBox-Android was replaced.
