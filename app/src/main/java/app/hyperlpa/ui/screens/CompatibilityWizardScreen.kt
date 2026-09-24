package app.hyperlpa.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hyperlpa.R
import app.hyperlpa.domain.model.LpaOperation
import app.hyperlpa.ui.HyperLpaUiState
import app.hyperlpa.ui.components.DetailLazyScaffold
import app.hyperlpa.ui.components.LoadingState
import app.hyperlpa.ui.components.PageStart
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class CompatibilityResult(val readers: List<String>, val euiccReaders: List<String>)

@Composable
fun CompatibilityWizardScreen(
    state: HyperLpaUiState,
    firstRun: Boolean,
    onBack: () -> Unit,
    onRefreshReaders: suspend () -> Unit,
    onContinue: () -> Unit,
) {
    val lpa = state.lpa
    val availableReaders = lpa.readers.filter { it.available }
    val latest = CompatibilityResult(
        readers = availableReaders.map { it.name },
        euiccReaders = availableReaders
            .filter { it.eid != null || (it.id == lpa.selectedReaderId && lpa.euiccInfo != null) }
            .map { it.name },
    )
    val checking = !lpa.initialized || when (lpa.operation) {
        is LpaOperation.DiscoveringReaders,
        is LpaOperation.Connecting,
        is LpaOperation.Refreshing,
        -> true
        else -> false
    }
    // Readers and eUICC details arrive in steps; show the page only once a check has finished.
    var result by remember { mutableStateOf(latest.takeUnless { checking }) }
    LaunchedEffect(checking, latest) {
        if (!checking) result = latest
    }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    val shown = result

    DetailLazyScaffold(
        title = "",
        onBack = onBack,
        showBackButton = !firstRun,
        isRefreshing = refreshing,
        onRefresh = if (shown == null) null else ({
            if (!refreshing) {
                refreshing = true
                scope.launch {
                    try { onRefreshReaders() } finally { refreshing = false }
                }
            }
        }),
    ) { _ ->
        item(contentType = PageStart.Viewport) {
            if (shown == null) {
                LoadingState(stringResource(R.string.compatibility_checking), Modifier.fillParentMaxSize())
            } else {
                CompatibilityResultContent(shown, firstRun, onContinue)
            }
        }
    }
}

@Composable
private fun CompatibilityResultContent(result: CompatibilityResult, firstRun: Boolean, onContinue: () -> Unit) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val deviceInfo = listOf(
        "BRAND" to Build.BRAND,
        "DEVICE" to Build.DEVICE,
        "MODEL" to Build.MODEL,
        "VERSION.RELEASE" to Build.VERSION.RELEASE,
        "VERSION.SDK_INT" to Build.VERSION.SDK_INT.toString(),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = windowHeight * 0.72f)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(
                when {
                    result.euiccReaders.isNotEmpty() -> R.string.compatibility_result_ready
                    result.readers.isNotEmpty() -> R.string.compatibility_result_no_euicc
                    else -> R.string.compatibility_result_none
                },
            ),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(18.dp))
        deviceInfo.forEach { (key, value) ->
            Text(
                text = "$key: $value",
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 5.dp, horizontal = 16.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.compatibility_readers, readerList(result.readers)),
            style = MiuixTheme.textStyles.body1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.compatibility_euicc_access, readerList(result.euiccReaders)),
            style = MiuixTheme.textStyles.body1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(
                if (result.euiccReaders.isNotEmpty()) R.string.compatibility_note
                else R.string.compatibility_note_missing,
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(20.dp))
        TextButton(
            text = stringResource(if (firstRun) R.string.common_continue else R.string.common_back),
            onClick = onContinue,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun readerList(names: List<String>): String {
    val separator = stringResource(R.string.compatibility_list_separator)
    return when (names.size) {
        0 -> stringResource(R.string.common_none)
        1 -> names.single()
        else -> stringResource(R.string.compatibility_list_pair, names.dropLast(1).joinToString(separator), names.last())
    }
}
