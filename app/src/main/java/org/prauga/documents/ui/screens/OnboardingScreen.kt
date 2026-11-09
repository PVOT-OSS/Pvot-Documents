package org.prauga.documents.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.prauga.pvot.designsystem.components.PvotCard
import com.prauga.pvot.designsystem.components.button.PvotButton
import com.prauga.pvot.designsystem.components.button.PvotButtonStyle
import com.prauga.pvot.designsystem.theme.PvotSpacing
import org.prauga.documents.AppPreferences
import org.prauga.documents.R
import org.prauga.documents.data.PublicFolder
import org.prauga.documents.data.isPublicFolderAdded

/**
 * Shown once, before [org.prauga.documents.ui.AppShell]: lets the user grant Documents and
 * Downloads up front, so Manage already has them instead of starting empty. There is no API to
 * grant folder access without the system picker, so each button still opens it, pointed straight
 * at that folder through the picker's initial-URI hint (see [PublicFolder.initialUri]) — one tap
 * to confirm rather than a folder hunt. Continuing is always allowed; neither folder is required.
 */
@Composable
fun OnboardingScreen(preferences: AppPreferences, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = PvotSpacing.L,
            end = PvotSpacing.L,
            top = PvotSpacing.L + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = PvotSpacing.L + WindowInsets.navigationBars.asPaddingValues()
                .calculateBottomPadding()
        ),
        verticalArrangement = Arrangement.spacedBy(PvotSpacing.L)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(PvotSpacing.S)) {
                Text(
                    text = stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            FolderOptionCard(
                preferences = preferences,
                folder = PublicFolder.Documents,
                titleRes = R.string.onboarding_documents_title,
                subtitleRes = R.string.onboarding_documents_subtitle,
                iconRes = R.drawable.ic_file_text
            )
        }
        item {
            FolderOptionCard(
                preferences = preferences,
                folder = PublicFolder.Downloads,
                titleRes = R.string.onboarding_downloads_title,
                subtitleRes = R.string.onboarding_downloads_subtitle,
                iconRes = R.drawable.ic_download
            )
        }
        item {
            PvotButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = preferences::completeOnboarding,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun FolderOptionCard(
    preferences: AppPreferences,
    folder: PublicFolder,
    titleRes: Int,
    subtitleRes: Int,
    iconRes: Int
) {
    val context = LocalContext.current
    var added by remember {
        mutableStateOf(isPublicFolderAdded(preferences.folderUris, folder))
    }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            preferences.addFolder(uri, context.contentResolver)
            added = true
        }
    }

    PvotCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(PvotSpacing.L),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PvotSpacing.L)
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(subtitleRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = PvotSpacing.XS)
                )
            }
            PvotButton(
                text = stringResource(
                    if (added) R.string.onboarding_added else R.string.onboarding_allow
                ),
                // OpenDocumentTree's input *is* the initial-URI hint; the picker opens inside
                // `folder` instead of at the storage root.
                onClick = { pickFolder.launch(folder.initialUri) },
                style = if (added) PvotButtonStyle.Tonal else PvotButtonStyle.Filled,
                enabled = !added
            )
        }
    }
}
