package com.stravo.vpn.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.LocationsCatalog
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** TV: список локаций с полным проходом по D-pad. */
@Composable
fun TvLocationsScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    Column(modifier = modifier.fillMaxSize().padding(start = StravoTokens.Space2Xl)) {
        Text(
            text = stringResource(id = R.string.locations_title),
            style = StravoType.ScreenTitle,
            color = palette.textPrimary,
            modifier = Modifier.padding(bottom = StravoTokens.SpaceLg),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm)) {
            items(items = LocationsCatalog.all, key = { it.id }) { location ->
                var focused by remember { mutableStateOf(false) }
                StravoCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused },
                    focused = focused,
                    radius = StravoTokens.CardRadiusTv,
                    onClick = { onEvent(HomeEvent.LocationSelected(location.id)) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = location.flag, style = StravoType.BodyStrong, color = palette.textPrimary)
                        Column(modifier = Modifier.padding(start = StravoTokens.SpaceMd)) {
                            Text(text = location.country, style = StravoType.BodyStrong, color = palette.textPrimary)
                            Text(text = location.city, style = StravoType.Caption, color = palette.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
