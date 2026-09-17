package com.stravo.vpn.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.VpnLocation
import com.stravo.vpn.ui.components.StravoScreenHeader
import com.stravo.vpn.ui.components.StravoSearchField
import com.stravo.vpn.ui.state.HomeEvent
import com.stravo.vpn.ui.state.HomeUiState
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType
import com.stravo.vpn.ui.components.PencilDivider
import androidx.compose.foundation.layout.height

/** Выбор локации: фильтр, поиск и список стран из подписки. */
@Composable
fun LocationsScreen(
    state: HomeUiState,
    onEvent: (HomeEvent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStravoPalette.current
    var query by rememberSaveable { mutableStateOf("") }
    var recommendedOnly by rememberSaveable { mutableStateOf(true) }

    val locations = state.locations
    val visible = remember(query, recommendedOnly, locations, state.location.id) {
        locations.filter { location ->
            val matchesQuery = query.isBlank() ||
                location.country.contains(query, ignoreCase = true) ||
                location.city.contains(query, ignoreCase = true)
            val matchesFilter = !recommendedOnly || location.recommended || location.id == state.location.id
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = StravoTokens.ScreenPaddingMobile)) {
        StravoScreenHeader(
            title = stringResource(id = R.string.locations_title),
            onBack = onBack,
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = StravoTokens.SpaceLg),
            horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm),
        ) {
            FilterChip(
                text = stringResource(id = R.string.locations_filter_recommended),
                selected = recommendedOnly,
                onClick = { recommendedOnly = true },
            )
            FilterChip(
                text = stringResource(id = R.string.locations_filter_all),
                selected = !recommendedOnly,
                onClick = { recommendedOnly = false },
            )
        }

        StravoSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = stringResource(id = R.string.locations_search_hint),
            modifier = Modifier.padding(top = StravoTokens.SpaceMd),
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = StravoTokens.SpaceSm),
        ) {
            items(items = visible, key = { it.id }) { location ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    LocationRow(
                        location = location,
                        selected = location.id == state.location.id,
                        onClick = { onEvent(HomeEvent.LocationSelected(location.id)) },
                    )
                    PencilDivider(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .padding(horizontal = StravoTokens.SpaceSm),
                    )
                }
            }
        }

        Text(
            text = stringResource(id = R.string.locations_footer),
            style = StravoType.Caption,
            color = palette.textSecondary,
            modifier = Modifier.padding(vertical = StravoTokens.SpaceMd),
        )
    }
}

@Composable
private fun LocationRow(
    location: VpnLocation,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    val shape = RoundedCornerShape(StravoTokens.CardRadiusMobile)
    Row(
    modifier = Modifier
        .fillMaxWidth()
        .clip(shape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .padding(vertical = StravoTokens.SpaceSm, horizontal = StravoTokens.SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StravoTokens.SpaceMd),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(palette.panelSoft)
                .border(1.dp, palette.outline.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = location.flag, style = StravoType.BodyStrong)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = location.country, style = StravoType.BodyStrong, color = palette.textPrimary)
            Text(text = location.city, style = StravoType.Caption, color = palette.textSecondary)
        }
        if (selected) {
            Text(
                text = stringResource(id = R.string.locations_selected),
                style = StravoType.Tiny,
                color = palette.accent,
                modifier = Modifier.padding(end = StravoTokens.SpaceLg),
            )
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalStravoPalette.current
    val shape = CircleShape
    Column(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) palette.medallion else palette.panel)
            .border(1.dp, palette.outline.copy(alpha = 0.25f), shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = StravoTokens.SpaceLg, vertical = StravoTokens.SpaceSm),
    ) {
        Text(
            text = text,
            style = StravoType.Caption,
            color = if (selected) palette.onAccent else palette.textPrimary,
        )
    }
}

