package com.stravo.vpn.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

private val StravoMaterialColors = lightColors(
    primary = StravoColors.Mint,
    primaryVariant = StravoColors.Graphite,
    secondary = StravoColors.Coral,
    background = StravoColors.Paper,
    surface = StravoColors.PaperLight,
    onPrimary = StravoColors.PaperLight,
    onSecondary = StravoColors.PaperLight,
    onBackground = StravoColors.Graphite,
    onSurface = StravoColors.Graphite,
)

private val StravoMaterialTypography = Typography(
    h4 = StravoTypography.ScreenTitle,
    h5 = StravoTypography.SectionTitle,
    body1 = StravoTypography.Body,
    body2 = StravoTypography.Caption,
    button = StravoTypography.Eyebrow,
)

private val StravoMaterialShapes = Shapes(
    small = StravoShapes.Control,
    medium = StravoShapes.Card,
    large = StravoShapes.TvCard,
)

@Composable
fun StravoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = StravoMaterialColors,
        typography = StravoMaterialTypography,
        shapes = StravoMaterialShapes,
        content = content,
    )
}
