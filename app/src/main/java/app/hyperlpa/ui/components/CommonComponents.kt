package app.hyperlpa.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
) {
    SmallTitle(
        text = text,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 6.dp),
        cornerRadius = 16.dp,
        insideMargin = PaddingValues(0.dp),
        pressFeedbackType = if (onClick != null || onLongPress != null) {
            PressFeedbackType.Sink
        } else {
            PressFeedbackType.None
        },
        onClick = onClick,
        onLongPress = onLongPress,
        content = content,
    )
}

enum class PageStateKind {
    LOADING,
    EMPTY,
    ERROR,
    CONTENT,
}

@Composable
fun PageStateHost(
    state: PageStateKind,
    modifier: Modifier = Modifier,
    loadingMessage: String? = null,
    emptyTitle: String? = null,
    emptyMessage: String? = null,
    errorTitle: String? = null,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val resolvedLoadingMessage = loadingMessage ?: stringResource(app.hyperlpa.R.string.common_loading)
    val resolvedEmptyTitle = emptyTitle ?: stringResource(app.hyperlpa.R.string.common_nothing_here)
    val resolvedEmptyMessage = emptyMessage ?: stringResource(app.hyperlpa.R.string.common_content_available_later)
    val resolvedErrorTitle = errorTitle ?: stringResource(app.hyperlpa.R.string.common_something_went_wrong)
    val resolvedErrorMessage = errorMessage ?: stringResource(app.hyperlpa.R.string.common_try_again_later)
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "page-state",
    ) { target ->
        when (target) {
            PageStateKind.LOADING -> LoadingState(message = resolvedLoadingMessage)
            PageStateKind.EMPTY -> EmptyState(title = resolvedEmptyTitle, message = resolvedEmptyMessage)
            PageStateKind.ERROR -> ErrorState(
                title = resolvedErrorTitle,
                message = resolvedErrorMessage,
                onRetry = onRetry,
            )
            PageStateKind.CONTENT -> content()
        }
    }
}

@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(size = 38.dp)
        Spacer(Modifier.height(18.dp))
        Text(text = message, style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Info,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MessageState(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    MessageState(
        title = title,
        message = message,
        modifier = modifier,
        icon = MiuixIcons.Refresh,
        actionLabel = if (onRetry == null) null else stringResource(app.hyperlpa.R.string.common_try_again),
        onAction = onRetry,
    )
}

@Composable
private fun MessageState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val stateDescription = stringResource(
        app.hyperlpa.R.string.common_state_description,
        title,
        message,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 48.dp)
            .semantics(mergeDescendants = true) { contentDescription = stateDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = message,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            TextButton(
                text = actionLabel,
                onClick = onAction,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
