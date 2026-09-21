package com.stravo.vpn.ui.navigation

import androidx.annotation.StringRes
import com.stravo.vpn.R

/** Экраны приложения. Одни и те же destinations используются на телефоне и на TV. */
enum class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val iconRes: Int,
) {
    HOME("home", R.string.nav_home, R.drawable.ic_home),
    LOCATIONS("locations", R.string.nav_locations, R.drawable.ic_location),
    PROFILE("profile", R.string.nav_profile, R.drawable.ic_profile),
    CONNECT_TV("connect_tv", R.string.subscription_connect_tv, R.drawable.ic_tv),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_settings),
    SCANNER("scanner", R.string.scan_title, R.drawable.ic_scan),
    ADD_SUBSCRIPTION("add_subscription", R.string.add_sub_title, R.drawable.ic_crown),
    APPS("apps", R.string.settings_apps, R.drawable.ic_network),
    ;

    companion object {
        /** Порядок в нижней панели телефона. */
        val bottomBar: List<Destination> = listOf(HOME, LOCATIONS, PROFILE, SETTINGS)

        /** Порядок в боковом меню TV. */
        val tvRail: List<Destination> = listOf(HOME, LOCATIONS, PROFILE, CONNECT_TV, SETTINGS)

        fun byRoute(route: String?): Destination = entries.firstOrNull { it.route == route } ?: HOME
    }
}

