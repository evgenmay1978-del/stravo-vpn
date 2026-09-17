package com.stravo.vpn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.stravo.vpn.domain.model.FormFactor
import com.stravo.vpn.ui.mobile.MobileRoot
import com.stravo.vpn.ui.state.StravoViewModel
import com.stravo.vpn.ui.tv.TvRoot

/**
 * Один codebase — два composition root.
 * Mobile и TV используют одни ViewModel, репозитории и destinations,
 * но разные UI-деревья и разные темы.
 */
@Composable
fun StravoAppRoot(
    viewModel: StravoViewModel,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(formFactor) { viewModel.setFormFactor(formFactor) }
    when (formFactor) {
        FormFactor.TV -> TvRoot(viewModel = viewModel, modifier = modifier)
        FormFactor.PHONE -> MobileRoot(viewModel = viewModel, modifier = modifier)
    }
}
