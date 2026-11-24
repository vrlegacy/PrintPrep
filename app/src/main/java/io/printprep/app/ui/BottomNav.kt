package io.printprep.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import io.printprep.app.JobViewModel
import io.printprep.app.R
import io.printprep.app.Screen

/** One bottom-navigation entry: either a Material icon or a drawable (for the printer glyph). */
private data class NavItem(val screen: Screen, val label: String, val icon: ImageVector? = null, val drawable: Int? = null)

private val NAV_ITEMS = listOf(
    NavItem(Screen.Home, "Home", icon = Icons.Filled.Home),
    NavItem(Screen.Editor, "Jobs", icon = Icons.AutoMirrored.Filled.List),
    NavItem(Screen.Printer, "Printer", drawable = R.drawable.ic_print),
    NavItem(Screen.Settings, "Settings", icon = Icons.Filled.Settings),
)

/**
 * The app's bottom navigation bar, shown on the four top-level destinations (Home, Jobs, Printer,
 * Settings). Added to each of those screens' own Scaffold as its bottomBar so each screen keeps its
 * own content insets; sub-pages (web browser, item editor, diagnostics) simply don't include it.
 */
@Composable
fun AppBottomNav(vm: JobViewModel) {
    NavigationBar {
        NAV_ITEMS.forEach { item ->
            NavigationBarItem(
                selected = vm.screen == item.screen,
                onClick = { vm.selectTab(item.screen) },
                icon = {
                    when {
                        item.icon != null -> Icon(item.icon, contentDescription = item.label)
                        else -> Icon(painterResource(item.drawable!!), contentDescription = item.label)
                    }
                },
                label = { Text(item.label) },
            )
        }
    }
}
