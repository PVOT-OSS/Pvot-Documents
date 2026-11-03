package org.prauga.documents

import android.content.Context
import android.content.SharedPreferences
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

    /** Grid instead of a list on the Browse tab. */
    var gridView by mutableStateOf(prefs.getBoolean(KEY_GRID_VIEW, false))
        private set

    fun updateGridView(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_GRID_VIEW, enabled) }
        gridView = enabled
    }

    private companion object {
        const val PREFS_NAME = "documents_preferences"
        const val KEY_GLASS_INTENSITY = "glass_intensity"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_GRID_VIEW = "grid_view"
    }
}
