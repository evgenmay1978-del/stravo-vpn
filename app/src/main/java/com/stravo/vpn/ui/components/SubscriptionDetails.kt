package com.stravo.vpn.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.stravo.vpn.R
import com.stravo.vpn.domain.model.Subscription
import com.stravo.vpn.ui.theme.LocalStravoPalette
import com.stravo.vpn.ui.theme.StravoTokens
import com.stravo.vpn.ui.theme.StravoType

/** Provider text is inert text, not HTML, links, or instructions to the application. */
@Composable
fun SubscriptionDetails(subscription: Subscription) {
    if (!subscription.isActive || (subscription.description == null && subscription.announcement == null &&
        subscription.usedBytes == null && subscription.totalBytes == null)) return
    val palette = LocalStravoPalette.current
    val context = LocalContext.current
    StravoCard(Modifier.fillMaxWidth().padding(top = StravoTokens.SpaceMd)) {
        Column(verticalArrangement = Arrangement.spacedBy(StravoTokens.SpaceSm)) {
            subscription.description?.let {
                Text(it, style = StravoType.Caption, color = palette.textSecondary)
            }
            subscription.announcement?.let {
                Text(it, style = StravoType.BodyStrong, color = palette.textPrimary)
            }
            if (subscription.usedBytes != null || subscription.totalBytes != null) {
                val used = subscription.usedBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
                val total = subscription.totalBytes?.let { Formatter.formatShortFileSize(context, it) }
                    ?: stringResource(R.string.subscription_unlimited)
                Text(stringResource(R.string.subscription_traffic, used, total),
                    style = StravoType.Caption, color = palette.textSecondary)
            }
        }
    }
}
