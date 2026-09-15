package com.stravo.vpn.ui.navigation

sealed interface StravoRoute {
    val key: String

    data object Home : StravoRoute {
        override val key = "home"
    }

    data object Servers : StravoRoute {
        override val key = "servers"
    }

    data object Import : StravoRoute {
        override val key = "import"
    }

    data object WhiteList : StravoRoute {
        override val key = "white_list"
    }

    data object Settings : StravoRoute {
        override val key = "settings"
    }

    data object Help : StravoRoute {
        override val key = "help"
    }
}

fun routeFromKey(key: String): StravoRoute = when (key) {
    StravoRoute.Servers.key -> StravoRoute.Servers
    StravoRoute.Import.key -> StravoRoute.Import
    StravoRoute.WhiteList.key -> StravoRoute.WhiteList
    StravoRoute.Settings.key -> StravoRoute.Settings
    StravoRoute.Help.key -> StravoRoute.Help
    else -> StravoRoute.Home
}
