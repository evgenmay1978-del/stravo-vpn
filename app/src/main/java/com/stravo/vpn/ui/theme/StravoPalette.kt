package com.stravo.vpn.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цветовая схема конкретного форм-фактора: телефон — бумага, TV — графитовая панель. */
data class StravoPalette(
    val background: Color,
    val panel: Color,
    val panelSoft: Color,
    val outline: Color,
    val outlineSoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentDeep: Color,
    val onAccent: Color,
    val medallion: Color,
    val medallionDeep: Color,
    val isDark: Boolean,
) {
    companion object {
        val Light = StravoPalette(
            background = StravoColors.Paper,
            panel = StravoColors.PaperLight,
            panelSoft = StravoColors.PaperDeep,
            outline = StravoColors.Graphite,
            outlineSoft = StravoColors.GraphiteSoft,
            textPrimary = StravoColors.Graphite,
            textSecondary = StravoColors.GraphiteSecondary,
            accent = StravoColors.Emerald,
            accentDeep = StravoColors.EmeraldDeep,
            onAccent = StravoColors.Paper,
            medallion = StravoColors.Graphite,
            medallionDeep = Color(0xFF0E1618),
            isDark = false,
        )

        val Dark = StravoPalette(
            background = StravoColors.TvBackground,
            panel = StravoColors.TvPanel,
            panelSoft = StravoColors.TvPanelSoft,
            outline = StravoColors.TvInk,
            outlineSoft = Color(0xFF5A6A6C),
            textPrimary = StravoColors.TvInk,
            textSecondary = StravoColors.TvInkSoft,
            accent = StravoColors.Emerald,
            accentDeep = StravoColors.EmeraldDeep,
            onAccent = Color(0xFF0E1618),
            medallion = Color(0xFF16211F),
            medallionDeep = Color(0xFF070D0D),
            isDark = true,
        )
    }
}

val LocalStravoPalette = staticCompositionLocalOf { StravoPalette.Light }
