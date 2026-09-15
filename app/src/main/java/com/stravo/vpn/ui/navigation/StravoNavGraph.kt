package com.stravo.vpn.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.stravo.vpn.StravoApplication
import com.stravo.vpn.domain.importing.DefaultProfileImporter
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.screens.help.HelpScreen
import com.stravo.vpn.ui.screens.home.HomeScreen
import com.stravo.vpn.ui.screens.importing.ImportScreen
import com.stravo.vpn.ui.screens.importing.ImportViewModel
import com.stravo.vpn.ui.screens.servers.ServersScreen
import com.stravo.vpn.ui.screens.settings.SettingsScreen
import com.stravo.vpn.ui.screens.whitelist.WhiteListScreen

@Composable
fun StravoNavGraph(formFactor: FormFactor) {
    var routeKey by remember { mutableStateOf(StravoRoute.Home.key) }
    val application = LocalContext.current.applicationContext as StravoApplication
    val container = remember { application.container }
    val importer = remember(formFactor) {
        DefaultProfileImporter(
            formFactor = formFactor,
            profileRepository = container.profileRepository,
        )
    }
    val importViewModel = remember(formFactor) { ImportViewModel(importer) }
    val requestedRoute = routeFromKey(routeKey)
    val currentRoute = if (formFactor == FormFactor.Tv && requestedRoute == StravoRoute.WhiteList) {
        StravoRoute.Home
    } else {
        requestedRoute
    }

    fun navigate(route: StravoRoute) {
        if (formFactor == FormFactor.Tv && route == StravoRoute.WhiteList) {
            routeKey = StravoRoute.Home.key
        } else {
            routeKey = route.key
        }
    }

    val content: @Composable () -> Unit = {
        when (currentRoute) {
            StravoRoute.Home -> HomeScreen(
                formFactor = formFactor,
                profileRepository = container.profileRepository,
                settingsRepository = container.settingsRepository,
                onNavigate = ::navigate,
            )
            StravoRoute.Servers -> ServersScreen(
                formFactor = formFactor,
                profileRepository = container.profileRepository,
                settingsRepository = container.settingsRepository,
                onNavigate = ::navigate,
            )
            StravoRoute.Import -> ImportScreen(
                formFactor = formFactor,
                viewModel = importViewModel,
            )
            StravoRoute.WhiteList -> WhiteListScreen(
                formFactor = formFactor,
                onNavigate = ::navigate,
            )
            StravoRoute.Settings -> SettingsScreen(
                formFactor = formFactor,
                settingsRepository = container.settingsRepository,
                onNavigate = ::navigate,
            )
            StravoRoute.Help -> HelpScreen(
                formFactor = formFactor,
                onNavigate = ::navigate,
            )
        }
    }

    when (formFactor) {
        FormFactor.Phone -> PhoneShell(currentRoute, ::navigate, content)
        FormFactor.Tv -> TvShell(currentRoute, ::navigate, content)
    }
}
