package com.stravo.vpn.ui.screens.importing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stravo.vpn.domain.importing.ImportResult
import com.stravo.vpn.domain.importing.ProfileImportSource
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.components.PaperCanvas
import com.stravo.vpn.ui.components.StravoCard
import com.stravo.vpn.ui.theme.StravoColors
import com.stravo.vpn.ui.theme.StravoTypography

@Composable
fun ImportScreen(
    formFactor: FormFactor,
    viewModel: ImportViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    PaperCanvas(modifier = modifier) {
        StravoCard(modifier = Modifier.padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "Добавить маршрут",
                    style = StravoTypography.ScreenTitle,
                    color = StravoColors.Graphite,
                )
                Text(
                    text = if (formFactor == FormFactor.Tv) {
                        "Введите ссылку с помощью пульта"
                    } else {
                        "Вставьте URL, ссылку или текст QR-кода"
                    },
                    style = StravoTypography.Body,
                    color = StravoColors.GraphiteMuted,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.input,
                    onValueChange = viewModel::setInput,
                    label = { Text("Ссылка или конфигурация") },
                    minLines = 3,
                    enabled = !state.isBusy,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !state.isBusy,
                        onClick = {
                            viewModel.importProfile(ProfileImportSource.Url(state.input), scope)
                        },
                    ) { Text("ИМПОРТИРОВАТЬ") }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = !state.isBusy,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip: ClipData? = clipboard.primaryClip
                            val value = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            viewModel.setInput(value)
                            viewModel.importProfile(ProfileImportSource.Clipboard(value), scope)
                        },
                    ) { Text("БУФЕР") }
                }
                if (state.result is ImportResult.Success) {
                    val summary = (state.result as ImportResult.Success).summary
                    Text(
                        text = "Добавлено: ${summary.displayName}",
                        color = StravoColors.Mint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else if (state.result is ImportResult.Failure) {
                    Text(
                        text = (state.result as ImportResult.Failure).error.userMessage,
                        color = StravoColors.Coral,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
