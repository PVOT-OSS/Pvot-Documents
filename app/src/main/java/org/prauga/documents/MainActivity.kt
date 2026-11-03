package org.prauga.documents

import android.os.Bundle
import com.prauga.pvot.designsystem.effects.ProvidePvotGlassIntensity
import com.prauga.pvot.designsystem.platform.PvotBaseActivity
import com.prauga.pvot.designsystem.theme.PvotAppTheme
import org.prauga.documents.ui.AppShell
import org.prauga.documents.ui.screens.OnboardingScreen

class MainActivity : PvotBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = AppPreferences(applicationContext)
        setPvotContent {
            PvotAppTheme(dynamicColor = preferences.dynamicColor) {
                // The preference, held to the device: solid under battery saver.
                ProvidePvotGlassIntensity(preferences.glassIntensity) {
                    if (preferences.onboardingComplete) {
                        AppShell(preferences)
                    } else {
                        OnboardingScreen(preferences)
                    }
                }
            }
        }
    }
}
