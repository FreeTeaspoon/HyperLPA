// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
package app.hyperlpa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.hyperlpa.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Inline form of the pinned demo's GlassSearchOverlay input, retaining live list filtering. */
@Composable
fun Os4SearchField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(value = value, onValueChange = onValueChange, singleLine = true,
        textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = { field ->
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .background(colors.surfaceContainerHigh, CircleShape).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(MiuixIcons.Glass.Search, null, Modifier.size(28.dp), tint = colors.disabledOnSurface)
                Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    if (value.isEmpty()) Text(label, color = colors.disabledOnSurface, style = MiuixTheme.textStyles.body1)
                    field()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(40.dp)) {
                    Icon(MiuixIcons.Glass.Close, stringResource(R.string.common_clear), Modifier.size(20.dp), tint = colors.disabledOnSurface)
                }
            }
        })
}
