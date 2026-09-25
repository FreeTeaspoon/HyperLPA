package app.hyperlpa.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hyperlpa.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes
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

@Composable
fun ValuePreference(title: String, value: String) {
    BasicComponent(
        title = title,
        summary = value,
        titleColor = BasicComponentDefaults.titleColor(
            disabledColor = MiuixTheme.colorScheme.onBackground,
        ),
        summaryColor = BasicComponentDefaults.summaryColor(
            disabledColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        ),
        enabled = false,
    )
}

@Composable
fun PrimaryPageButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        colors = ButtonDefaults.buttonColorsPrimary(),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        if (busy) {
            InfiniteProgressIndicator(color = MiuixTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, style = MiuixTheme.textStyles.button)
    }
}

@Composable
fun TipCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    TipCard(modifier = modifier) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
fun TipCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 12.dp, bottom = 6.dp),
        insideMargin = PaddingValues(0.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
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
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
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
            PageStateKind.LOADING -> LoadingState(
                message = resolvedLoadingMessage,
                modifier = Modifier.fillMaxSize(),
            )
            PageStateKind.EMPTY -> EmptyState(
                title = resolvedEmptyTitle,
                message = resolvedEmptyMessage,
                modifier = Modifier.fillMaxSize(),
                actionLabel = emptyActionLabel,
                onAction = onEmptyAction,
            )
            PageStateKind.ERROR -> ErrorState(
                title = resolvedErrorTitle,
                message = resolvedErrorMessage,
                modifier = Modifier.fillMaxSize(),
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
        InfiniteProgressIndicator(size = 20.dp)
        Spacer(Modifier.height(10.dp))
        Text(text = message, style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Center)
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Notes,
    showMessage: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val stateDescription = if (message.isBlank()) title else stringResource(
        app.hyperlpa.R.string.common_state_description,
        title,
        message,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .semantics(mergeDescendants = true) { contentDescription = stateDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.offset(y = (-36).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(muted.copy(alpha = if (LocalDarkTheme.current) 0.28f else 0.23f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                    tint = MiuixTheme.colorScheme.surface,
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                color = muted,
                textAlign = TextAlign.Center,
            )
            if (showMessage && message.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 13.sp,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                TextButton(
                    text = actionLabel,
                    onClick = onAction,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        icon = MiuixIcons.Refresh,
        showMessage = true,
        actionLabel = if (onRetry == null) null else stringResource(app.hyperlpa.R.string.common_try_again),
        onAction = onRetry,
    )
}
