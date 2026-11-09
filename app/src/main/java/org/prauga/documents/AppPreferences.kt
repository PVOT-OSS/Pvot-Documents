package org.prauga.documents

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.prauga.pvot.designsystem.effects.PvotGlassDefaults
import org.json.JSONArray
import org.json.JSONObject
import org.prauga.documents.models.DocumentEntry
import org.prauga.documents.models.TrashedDocument
import org.prauga.documents.util.isOwnFile
import java.io.File

/** The app's settings, kept in SharedPreferences and observable from Compose. */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * The app's glass preference, 0 to 1. Held to what the device can draw through
     * [com.prauga.pvot.designsystem.effects.ProvidePvotGlassIntensity], which falls back to solid
     * under battery saver.
     */
    var glassIntensity by mutableFloatStateOf(
        prefs.getFloat(KEY_GLASS_INTENSITY, PvotGlassDefaults.defaultIntensity())
    )
        private set

    fun updateGlassIntensity(intensity: Float) {
        prefs.edit { putFloat(KEY_GLASS_INTENSITY, intensity) }
        glassIntensity = intensity
    }

    /** Material You colors from the system wallpaper, where the device supports them. */
    var dynamicColor by mutableStateOf(prefs.getBoolean(KEY_DYNAMIC_COLOR, false))
        private set

    fun updateDynamicColor(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DYNAMIC_COLOR, enabled) }
        dynamicColor = enabled
    }

    /** Grid instead of a list on the Manage tab. */
    var gridView by mutableStateOf(prefs.getBoolean(KEY_GRID_VIEW, false))
        private set

    fun updateGridView(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_GRID_VIEW, enabled) }
        gridView = enabled
    }

    /**
     * Folders the user added through the system picker, as persisted tree URIs. The app holds a
     * persisted read/write permission on each; see [addFolder].
     */
    var folderUris by mutableStateOf(prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty())
        private set

    /** Adds [treeUri] and takes a persisted permission on it, so it survives a reboot. */
    fun addFolder(treeUri: Uri, contentResolver: ContentResolver) {
        contentResolver.takePersistableUriPermission(treeUri, FolderPermissionFlags)
        val updated = folderUris + treeUri.toString()
        prefs.edit { putStringSet(KEY_FOLDERS, updated) }
        folderUris = updated
    }

    /** Removes [treeUri] and releases the permission taken in [addFolder]. */
    fun removeFolder(treeUri: Uri, contentResolver: ContentResolver) {
        runCatching {
            contentResolver.releasePersistableUriPermission(treeUri, FolderPermissionFlags)
        }
        val updated = folderUris - treeUri.toString()
        prefs.edit { putStringSet(KEY_FOLDERS, updated) }
        folderUris = updated
    }

    /** Whether the user has been through [org.prauga.documents.ui.screens.OnboardingScreen]. */
    var onboardingComplete by mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false))
        private set

    fun completeOnboarding() {
        prefs.edit { putBoolean(KEY_ONBOARDING_COMPLETE, true) }
        onboardingComplete = true
    }

    /**
     * Whether a document opened from another app is copied into Documents' own storage: null
     * until the user has been asked once, through the prompt in
     * [org.prauga.documents.ui.screens.IncomingDocumentHandler]. Changeable afterward from
     * Settings.
     */
    var autosaveOpenedDocuments by mutableStateOf(
        if (prefs.contains(KEY_AUTOSAVE)) prefs.getBoolean(KEY_AUTOSAVE, true) else null
    )
        private set

    fun setAutosaveOpenedDocuments(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTOSAVE, enabled) }
        autosaveOpenedDocuments = enabled
    }

    /**
     * Documents opened from another app, most recent first, capped at [MaxRecentDocuments]. An
     * autosaved entry points at Documents' own copy; a not-saved one keeps the original content
     * URI, which may stop resolving once the sending app's grant expires.
     */
    var recentDocuments by mutableStateOf(
        // Entries an older build recorded for a path it could never read (a `file://` URI from
        // another app's "Open with", outside Documents' own storage) are dropped on load.
        decodeRecentDocuments(prefs.getString(KEY_RECENT, null)).filter {
            it.uri.scheme != "file" || isOwnFile(context, File(it.uri.path.orEmpty()))
        }
    )
        private set

    /** Drops [entry] from [recentDocuments], such as one whose document no longer opens. */
    fun removeRecentDocument(entry: DocumentEntry) {
        val updated = recentDocuments.filterNot { it.uri == entry.uri }
        prefs.edit { putString(KEY_RECENT, encodeRecentDocuments(updated)) }
        recentDocuments = updated
    }

    /**
     * Adds [entry], replacing any existing entry for the same document. Re-opening an unsaved
     * document resolves to the same content URI, so a URI match is enough there; a fresh autosave
     * is a new file with a new URI each time, so a name-and-size match catches that case too — and
     * the old copy, now unreferenced, is deleted rather than left behind.
     */
    fun addRecentDocument(entry: DocumentEntry) {
        val superseded = recentDocuments.filter {
            it.uri == entry.uri || (it.name == entry.name && it.sizeBytes == entry.sizeBytes)
        }
        superseded.forEach { old ->
            if (old.uri.scheme == "file" && old.uri != entry.uri) {
                // Delete the file, then its folder only if that leaves it empty: each copy from
                // copyToAppStorage() gets its own folder, but an old build's file may not have, and
                // a recursive delete of a shared folder would take other copies down with it.
                old.uri.path?.let { path ->
                    val file = File(path)
                    file.delete()
                    file.parentFile?.delete()
                }
            }
        }
        val updated = (listOf(entry) + (recentDocuments - superseded.toSet())).take(MaxRecentDocuments)
        prefs.edit { putString(KEY_RECENT, encodeRecentDocuments(updated)) }
        recentDocuments = updated
    }

    /**
     * Documents moved to Recently deleted, most recent first — see
     * [org.prauga.documents.data.DocumentsRepository.moveToTrash].
     */
    var trashedDocuments by mutableStateOf(decodeTrashedDocuments(prefs.getString(KEY_TRASH, null)))
        private set

    fun addTrashedDocument(entry: TrashedDocument) {
        val updated = listOf(entry) + trashedDocuments
        prefs.edit { putString(KEY_TRASH, encodeTrashedDocuments(updated)) }
        trashedDocuments = updated
    }

    /** Drops [entry] after it's been restored or deleted for good; the repository handles the
     * file itself. */
    fun removeTrashedDocument(entry: TrashedDocument) {
        val updated = trashedDocuments - entry
        prefs.edit { putString(KEY_TRASH, encodeTrashedDocuments(updated)) }
        trashedDocuments = updated
    }

    /** Whether PDF pages are drawn dark (colors inverted, hues kept) for reading at night. */
    var darkPdfPages by mutableStateOf(prefs.getBoolean(KEY_DARK_PDF_PAGES, false))
        private set

    fun updateDarkPdfPages(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DARK_PDF_PAGES, enabled) }
        darkPdfPages = enabled
    }

    /** Where reading stopped in the document [key] names (see `readingKey` in the viewer), if
     * it has been opened before. */
    fun readingPosition(key: String): ReadingPosition? {
        return decodeReadingPositions(prefs.getString(KEY_READING_POSITIONS, null))
            .firstOrNull { it.first == key }?.second
    }

    /** Remembers [position] for the document [key] names, most recent first; only the last
     * [MaxReadingPositions] documents are kept, so the list doesn't grow forever. */
    fun saveReadingPosition(key: String, position: ReadingPosition) {
        val existing = decodeReadingPositions(prefs.getString(KEY_READING_POSITIONS, null))
        val updated = (listOf(key to position) + existing.filterNot { it.first == key })
            .take(MaxReadingPositions)
        val json = JSONArray()
        updated.forEach { (entryKey, pos) ->
            json.put(JSONObject().put("key", entryKey).put("page", pos.page).put("offset", pos.offsetFraction.toDouble()))
        }
        prefs.edit { putString(KEY_READING_POSITIONS, json.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "documents_preferences"
        const val KEY_GLASS_INTENSITY = "glass_intensity"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_GRID_VIEW = "grid_view"
        const val KEY_FOLDERS = "folder_uris"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_AUTOSAVE = "autosave_opened_documents"
        const val KEY_RECENT = "recent_documents"
        const val KEY_TRASH = "trashed_documents"
        const val KEY_DARK_PDF_PAGES = "dark_pdf_pages"
        const val KEY_READING_POSITIONS = "reading_positions"
        const val MaxReadingPositions = 100

        fun decodeReadingPositions(json: String?): List<Pair<String, ReadingPosition>> =
            runCatching {
                val array = JSONArray(json ?: return emptyList())
                List(array.length()) { i ->
                    val o = array.getJSONObject(i)
                    o.getString("key") to ReadingPosition(o.getInt("page"), o.optDouble("offset", 0.0).toFloat())
                }
            }.getOrDefault(emptyList())
        const val MaxRecentDocuments = 50
        const val FolderPermissionFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun JSONObject.putDocumentEntry(entry: DocumentEntry): JSONObject = apply {
            put("uri", entry.uri.toString())
            put("name", entry.name)
            put("mimeType", entry.mimeType)
            put("sizeBytes", entry.sizeBytes)
            put("lastModified", entry.lastModified)
        }

        fun JSONObject.getDocumentEntry(): DocumentEntry = DocumentEntry(
            uri = Uri.parse(getString("uri")),
            name = getString("name"),
            mimeType = getString("mimeType"),
            sizeBytes = getLong("sizeBytes"),
            lastModified = getLong("lastModified")
        )

        fun encodeRecentDocuments(entries: List<DocumentEntry>): String {
            val array = JSONArray()
            entries.forEach { entry -> array.put(JSONObject().putDocumentEntry(entry)) }
            return array.toString()
        }

        fun decodeRecentDocuments(json: String?): List<DocumentEntry> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { index -> array.getJSONObject(index).getDocumentEntry() }
            }.getOrDefault(emptyList())
        }

        fun encodeTrashedDocuments(entries: List<TrashedDocument>): String {
            val array = JSONArray()
            entries.forEach { entry ->
                array.put(
                    JSONObject().putDocumentEntry(entry.document).apply {
                        put("folderTreeUri", entry.folderTreeUri.toString())
                        put("deletedAt", entry.deletedAt)
                    }
                )
            }
            return array.toString()
        }

        fun decodeTrashedDocuments(json: String?): List<TrashedDocument> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    TrashedDocument(
                        document = item.getDocumentEntry(),
                        folderTreeUri = Uri.parse(item.getString("folderTreeUri")),
                        deletedAt = item.getLong("deletedAt")
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}

/** A place in a PDF: the zero-based [page] at the top of the screen, and how far down it the screen
 * starts, as a fraction of the page's height. */
data class ReadingPosition(val page: Int, val offsetFraction: Float)
