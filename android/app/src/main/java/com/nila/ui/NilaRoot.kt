package com.nila.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nila.ui.screens.AskScreen
import com.nila.ui.screens.HealthScreen
import com.nila.ui.screens.HomeScreen
import com.nila.ui.screens.LearnScreen
import com.nila.ui.screens.ScanScreen
import com.nila.ui.screens.SettingsScreen
import com.nila.ui.screens.TimelineScreen
import com.nila.ui.screens.WatchScreen
import com.nila.ui.theme.Appearance
import com.nila.ui.theme.LocalNilaDarkTheme

/**
 * Five destinations, which is Material's ceiling for a bottom bar and also the
 * point past which labels start wrapping on a 1080px screen. Settings is not a
 * destination people move between -- it lives in the top bar.
 */
private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Monitor", Icons.Outlined.Hearing),
    HEALTH("health", "Health", Icons.Outlined.FolderShared),
    WATCH("watch", "Watch", Icons.Outlined.Visibility),
    ASK("ask", "Ask", Icons.Outlined.QuestionAnswer),
    SCAN("scan", "Scan", Icons.Outlined.CameraAlt),
}

private const val SETTINGS_ROUTE = "settings"
private const val LEARN_ROUTE = "learn"
private const val TIMELINE_ROUTE = "timeline"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NilaRoot(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    state: AppState = viewModel(),
) {
    if (!permissionsGranted) {
        PermissionGate(onRequestPermissions)
        return
    }

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination

    val onSettings = current?.route == SETTINGS_ROUTE
    val onLearn = current?.route == LEARN_ROUTE
    val onTimeline = current?.route == TIMELINE_ROUTE

    Scaffold(
        topBar = {
            // Left-aligned rather than centred. A centred title has to share
            // the bar with three actions, so it never actually sits in the
            // middle -- it sits wherever the remaining space happens to put it,
            // which reads as a misaligned title rather than a centred one.
            TopAppBar(
                title = {
                    Text(
                        when {
                            onSettings -> "Settings"
                            onLearn -> "Insights"
                            onTimeline -> "Timeline"
                            else -> Tab.entries
                                .firstOrNull { it.route == current?.route }
                                ?.label ?: "Nila"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    if (onSettings || onLearn || onTimeline) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                                 contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    // The appearance switch is on every screen, including the
                    // ones with a back arrow. It is the one control whose whole
                    // value is being reachable at the moment the light is
                    // wrong, and sending someone into Settings to find it at
                    // three in the morning defeats it.
                    ThemeToggle()
                    if (!onSettings && !onLearn && !onTimeline) {
                        IconButton(onClick = { navController.navigate(TIMELINE_ROUTE) }) {
                            Icon(Icons.Outlined.Timeline, contentDescription = "Timeline")
                        }
                        IconButton(onClick = { navController.navigate(LEARN_ROUTE) }) {
                            Icon(Icons.Outlined.Book, contentDescription = "Insights")
                        }
                        IconButton(onClick = { navController.navigate(SETTINGS_ROUTE) }) {
                            Icon(Icons.Outlined.Tune, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEach { tab ->
                    val selected = current?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.HOME.route) { HomeScreen(state) }
            composable(Tab.HEALTH.route) { HealthScreen(state) }
            composable(TIMELINE_ROUTE) { TimelineScreen(state) }
            composable(Tab.ASK.route) { AskScreen(state) }
            composable(Tab.WATCH.route) { WatchScreen(state) }
            composable(Tab.SCAN.route) { ScanScreen(state) }
            composable(LEARN_ROUTE) { LearnScreen(state) }
            composable(SETTINGS_ROUTE) { SettingsScreen(state) }
        }
    }
}

/**
 * Light and dark, in one tap.
 *
 * The icon shows the destination rather than the current state -- a moon while
 * the app is light, a sun while it is dark -- which is the convention every
 * phone uses for this control and the only one that reads correctly at a
 * glance: the button is a door, and a door is labelled with what is through it.
 */
@Composable
private fun ThemeToggle() {
    val context = LocalContext.current
    val dark = LocalNilaDarkTheme.current
    IconButton(onClick = { Appearance.toggle(context, dark) }) {
        Icon(
            imageVector = if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = if (dark) "Switch to the light theme"
                                 else "Switch to the dark theme",
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nila needs the microphone",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            "It listens for your baby and never records or uploads anything. " +
                "The camera is optional and only used if you turn on the safety watch.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Grant permissions") }
    }
}
