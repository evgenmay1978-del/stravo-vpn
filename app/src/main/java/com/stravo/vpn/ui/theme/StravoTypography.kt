package com.stravo.vpn.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object StravoTypography {
    private val Sans = FontFamily.SansSerif

    val ScreenTitle = TextStyle(
        fontFamily = Sans,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    )

    val SectionTitle = TextStyle(
        fontFamily = Sans,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 24.sp,
    )

    val Body = TextStyle(
        fontFamily = Sans,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    val Caption = TextStyle(
        fontFamily = Sans,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    val Eyebrow = TextStyle(
        fontFamily = Sans,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
    )
}
