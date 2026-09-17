package com.stravo.vpn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.stravo.vpn.domain.model.FormFactor

@Composable
fun StravoTheme(
    formFactor: FormFactor,
    content: @Composable () -> Unit,
) {
    // На TV всегда графитовая панель, на телефоне — бумага, независимо от системной темы.
    val palette = when (formFactor) {
        FormFactor.TV -> StravoPalette.Dark
        FormFactor.PHONE -> if (isSystemInDarkTheme()) StravoPalette.Dark else StravoPalette.Light
    }
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            outline = palette.outlineSoft,
            error = StravoColors.Danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            onBackground = palette.textPrimary,
            surface = palette.panel,
            onSurface = palette.textPrimary,
            outline = palette.outlineSoft,
            error = StravoColors.Danger,
        )
    }
    CompositionLocalProvider(LocalStravoPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
