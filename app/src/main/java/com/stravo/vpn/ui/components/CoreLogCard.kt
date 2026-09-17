package com.stravo.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/**
 * Журнал ядра одной карточкой: шаги запуска и последние сообщения sing-box.
 *
 * Секретов нет — адреса, идентификаторы и ключи вырезаны в [com.stravo.vpn.data.diagnostics.CoreTrace].
 * Нужен, чтобы объяснить словами, почему туннель поднялся, а трафик не пошёл.
 */
@Composable
fun CoreLogCard(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) return
    val palette = LocalStravoPalette.current
    StravoCard(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = StravoType.BodyStrong,
            color = palette.textPrimary,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceSm)
                .heightIn(max = 220.dp)
                .background(palette.panelSoft)
                .border(1.dp, palette.outline.copy(alpha = 0.28f))
                .verticalScroll(rememberScrollState())
                .padding(StravoTokens.SpaceSm),
        ) {
            Text(
                text = lines.joinToString("\n"),
                style = StravoType.Tiny.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                color = palette.textSecondary,
            )
        }
        // Последние строки — отдельными строками: их видно целиком, даже если
        // общий блок прокручивается.
        if (lines.size > 1) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = StravoTokens.SpaceSm)) {
                lines.takeLast(3).forEach { line ->
                    Text(
                        text = line,
                        style = StravoType.Tiny.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}
