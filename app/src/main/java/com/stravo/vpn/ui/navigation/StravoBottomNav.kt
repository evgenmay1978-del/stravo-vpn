package com.stravo.vpn.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Нижняя навигация телефона: Главная, Локации, Профиль, Настройки. */
@Composable
fun StravoBottomNav(
    current: Destination,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.panel)
            .padding(vertical = StravoTokens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.bottomBar.forEach { destination ->
            val selected = destination == current
            val tint = if (selected) palette.accent else palette.textSecondary
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(StravoTokens.TouchTargetMin + 8.dp)
                    .clickable(role = Role.Tab) { onSelect(destination) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(modifier = Modifier.size(24.dp)) {
                    Icon(
                        painter = painterResource(id = destination.iconRes),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Text(
                    text = stringResource(id = destination.labelRes),
                    style = StravoType.Tiny,
                    color = tint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
