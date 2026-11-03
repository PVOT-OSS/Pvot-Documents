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

    private companion object {
        const val PREFS_NAME = "documents_preferences"
        const val KEY_GLASS_INTENSITY = "glass_intensity"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_GRID_VIEW = "grid_view"
        const val KEY_FOLDERS = "folder_uris"
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val FolderPermissionFlags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
