package com.sheen.spamblocker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sheen.spamblocker.ui.calllog.CallLogScreen
import com.sheen.spamblocker.ui.history.HistoryScreen
import com.sheen.spamblocker.ui.home.HomeScreen
import com.sheen.spamblocker.ui.onboarding.OnboardingScreen
import com.sheen.spamblocker.ui.rules.RulesScreen
import com.sheen.spamblocker.ui.settings.SettingsScreen
import com.sheen.spamblocker.ui.sources.SourcesScreen
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable data object Onboarding
@Serializable data object ImportOnly
@Serializable data object Home
@Serializable data object History
@Serializable data object CallLogRoute
@Serializable data object Rules
@Serializable data object Sources
@Serializable data object SettingsRoute

private data class TopLevel(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

@Suppress("DEPRECATION") // Icons.Filled.List; the AutoMirrored variant isn't in material-icons-core.
private val topLevel = listOf(
    TopLevel(Home, Home::class, "Home", Icons.Filled.Home),
    TopLevel(History, History::class, "History", Icons.Filled.DateRange),
    TopLevel(CallLogRoute, CallLogRoute::class, "Call log", Icons.Filled.Call),
    TopLevel(Rules, Rules::class, "Rules", Icons.Filled.Lock),
    TopLevel(Sources, Sources::class, "Sources", Icons.Filled.List),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(showOnboarding: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val current = topLevel.firstOrNull { t -> destination?.hasRoute(t.type) == true }
    val onSettings = destination?.hasRoute(SettingsRoute::class) == true
    val inOnboarding = destination?.hasRoute(Onboarding::class) == true || destination?.hasRoute(ImportOnly::class) == true

    Scaffold(
        topBar = {
            if (!inOnboarding) {
                TopAppBar(
                    title = { Text(current?.label ?: if (onSettings) "Settings" else "") },
                    navigationIcon = {
                        if (onSettings) {
                            @Suppress("DEPRECATION")
                            IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.ArrowBack, "Back") }
                        }
                    },
                    actions = {
                        if (current != null) {
                            IconButton(onClick = { nav.navigate(SettingsRoute) }) { Icon(Icons.Filled.Settings, "Settings") }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (current != null) {
                NavigationBar {
                    topLevel.forEach { t ->
                        NavigationBarItem(
                            selected = t == current,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = if (showOnboarding) Onboarding else Home, modifier = Modifier.padding(padding)) {
            composable<Onboarding> {
                OnboardingScreen(onDone = { nav.navigate(Home) { popUpTo<Onboarding> { inclusive = true } } })
            }
            composable<ImportOnly> { OnboardingScreen(onDone = { nav.popBackStack() }, importOnly = true) }
            composable<Home> { HomeScreen() }
            composable<History> { HistoryScreen() }
            composable<CallLogRoute> { CallLogScreen() }
            composable<Rules> { RulesScreen() }
            composable<Sources> { SourcesScreen() }
            composable<SettingsRoute> { SettingsScreen(onRerunImport = { nav.navigate(ImportOnly) }) }
        }
    }
}
