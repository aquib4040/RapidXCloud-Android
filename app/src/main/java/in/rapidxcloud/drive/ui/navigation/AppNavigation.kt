package `in`.rapidxcloud.drive.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import `in`.rapidxcloud.drive.ui.drive.DriveScreen
import `in`.rapidxcloud.drive.ui.leech.UrlLeechDialog
import `in`.rapidxcloud.drive.ui.pics.PicsScreen
import `in`.rapidxcloud.drive.ui.settings.SettingsScreen
import `in`.rapidxcloud.drive.ui.shared.SharedWithMeScreen
import `in`.rapidxcloud.drive.ui.starred.StarredScreen
import `in`.rapidxcloud.drive.ui.theme.*
import `in`.rapidxcloud.drive.ui.trash.TrashScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Vault : Screen("vault", "Vault", Icons.Default.Cloud)
    object Shared : Screen("shared", "Shared", Icons.Default.People)
    object Photos : Screen("photos", "Photos", Icons.Default.PhotoLibrary)
    object Starred : Screen("starred", "Starred", Icons.Default.Star)
    object Trash : Screen("trash", "Trash", Icons.Default.Delete)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun AppNavigation(
    onLogout: () -> Unit
) {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Vault) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var showUrlLeechDialog by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = TextSecondary
            ) {
                val screens = listOf(
                    Screen.Vault,
                    Screen.Shared,
                    Screen.Photos,
                    Screen.Starred,
                    Screen.Trash,
                    Screen.Settings
                )
                screens.forEach { screen ->
                    val isSelected = currentScreen.route == screen.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentScreen = screen },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title, fontSize = 10.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CyanAccent,
                            selectedTextColor = CyanAccent,
                            indicatorColor = DarkCard,
                            unselectedIconColor = TextMuted,
                            unselectedTextColor = TextMuted
                        )
                    )
                }
            }
        },
        containerColor = DarkBg
    ) { padding ->
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(padding)) {
            when (currentScreen) {
                Screen.Vault -> DriveScreen(
                    currentFolderId = currentFolderId,
                    onNavigateFolder = { folderId, _ -> currentFolderId = folderId },
                    onOpenUrlLeech = { showUrlLeechDialog = true }
                )
                Screen.Shared -> SharedWithMeScreen(
                    onNavigateFolder = { folderId, _ ->
                        currentFolderId = folderId
                        currentScreen = Screen.Vault
                    }
                )
                Screen.Photos -> PicsScreen()
                Screen.Starred -> StarredScreen()
                Screen.Trash -> TrashScreen()
                Screen.Settings -> SettingsScreen(onLogout = onLogout)
            }
        }
    }

    if (showUrlLeechDialog) {
        UrlLeechDialog(
            folderId = currentFolderId,
            onDismiss = { showUrlLeechDialog = false }
        )
    }
}
