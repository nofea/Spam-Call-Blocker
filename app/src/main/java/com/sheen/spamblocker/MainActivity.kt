package com.sheen.spamblocker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sheen.spamblocker.data.SettingsRepository
import com.sheen.spamblocker.ui.AppNavigation
import com.sheen.spamblocker.ui.theme.SpamBlockerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SpamBlockerTheme {
                // Onboarding runs until the first-run import has been offered (SPEC §11).
                var showOnboarding by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) { showOnboarding = !settings.current().importOffered }
                showOnboarding?.let { AppNavigation(showOnboarding = it) }
            }
        }
    }
}
