package com.stravo.vpn.ui.components

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.StravoColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A temporary charred edge; zero cancels it and each new trigger burns once. */
@Composable
internal fun PowerSmolderRim(trigger: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress = remember { Animatable(1f) }
    // Stable roughness, so the paper edge does not jump between animation frames.
    val rim = remember {
        List(181) { index ->
            val step = index % 180
            val angle = step * (2.0 * PI / 180.0)
            val radius = 0.455f + sin(step * 1.37f) * 0.003f + cos(step * 0.43f) * 0.002f
            Offset(cos(angle).toFloat() * radius, sin(angle).toFloat() * radius)
        }
    }
    LaunchedEffect(trigger) {
        progress.snapTo(1f)
        // Read at the gesture; Compose also observes duration-scale changes mid-animation.
        if (trigger == 0 || Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        ) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 2600, easing = LinearEasing))
    }

    Canvas(modifier.clipToBounds()) {
        val phase = progress.value
        if (trigger == 0 || phase <= 0f || phase >= 1f) return@Canvas
        val diameter = size.minDimension
        val fade = ((1f - phase) / 0.46f).coerceIn(0f, 1f)
        val amber = Color(0xFFC38B46)
        for (index in 0 until rim.lastIndex) {
            val delay = (index * 37 % 101) / 101f * 0.18f
            val heat = ((phase - delay) / 0.12f).coerceIn(0f, 1f) * fade
            if (heat <= 0f) continue
            val start = center + rim[index] * diameter
            val end = center + rim[index + 1] * diameter
            val grain = (sin(index * 0.73f) + 1f) * 0.5f
            drawLine(
                StravoColors.Graphite.copy(alpha = heat * 0.62f), start, end,
                strokeWidth = diameter * (0.006f + grain * 0.008f), cap = StrokeCap.Round,
            )
            drawLine(
                Color(0xFF76543B).copy(alpha = heat * 0.35f), start, end,
                strokeWidth = 0.7.dp.toPx(), cap = StrokeCap.Round,
            )
            // Eight small embers sit on the edge; nothing drifts across the screen.
            if (index % 23 == 7) {
                val ember = heat * (0.72f + 0.28f * sin(phase * 17f + index))
                drawCircle(amber.copy(alpha = ember * 0.12f), 3.dp.toPx(), start)
                drawCircle(amber.copy(alpha = ember * 0.75f), 1.25.dp.toPx(), start)
                drawCircle(Color(0xFFEAC184).copy(alpha = ember), 0.55.dp.toPx(), start)
            }
        }
    }
}
