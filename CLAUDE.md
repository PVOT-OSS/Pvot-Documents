# Pvot Documents

Pvot Documents (`org.prauga.documents`) is a private, offline document reader and organizer, in
Jetpack Compose on the Pvot design system. It is a PVOT-OSS app; the design system lives in the
`PvotLib/` git submodule, built as an included build (`settings.gradle.kts`) and resolved as
`com.prauga.pvot:design-system` and `core-ui`. The goal is an open source answer to the OnePlus
Documents app.

## Read first

The UI rules are PvotLib's, not this file's. Before changing UI read, in `PvotLib/`:
- `CLAUDE.md`: component table, tokens, working agreements.
- `docs/NEW_APP.md`: the playbook this app was started from, and `docs/GUIDE.md` for every token and
  motion spec.

Short version: use the Pvot component when one exists. Colors only from `MaterialTheme.colorScheme`,
space from `PvotSpacing`, shapes from `PvotShapes`, motion from `PvotMotion`. No hex colors, no magic
`dp`, all text in `strings.xml`. When Pvot has no component for something, propose it for PvotLib;
if it has to be app-local for now, keep it small and list it under "Open items".

## Decisions

- **Everything here is our own code.** Scanning, file parsing (DOCX, Markdown, CSV, HTML, ...) and
  every screen are written for this app, not ported or adapted from another project. No other
  project is credited or referenced in code, commits or docs. Third-party libraries we take a real
  dependency on (not code we copy) are the normal exception and are credited in
  `THIRD_PARTY_NOTICES.md` as usual.
- **Storage through the Storage Access Framework.** The user picks folders; the app keeps persisted
  URI permissions. No storage permission and no `MANAGE_EXTERNAL_STORAGE` for now; "All files
  access" may come later as an option.
- **Fully free software.** Every dependency must be open source and F-Droid friendly: no Google Play
  Services, no ML Kit. Planned: PDFium for rendering, PdfBox-Android for writing PDFs, OpenCV and
  Tesseract for the scanner. AGPL libraries (MuPDF) are out.
- **No network.** The app does not ask for `INTERNET`. Any AI feature is on-device and comes last.
- **Office formats are view-only** in v1, through small parsers of our own rather than a bundled
  office suite.
- **Nav follows OnePlus Docs' shape**: four tabs, Recent / Tools / Manage / Settings.
  Recent is one list with type filter chips (All, Scanned, DOC, XLS, ...), not separate tabs per
  type. Tools is a hub of cards (scan, convert, later AI). Manage holds folders ("Sources"),
  "Created by me", and later Recently deleted / Private Safe. A floating "+" for new/scan is not
  built yet; it's phase 1.

## Phases

0. Scaffold (done): project, PvotLib, app shell with Recent, Tools, Manage and Settings, CI.
1. Library: folders, "Open with" with an autosave-or-not prompt (remembered after), recents with
   filter chips, search, sort, list/grid, multi-select, share, rename, delete to Recently deleted.
   The "+" to create a blank Office file or start a scan.
2. Core viewers (done): PDF, text, code, Markdown, CSV, HTML, JSON, DOCX.
3. Office formats: XLSX/ODS, PPTX/ODP, ODT, maybe EPUB.
4. Editing and PDF tools: text/Markdown/CSV editing, PDF markup and signatures, merge, split,
   reorder, rotate, images to PDF, compress, password. Convert-to-PDF tool in Tools.
5. Scanner: capture, edge detection, perspective correction, filters, multi-page PDF, OCR. Lives in
   Tools and behind the "+".
6. Later: on-device AI (summarize, translate — Tools has the slots already), tablet two-pane,
   widgets, printing, F-Droid, Private Safe (locked folder).

Each phase ends with something that runs and has been looked at on the phone.

## Working agreements

- **Ask before pushing**, and never commit or push on your own initiative otherwise. When told to
  commit, author and committer are `debz-g <77199373+debz-g@users.noreply.github.com>` (set
  `GIT_AUTHOR_*` and `GIT_COMMITTER_*` env vars, do not edit git config). Commit messages must not
  mention Claude, AI or an LLM, and carry no Co-Authored-By or "generated with" line. The same goes
  for PR descriptions.
- `origin` is `PVOT-OSS/Pvot-Documents`. Work goes on a branch and lands through a PR into `main`.
- **Rebase, never merge.** Keep history linear.
- Ask before downloads, deleting data, or anything hard to undo.
- Say what was verified and what was not. "It builds" is not "I looked at it on the phone".
- **Ask before using the test phone.** The S23 is a daily driver and other work may be running on it.

## Layout

```
app/src/main/java/org/prauga/documents/
  MainActivity.kt         PvotBaseActivity, theme, glass intensity provider
  AppPreferences.kt       SharedPreferences-backed settings, observable from Compose
  ui/AppShell.kt          PvotScreenScaffold: top bar per tab, PvotNavBar, four tabs
  ui/screens/             Recent, Tools, Manage (empty states for now), Settings
```

## Toolchain

AGP 9.5.0, Kotlin 2.4.21, Java 21, Gradle 9.6.1, compile SDK 37.1, target SDK 36, min SDK 27
(PvotLib's floor). CI is `PVOT-OSS/workflows` `android-build.yml` with `java-version: '21'` and runs
`assembleDebug assembleRelease lint`.

## Commands

```
export ANDROID_HOME=$HOME/Library/Android/sdk     # there is no local.properties
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :app:installDebug                       # needs ANDROID_SERIAL if several devices
```

The `PvotLib` submodule is pinned to a commit on PvotLib `main` (currently `49513ee`); update it
deliberately with `git -C PvotLib fetch && git -C PvotLib checkout <commit>`, never by tracking a
branch. A bump is its own commit, and it builds against the app before it is committed.

## Release builds

`assembleRelease` runs R8 and resource shrinking, and produces an **unsigned** APK. Release APKs are
signed locally with the keystore kept **outside the repo**, at `../.local/pvot-release.jks`. Never
commit a keystore, a password file or `keystore.properties`.

## Testing on a device

- Test device is the user's Galaxy S23 over adb. Pin `ANDROID_SERIAL` to the USB serial
  (`RZCX51G2MZA`) and re-check `adb devices` before a run.
- The phone is the user's daily phone. Look at the screen before every tap, tap only inside this
  app, and never touch other apps.

## Licensing

GPL-3.0 (`LICENSE`). Tabler icons (MIT) are credited in `THIRD_PARTY_NOTICES.md`, with the per-file
comment kept in each vector drawable.

## Open items

- PvotLib gaps this app will hit: chips (file-type filters), checkboxes (multi-select), circular
  progress (page loading). Propose them for PvotLib before phase 1 needs them.
