package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hyperlpa.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DialogActionRow(
    onCancel: () -> Unit,
    cancelText: String? = null,
    confirmText: String,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
) {
    val resolvedCancelText = cancelText ?: stringResource(R.string.common_cancel)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(text = resolvedCancelText, onClick = onCancel, modifier = Modifier.weight(1f))
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = if (destructive) {
                ButtonDefaults.textButtonColors(
                    textColor = MiuixTheme.colorScheme.error,
                )
            } else {
                ButtonDefaults.textButtonColorsPrimary()
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun TextInputDialog(
    show: Boolean,
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    label: String = title,
    confirmText: String = stringResource(R.string.common_save),
    maxLength: Int = 256,
    allowBlank: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    inputFilter: (String) -> String = { it },
    validate: (String) -> String? = { null },
) {
    OverlayDialog(
        show = show,
        modifier = modifier,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        var value by remember { mutableStateOf(TextFieldValue(initialValue, TextRange(initialValue.length))) }
        val focusRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        val error = value.text.takeIf { it.isNotBlank() }?.let(validate)
        val canConfirm = error == null && (allowBlank || value.text.isNotBlank())
        TextField(
            value = value,
            onValueChange = { input ->
                val filtered = inputFilter(input.text)
                if (filtered.length <= maxLength) {
                    value = if (filtered == input.text) input else TextFieldValue(filtered, TextRange(filtered.length))
                }
            },
            label = label,
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(value.text) }),
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
        if (error != null) {
            Text(
                text = error,
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote1,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        DialogActionRow(
            onCancel = onDismiss,
            confirmText = confirmText,
            confirmEnabled = canConfirm,
            onConfirm = { onConfirm(value.text) },
        )
    }
}
