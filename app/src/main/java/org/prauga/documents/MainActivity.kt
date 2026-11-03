package org.prauga.documents

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prauga.pvot.designsystem.effects.ProvidePvotGlassIntensity
import com.prauga.pvot.designsystem.platform.PvotBaseActivity
import com.prauga.pvot.designsystem.theme.PvotAppTheme
import org.prauga.documents.extensions.parcelableExtraCompat
import org.prauga.documents.ui.AppShell
import org.prauga.documents.ui.screens.IncomingDocumentHandler
import org.prauga.documents.ui.screens.OnboardingScreen

class MainActivity : PvotBaseActivity() {

    // A property, not local state: onNewIntent runs outside composition and needs somewhere to
    // put the next URI that the Compose tree is already observing.
    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AppPreferences(applicationContext)
        incomingUri = incomingDocumentUri(intent)
        setPvotContent {
            PvotAppTheme(dynamicColor = preferences.dynamicColor) {
                // The preference, held to the device: solid under battery saver.
                ProvidePvotGlassIntensity(preferences.glassIntensity) {
                    if (preferences.onboardingComplete) {
                        AppShell(preferences)
                    } else {
                        OnboardingScreen(preferences)
                    }
                    incomingUri?.let { uri ->
                        IncomingDocumentHandler(
                            uri = uri,
                            preferences = preferences,
                            onHandled = { incomingUri = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = incomingDocumentUri(intent)
    }

    /** The document [intent] was opened or shared with, from "Open with" or "Share". */
    private fun incomingDocumentUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.parcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
        else -> null
    }
}
