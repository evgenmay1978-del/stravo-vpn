package com.stravo.vpn

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val formFactor = if (isTelevision()) "ANDROID TV" else "ANDROID"
        setContent {
            StravoApp(formFactor = formFactor)
        }
    }

    private fun isTelevision(): Boolean {
        val type = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
        return type == Configuration.UI_MODE_TYPE_TELEVISION
    }
}

@Composable
private fun StravoApp(formFactor: String) {
    val paper = Color(0xFFF2E6CC)
    val graphite = Color(0xFF20313A)
    val mint = Color(0xFF5B9E95)
    val coral = Color(0xFFDC6A5E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "STRAVO VPN",
                    color = graphite,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    text = formFactor,
                    color = mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, graphite, RoundedCornerShape(22.dp))
                    .padding(28.dp),
            ) {
                Column {
                    Text(
                        text = "Ваш безопасный маршрут",
                        color = graphite,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Холст STRAVO готов к первому подключению.",
                        color = graphite.copy(alpha = 0.72f),
                        fontSize = 16.sp,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Профиль ещё не добавлен",
                        color = coral,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
