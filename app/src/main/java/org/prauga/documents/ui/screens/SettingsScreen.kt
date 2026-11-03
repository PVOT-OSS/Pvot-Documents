package org.prauga.documents.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import com.prauga.pvot.coreui.PvotSectionHeader
import com.prauga.pvot.designsystem.components.PvotScreen
import com.prauga.pvot.designsystem.components.PvotScreenDefaults
import com.prauga.pvot.designsystem.components.list.PvotListGroup
import com.prauga.pvot.designsystem.components.list.PvotPreferenceRow
import com.prauga.pvot.designsystem.components.list.PvotSwitchRow
import com.prauga.pvot.designsystem.components.slider.PvotSlider
import com.prauga.pvot.designsystem.effects.PvotGlassDefaults
import com.prauga.pvot.designsystem.effects.PvotGlassIntensity
import com.prauga.pvot.designsystem.motion.pvotReveal
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.AppPreferences
import org.prauga.documents.BuildConfig
import org.prauga.documents.R

/**
 * Appearance and about. The glass slider and view mode are real; everything else earns its place
 * only once the feature behind it exists (see CLAUDE.md "Open items").
 */
@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    state: LazyListState,
    topPadding: Dp,
    modifier: Modifier = Modifier
) {
    PvotScreen(
        modifier = modifier,
        state = state,
        contentPadding = PvotScreenDefaults.contentPadding(top = topPadding)
    ) {
        item {
            PvotSectionHeader(
                title = stringResource(R.string.settings_section_appearance),
                modifier = Modifier
                    .fillMaxWidth()
                    .pvotReveal(0)
            )
        }
        item {
            PvotListGroup(modifier = Modifier.fillMaxWidth().pvotReveal(1)) {
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.settings_grid_title),
                        summary = stringResource(R.string.settings_grid_summary),
                        checked = preferences.gridView,
                        onCheckedChange = preferences::updateGridView
                    )
                }
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.settings_dynamic_color_title),
                        summary = stringResource(R.string.settings_dynamic_color_summary),
                        checked = preferences.dynamicColor,
                        onCheckedChange = preferences::updateDynamicColor
                    )
                }
            }
        }
        item {
            GlassIntensityCard(preferences, Modifier.fillMaxWidth().pvotReveal(2))
        }
        item {
            PvotSectionHeader(
                title = stringResource(R.string.settings_section_open_with),
                modifier = Modifier
                    .fillMaxWidth()
                    .pvotReveal(3)
            )
        }
        item {
            PvotListGroup(modifier = Modifier.fillMaxWidth().pvotReveal(4)) {
                item {
                    PvotSwitchRow(
                        title = stringResource(R.string.settings_autosave_title),
                        summary = stringResource(R.string.settings_autosave_summary),
                        // A document opened before this is ever shown defaults to the common
                        // choice; the switch pins it explicitly the first time it's touched.
                        checked = preferences.autosaveOpenedDocuments ?: true,
                        onCheckedChange = preferences::setAutosaveOpenedDocuments
                    )
                }
            }
        }
        item {
            PvotSectionHeader(
                title = stringResource(R.string.settings_section_about),
                modifier = Modifier
                    .fillMaxWidth()
                    .pvotReveal(5)
            )
        }
        item {
            PvotListGroup(modifier = Modifier.fillMaxWidth().pvotReveal(6)) {
                item {
                    PvotPreferenceRow(
                        title = stringResource(R.string.settings_version_title),
                        value = BuildConfig.VERSION_NAME,
                        showChevron = false,
                        onClick = {}
                    )
                }
            }
        }
    }
}

/**
 * The strength of liquid glass through the app, as a labeled slider over
 * [PvotGlassDefaults.maxIntensity]: the device's own cap, which is 0 wherever it cannot blur.
 */
@Composable
private fun GlassIntensityCard(preferences: AppPreferences, modifier: Modifier = Modifier) {
    val max = PvotGlassDefaults.maxIntensity()
    PvotListGroup(
        modifier = modifier,
        title = stringResource(R.string.settings_glass_title)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PvotSpacing.L)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(glassSubtitleRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = glassLabel(preferences.glassIntensity.coerceAtMost(max)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (max > 0f) {
                    PvotSlider(
                        value = preferences.glassIntensity.coerceAtMost(max),
                        onValueChange = preferences::updateGlassIntensity,
                        valueRange = 0f..max,
                        modifier = Modifier.padding(top = PvotSpacing.S)
                    )
                }
                if (PvotGlassIntensity.isReduced()) {
                    Text(
                        text = stringResource(R.string.settings_glass_reduced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = PvotSpacing.XS)
                    )
                }
            }
        }
    }
}

@Composable
private fun glassSubtitleRes(): Int = when {
    PvotGlassDefaults.isLensSupported -> R.string.settings_glass_subtitle
    PvotGlassDefaults.isBlurSupported -> R.string.settings_glass_subtitle_blur
    else -> R.string.settings_glass_subtitle_flat
}

/** The name of each stop of the glass slider, from the cheapest to the full effect. */
private fun glassLabel(intensity: Float): String = when {
    intensity <= PvotGlassDefaults.SolidIntensity -> "Solid"
    intensity <= PvotGlassDefaults.FrostedIntensity + 0.02f -> "Frosted"
    intensity <= 0.34f -> "Light"
    intensity <= 0.67f -> "Medium"
    intensity < 1f -> "Rich"
    else -> "Full"
}
