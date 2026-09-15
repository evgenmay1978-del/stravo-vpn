package com.stravo.vpn.ui.screens.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.ui.components.CompassMark
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoShapes
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun StravoScreen(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = StravoTypography.ScreenTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = subtitle,
                    style = StravoTypography.Body,
                    color = StravoColors.GraphiteMuted,
                )
            }
            CompassMark(modifier = Modifier.padding(start = 12.dp), color = StravoColors.Mint)
        }
        Spacer(modifier = Modifier.height(2.dp))
        content()
    }
}

@Composable
fun StravoNotice(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    accent: Color = StravoColors.Mint,
) {
    StravoCard(modifier = modifier, borderColor = accent.copy(alpha = 0.55f)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = title,
                style = StravoTypography.SectionTitle,
                color = StravoColors.Graphite,
            )
            Text(
                text = message,
                style = StravoTypography.Body,
                color = StravoColors.GraphiteMuted,
            )
        }
    }
}

@Composable
fun StravoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = StravoColors.Graphite,
            contentColor = StravoColors.PaperLight,
            disabledBackgroundColor = StravoColors.Graphite.copy(alpha = 0.22f),
            disabledContentColor = StravoColors.GraphiteMuted,
        ),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
fun StravoSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, StravoColors.Graphite.copy(alpha = 0.45f)),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = StravoColors.Graphite,
        ),
    ) {
        Text(text = text, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
    }
}

@Composable
fun StravoToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    StravoCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = StravoTypography.SectionTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = subtitle,
                    style = StravoTypography.Caption,
                    color = StravoColors.GraphiteMuted,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = StravoColors.PaperLight,
                    checkedTrackColor = StravoColors.Mint,
                    uncheckedThumbColor = StravoColors.GraphiteMuted,
                    uncheckedTrackColor = StravoColors.PaperShade,
                ),
            )
        }
    }
}

@Composable
fun StravoBullet(
    title: String,
    text: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            color = StravoColors.Mint,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = StravoColors.Graphite,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = text,
                style = StravoTypography.Caption,
                color = StravoColors.GraphiteMuted,
            )
        }
    }
}

@Composable
fun StravoEmptyTitle(text: String) {
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = text,
        style = StravoTypography.SectionTitle,
        color = StravoColors.Graphite,
        textAlign = TextAlign.Center,
    )
}
