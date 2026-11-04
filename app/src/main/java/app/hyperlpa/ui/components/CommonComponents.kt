package app.hyperlpa.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Link
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
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        contentAlignment = Alignment.Center) {
        TextButton(
            text = if (busy) "" else text,
            onClick = onClick,
            enabled = enabled && !busy,
            colors = ButtonDefaults.textButtonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (busy) InfiniteProgressIndicator(color = MiuixTheme.colorScheme.onPrimary, size = 24.dp)
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

// Loading, empty and error states share this line so switching between them does not jump.
// A bias scales the lift with the free space, which keeps short windows balanced.
val PageStateAlignment = BiasAlignment(horizontalBias = 0f, verticalBias = -0.12f)

// Miuix calls its stacked-layers glyph Backup; the one named Layers draws a list.
val ProfilesStateIcon: ImageVector
    get() = MiuixIcons.Backup

val ReaderStateIcon: ImageVector
    get() = MiuixIcons.Link

const val PageStateFadeInMillis = 150
const val PageStateFadeOutMillis = 120

fun pageStateTransition(): ContentTransform = ContentTransform(
    targetContentEnter = fadeIn(tween(PageStateFadeInMillis)),
    initialContentExit = fadeOut(tween(PageStateFadeOutMillis)),
    sizeTransform = null,
)

/**
 * Cross-fades the complete result, including content, without changing external list state.
 */
@Composable
fun PageStateHost(
    state: PageStateKind,
    modifier: Modifier = Modifier,
    content: @Composable (PageStateKind) -> Unit,
) {
    AnimatedContent(
        targetState = state,
        modifier = modifier,
        transitionSpec = { pageStateTransition() },
        contentAlignment = Alignment.Center,
        label = "page-state",
    ) { target ->
        content(target)
    }
}

@Composable
fun LoadingState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 56.dp)
            .semantics(mergeDescendants = true) { contentDescription = message },
        contentAlignment = PageStateAlignment,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            InfiniteProgressIndicator(size = 20.dp)
            Spacer(Modifier.height(10.dp))
            Text(text = message, style = MiuixTheme.textStyles.body1, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Notes,
    showMessage: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val visibleMessage = message.takeIf { showMessage && it.isNotBlank() }
    val stateDescription = if (visibleMessage == null) title else stringResource(
        app.hyperlpa.R.string.common_state_description,
        title,
        visibleMessage,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .semantics(mergeDescendants = true) { contentDescription = stateDescription },
        contentAlignment = PageStateAlignment,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = muted.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (visibleMessage != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = visibleMessage,
                    fontSize = 13.sp,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                TextButton(text = actionText, onClick = onAction,
                    colors = ButtonDefaults.textButtonColors(color = androidx.compose.ui.graphics.Color.Transparent,
                        textColor = MiuixTheme.colorScheme.primary))
            }
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MiuixIcons.Refresh,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    EmptyState(
        title = title,
        message = message,
        modifier = modifier,
        icon = icon,
        showMessage = true,
        actionText = actionText,
        onAction = onAction,
    )
}

/** A continuous group with separately keyed, lazily composed rows. */
fun <T> androidx.compose.foundation.lazy.LazyListScope.lazyGroup(
    entries: List<T>,
    key: (T) -> Any,
    contentType: String,
    row: @Composable (T) -> Unit,
) {
    items(count = entries.size, key = { "$contentType:${key(entries[it])}" }, contentType = { contentType }) { index ->
        val topRadius = if (index == 0) 16.dp else 0.dp
        val bottom = if (index == entries.lastIndex) 16.dp else 0.dp
        top.yukonga.miuix.kmp.basic.Surface(
            modifier = Modifier.animateItem().fillMaxWidth().padding(horizontal = 12.dp)
                .padding(bottom = if (index == entries.lastIndex) 6.dp else 0.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = topRadius, topEnd = topRadius,
                bottomStart = bottom, bottomEnd = bottom),
            color = MiuixTheme.colorScheme.surfaceContainer,
        ) { row(entries[index]) }
    }
}

@Composable
fun ExpandingPreferenceGroup(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.expandVertically(expandFrom = Alignment.Top) + fadeIn(),
        exit = androidx.compose.animation.shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
    ) { Column(content = content) }
}
