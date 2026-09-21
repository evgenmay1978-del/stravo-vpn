package com.stravo.vpn.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.stravo.vpn.ui.components.PencilIcon as Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.VpnStats
import com.stravo.vpn.domain.model.asPing
import com.stravo.vpn.domain.model.asSpeed
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Пинг / Загрузка / Отдача. До измерения — длинное тире, без выдуманных чисел. */
@Composable
fun StravoStatRow(
    stats: VpnStats,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatCell(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_ping,
            label = stringResource(id = R.string.stats_ping),
            value = stats.pingMs.asPing(),
            unit = stringResource(id = R.string.unit_ms),
        )
        PencilDivider(modifier = Modifier.width(1.dp).height(38.dp))
        StatCell(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_download,
            label = stringResource(id = R.string.stats_download),
            value = stats.downloadMbps.asSpeed(),
            unit = stringResource(id = R.string.unit_mbps),
        )
        PencilDivider(modifier = Modifier.width(1.dp).height(38.dp))
        StatCell(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_upload,
            label = stringResource(id = R.string.stats_upload),
            value = stats.uploadMbps.asSpeed(),
            unit = stringResource(id = R.string.unit_mbps),
        )
    }
}

@Composable
private fun StatCell(
    iconRes: Int,
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceXs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = iconRes),
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = StravoType.Caption,
                color = palette.textSecondary,
                modifier = Modifier.padding(start = StravoTokens.SpaceXs),
            )
        }
        Text(
            text = value,
            style = StravoType.BodyStrong.copy(fontSize = 24.sp, lineHeight = 28.sp),
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = unit,
            style = StravoType.Tiny,
            color = palette.textSecondary,
        )
    }
}
