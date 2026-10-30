package ru.voiceassistant.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StickyNote2
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.voiceassistant.ui.expenses.ExpensesScreen
import ru.voiceassistant.ui.history.HistoryScreen
import ru.voiceassistant.ui.notes.NotesScreen
import ru.voiceassistant.ui.settings.SettingsScreen
import ru.voiceassistant.ui.tasks.TasksScreen
import ru.voiceassistant.ui.today.TodayScreen

private enum class Tab(val route: String, val title: String, val icon: ImageVector) {
    TODAY("today", "Сегодня", Icons.Outlined.Home),
    TASKS("tasks", "Задачи", Icons.Outlined.Checklist),
    NOTES("notes", "Заметки", Icons.Outlined.StickyNote2),
    EXPENSES("expenses", "Расходы", Icons.Outlined.Payments),
    HISTORY("history", "История", Icons.Outlined.History),
}

private const val SETTINGS = "settings"
private const val ARG_CREATE = "create"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val tab = Tab.entries.firstOrNull { it.route == route }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(if (route == SETTINGS) "Настройки" else tab?.title ?: "Ассистент") },
                navigationIcon = {
                    if (route == SETTINGS) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                        }
                    }
                },
                actions = {
                    if (route != SETTINGS) {
                        IconButton(onClick = { nav.navigate(SETTINGS) { launchSingleTop = true } }) {
                            Icon(Icons.Outlined.Settings, "Настройки")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (route != SETTINGS) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = t == tab,
                            onClick = { nav.navigateTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.title, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Tab.TODAY.route) {
                composable(Tab.TODAY.route) {
                    TodayScreen(
                        onNewTask = { nav.navigateTab("${Tab.TASKS.route}?$ARG_CREATE=true") },
                        onNewNote = { nav.navigateTab("${Tab.NOTES.route}?$ARG_CREATE=true") },
                        onNewExpense = { nav.navigateTab("${Tab.EXPENSES.route}?$ARG_CREATE=true") },
                        onOpenTasks = { nav.navigateTab(Tab.TASKS.route) },
                        onOpenNotes = { nav.navigateTab(Tab.NOTES.route) },
                        onOpenSettings = { nav.navigate(SETTINGS) },
                    )
                }
                composable("${Tab.TASKS.route}?$ARG_CREATE={$ARG_CREATE}", arguments = listOf(createArg())) {
                    TasksScreen(openCreate = it.arguments?.getBoolean(ARG_CREATE) == true)
                }
                composable("${Tab.NOTES.route}?$ARG_CREATE={$ARG_CREATE}", arguments = listOf(createArg())) {
                    NotesScreen(openCreate = it.arguments?.getBoolean(ARG_CREATE) == true)
                }
                composable("${Tab.EXPENSES.route}?$ARG_CREATE={$ARG_CREATE}", arguments = listOf(createArg())) {
                    ExpensesScreen(openCreate = it.arguments?.getBoolean(ARG_CREATE) == true)
                }
                composable(Tab.HISTORY.route) { HistoryScreen() }
                composable(SETTINGS) { SettingsScreen() }
            }
        }
    }
}

private fun createArg() = navArgument(ARG_CREATE) {
    type = NavType.BoolType
    defaultValue = false
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = !route.contains("$ARG_CREATE=true") }
        launchSingleTop = true
        restoreState = !route.contains("$ARG_CREATE=true")
    }
}
