// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// Header capture and shadow adapter adapted from CC Declutter's inspected OS4 profile.
package app.hyperlpa.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import app.hyperlpa.ui.adaptive.rememberIsWideWindow
import app.hyperlpa.ui.components.liquid.rememberCombinedBackdrop
import app.hyperlpa.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.*
import top.yukonga.miuix.kmp.glass.*
import top.yukonga.miuix.kmp.material.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Finished page only. The root Scaffold's overlay layer is outside this capture. */
val LocalDialogBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }
private val LocalHeaderBackdrop = staticCompositionLocalOf<Backdrop?> { null }
private val LocalHeaderBlur = staticCompositionLocalOf<HeaderBlurState?> { null }

/** The active scroll host reports its actual laid-out range, including lists and grids. */
class HeaderViewport(
    val measured: () -> Boolean,
    val canScrollBackward: () -> Boolean,
    val canScrollForward: () -> Boolean,
    val scrollRangePx: () -> Float = {
        if (!canScrollBackward() && !canScrollForward()) 0f else Float.POSITIVE_INFINITY
    },
)

@Composable
fun rememberHeaderViewport(list: LazyListState): HeaderViewport = remember(list) {
    HeaderViewport({ list.layoutInfo.viewportSize.height > 0 }, { list.canScrollBackward }, { list.canScrollForward }) {
        val info = list.layoutInfo
        val items = info.visibleItemsInfo
        if (!list.canScrollBackward && !list.canScrollForward) 0f
        else if (items.firstOrNull()?.index == 0 && items.lastOrNull()?.index == info.totalItemsCount - 1) {
            (items.last().offset + items.last().size - items.first().offset +
                info.beforeContentPadding + info.afterContentPadding - info.viewportSize.height).coerceAtLeast(0).toFloat()
        } else Float.POSITIVE_INFINITY
    }
}

@Composable
fun rememberHeaderViewport(grid: LazyGridState): HeaderViewport = remember(grid) {
    HeaderViewport({ grid.layoutInfo.viewportSize.height > 0 }, { grid.canScrollBackward }, { grid.canScrollForward }) {
        val info = grid.layoutInfo
        val items = info.visibleItemsInfo
        if (!grid.canScrollBackward && !grid.canScrollForward) 0f
        else if (items.firstOrNull()?.index == 0 && items.lastOrNull()?.index == info.totalItemsCount - 1) {
            (items.maxOf { it.offset.y + it.size.height } - items.minOf { it.offset.y } +
                info.beforeContentPadding + info.afterContentPadding - info.viewportSize.height).coerceAtLeast(0).toFloat()
        } else Float.POSITIVE_INFINITY
    }
}

class HeaderBlurState(
    private val viewport: HeaderViewport,
    private val scroll: ScrollBehavior,
    private val rampDistancePx: Float,
    private val shadowRampDistancePx: Float,
    private val collapsible: Boolean,
    private val fullShadow: Boolean = false,
) {
    val usesCollapseFallback: Boolean
        get() = collapsible && viewport.measured() && viewport.scrollRangePx() < rampDistancePx
    val alpha: Float
        get() = if (usesCollapseFallback) scroll.state.collapsedFraction.coerceIn(0f, 1f)
        else (-scroll.state.contentOffset / rampDistancePx).coerceIn(0f, 1f)
    val isContentScrolled: Boolean
        get() = viewport.canScrollBackward() || (usesCollapseFallback && scroll.state.collapsedFraction > 0f)
    val isFloating: Boolean
        get() = fullShadow || isContentScrolled
    val shadowAlpha: Float
        get() = when {
            fullShadow -> 1f
            usesCollapseFallback -> scroll.state.collapsedFraction.coerceIn(0f, 1f)
            else -> (-scroll.state.contentOffset / shadowRampDistancePx).coerceIn(0f, 1f)
        }
}

@Composable
private fun rememberHeaderBlurState(viewport: HeaderViewport, scroll: ScrollBehavior, collapsible: Boolean, fullShadow: Boolean): HeaderBlurState {
    val ramp = with(LocalDensity.current) { GlassTopAppBarDefaults.ProgressiveBlurRampDistance.toPx() }
    val shadowRamp = with(LocalDensity.current) { GlassTopAppBarDefaults.RampDistance.toPx() }
    return remember(viewport, scroll, ramp, shadowRamp, collapsible, fullShadow) {
        HeaderBlurState(viewport, scroll, ramp, shadowRamp, collapsible, fullShadow)
    }
}

// Follow positive scroll progress without lag; preserve the last visible frame on a jump to zero.
@Composable
private fun rememberHeaderFade(target: Float, tracksScroll: Boolean, label: String): State<Float> =
    animateFloatAsState(target,
        animationSpec = if (tracksScroll && target > 0f) snap() else GlassMotion.topBarButtonFloat(),
        label = label)

@Composable
private fun AdaptiveHeaderBackdrop(
    backdrop: LayerBackdrop?,
    blur: HeaderBlurState,
    content: @Composable (Backdrop?) -> Unit,
) {
    Box {
        if (backdrop == null) {
            content(null)
        } else {
            val band = rememberLayerBackdrop()
            HeaderBlurBand(backdrop, band, blur)
            // The button material can still sample the page as its blurred band fades away.
            content(rememberCombinedBackdrop(backdrop, band))
        }
    }
}

@Composable
private fun BoxScope.HeaderBlurBand(backdrop: LayerBackdrop, band: LayerBackdrop, blur: HeaderBlurState) {
    val bandAlpha = rememberHeaderFade(blur.alpha, tracksScroll = true, label = "os4HeaderBand")
    // A fully transparent band needs neither shaders nor offscreen effects.
    val visible by remember(bandAlpha) { derivedStateOf { bandAlpha.value > 0f } }
    val statusBars = WindowInsets.statusBars
    val colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.surface.copy(alpha = .3f))))
    Box(Modifier.matchParentSize()
        .layout { measurable, constraints ->
            val height = ((statusBars.getTop(this) + TopAppBarDefaults.CollapsedHeight.toPx() + 20.dp.toPx()) * 1.2f).toInt()
            val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.placeRelative(0, 0) }
        }
        .layerBackdrop(band)
        .graphicsLayer { alpha = bandAlpha.value.coerceIn(0f, 1f) }
        .progressiveTextureBlur(backdrop = backdrop, shape = RectangleShape,
            gradient = ProgressiveBlur.Top.copy(curve = .5f),
            blurRadius = GlassTopAppBarDefaults.ProgressiveBlurRadius.value, colors = colors,
            enabled = visible))
}

@Composable
fun Os4TopAppBar(
    title: String,
    backdrop: LayerBackdrop?,
    scrollBehavior: ScrollBehavior,
    viewport: HeaderViewport,
    modifier: Modifier = Modifier,
    fixed: Boolean = false,
    overPageArtwork: Boolean = false,
    artworkFallbackAlpha: (() -> Float)? = null,
    titleAlpha: () -> Float = { 1f },
    defaultWindowInsetsPadding: Boolean = true,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val wide = rememberIsWideWindow()
    val compact = !wide && !fixed && title.isNotBlank()
    val blur = rememberHeaderBlurState(viewport, scrollBehavior, compact, fullShadow = wide)
    val floating by remember(blur) { derivedStateOf { blur.isContentScrolled } }
    val dark = LocalDarkTheme.current
    AdaptiveHeaderBackdrop(backdrop, blur) { band ->
        CompositionLocalProvider(LocalHeaderBackdrop provides band, LocalHeaderBlur provides blur) {
            if (compact) {
                GlassTopAppBar(title = title, isContentScrolled = floating,
                    modifier = modifier, backdrop = backdrop, scrollBehavior = scrollBehavior,
                    // Suppress the native band only while our sampled band is available.
                    backgroundAlpha = { if (backdrop == null) 1f else 0f }, titleAlpha = titleAlpha,
                    style = GlassDefaults.style(dark), stroke = GlassEdgeStrokes.forTheme(dark),
                    defaultWindowInsetsPadding = defaultWindowInsetsPadding,
                    navigationIcon = navigationIcon, actions = actions)
            } else {
                val collapsedTitleAlpha = titleAlpha()
                val surface = MiuixTheme.colorScheme.surface
                val fallbackAlpha = artworkFallbackAlpha.takeIf { backdrop == null }
                val barModifier = if (fallbackAlpha != null) modifier.drawBehind {
                    val alpha = fallbackAlpha()
                    if (alpha > 0f) drawRect(surface, alpha = alpha)
                } else modifier
                // Like the demo's About page, keep the artwork uncovered until the title settles.
                val barColor = if (backdrop != null || fallbackAlpha != null || (overPageArtwork && collapsedTitleAlpha < 1f)) {
                    Color.Transparent
                } else surface
                SmallTopAppBar(title = title, modifier = barModifier, scrollBehavior = scrollBehavior,
                    color = barColor,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(alpha = collapsedTitleAlpha),
                    defaultWindowInsetsPadding = defaultWindowInsetsPadding,
                    navigationIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
                    actionIconPadding = GlassTopAppBarDefaults.HorizontalPadding,
                    navigationIcon = navigationIcon, actions = actions)
            }
        }
    }
}

@Composable
fun Os4IconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shadow: GlassShadow = GlassShadows.Regular, content: @Composable () -> Unit) {
    if (!enabled) return
    val dark = LocalDarkTheme.current
    val blur = LocalHeaderBlur.current
    val backdrop = LocalHeaderBackdrop.current
    val tracksScroll = backdrop != null && blur != null && !blur.usesCollapseFallback
    val shadowTarget = when {
        blur == null -> 1f
        tracksScroll || blur.usesCollapseFallback -> blur.shadowAlpha
        blur.isFloating -> 1f
        else -> 0f
    }
    val shadowAlpha = rememberHeaderFade(shadowTarget, tracksScroll, label = "os4ActionShadow")
    GlassIconButton(onClick = onClick,
        modifier = modifier,
        floating = blur?.isFloating,
        shadowAlpha = { shadowAlpha.value },
        inheritTopBarMaterial = false, backdrop = backdrop,
        style = GlassDefaults.style(dark), stroke = GlassEdgeStrokes.forTheme(dark),
        shadow = shadow, content = content)
}

@Composable
fun Os4Dialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    summary: String? = null,
    onDismissFinished: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    GlassDialog(show = show, onDismissRequest = onDismissRequest,
        backdrop = LocalDialogBackdrop.current, modifier = modifier,
        title = title, summary = summary, stroke = GlassEdgeStrokes.forTheme(LocalDarkTheme.current),
        onDismissFinished = onDismissFinished, content = content)
}

@Composable
fun DialogActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, role: GlassDialogButtonRole = GlassDialogButtonRole.Primary) {
    val haptic = LocalHapticFeedback.current
    GlassDialogButton(onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        onClick()
    }, modifier = modifier, enabled = enabled, role = role) {
        Text(text, style = MiuixTheme.textStyles.button)
    }
}

@Composable
fun Os4DropdownPreference(
    items: List<String>, selectedIndex: Int, title: String, onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier, summary: String? = null, enabled: Boolean = true,
    showValue: Boolean = true,
) {
    GlassDropdownPreference(onSelectedIndexChange = onSelectedIndexChange, items = items,
        selectedIndex = selectedIndex, title = title, backdrop = LocalDialogBackdrop.current,
        modifier = modifier, summary = summary?.takeUnless { showValue && it == items.getOrNull(selectedIndex) },
        enabled = enabled, showValue = showValue,
        visuals = GlassPopupDefaults.visuals(style = GlassDefaults.style(LocalDarkTheme.current),
            stroke = GlassEdgeStrokes.forTheme(LocalDarkTheme.current)))
}

/** Records the page separately so its menus can sample it and leave with it. */
private class MenuRegistry(val visit: () -> Any?) {
    val menus = mutableStateMapOf<Any, @Composable BoxScope.(LayerBackdrop?) -> Unit>()
}
private val LocalMenuRegistry = staticCompositionLocalOf<MenuRegistry> { error("Missing OS4 menu host") }

@Composable
fun Os4MenuHost(active: Boolean = true, content: @Composable () -> Unit) {
    // A quick Back may reveal this same composition. Give each visit a fresh identity.
    val visit = rememberUpdatedState(remember(active) { if (active) Any() else null })
    val registry = remember { MenuRegistry { visit.value } }
    val scene = if (registry.menus.isNotEmpty()) rememberAppBackdrop() else null
    CompositionLocalProvider(LocalMenuRegistry provides registry) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.matchParentSize().then(if (scene != null) Modifier.layerBackdrop(scene) else Modifier)) {
                content()
            }
            registry.menus.toMap().forEach { (token, menu) ->
                key(token) { menu(scene) }
            }
        }
    }
}

@Composable
fun Os4IconDropdownMenu(entry: DropdownEntry, flatAnchor: Boolean = false,
    shadow: GlassShadow = GlassShadows.Regular, content: @Composable () -> Unit) {
    val registry = LocalMenuRegistry.current
    val token = remember { Any() }
    val anchor = rememberGlassPopupAnchor()
    var openedOn by remember { mutableStateOf<Any?>(null) }
    var show by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val dark = LocalDarkTheme.current
    val entries = listOf(entry.copy(items = entry.items.map { it.withConfirmFeedback {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
    } }))
    val menu: @Composable BoxScope.(LayerBackdrop?) -> Unit = { scene ->
        GlassIconDropdownMenu(
            show = show && openedOn === registry.visit(),
            onDismissRequest = { show = false },
            anchor = anchor, anchorContent = content, entries = entries, backdrop = scene,
            visuals = GlassPopupDefaults.visuals(
                style = GlassDefaults.style(dark), stroke = GlassEdgeStrokes.forTheme(dark)),
            onDismissFinished = {
                show = false
                openedOn = null
            },
        )
    }
    val currentMenu = rememberUpdatedState(menu)
    if (openedOn != null) {
        DisposableEffect(registry, token) {
            registry.menus[token] = { scene -> currentMenu.value.invoke(this, scene) }
            onDispose { registry.menus.remove(token) }
        }
    }
    val onClick: () -> Unit = onClick@{
        val destination = registry.visit() ?: return@onClick
        if (!show || openedOn !== destination) {
            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            openedOn = destination
            show = true
        }
    }
    val size = GlassTopAppBarDefaults.ButtonSize
    val anchorModifier = Modifier.glassPopupAnchor(anchor, cornerRadius = size / 2, floating = !flatAnchor)
    val icon: @Composable () -> Unit = { Box(Modifier.glassPopupAnchorContent(anchor)) { content() } }
    if (flatAnchor) {
        IconButton(onClick = onClick, modifier = anchorModifier.size(size), cornerRadius = size / 2, content = icon)
    } else {
        Os4IconButton(onClick = onClick, modifier = anchorModifier, shadow = shadow, content = icon)
    }
}

@Composable
fun Os4BottomSheet(
    show: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    title: String? = null, startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    insideMargin: androidx.compose.ui.unit.DpSize = top.yukonga.miuix.kmp.layout.BottomSheetDefaults.insideMargin,
    onDismissFinished: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    GlassBottomSheet(show = show, onDismissRequest = onDismissRequest, backdrop = LocalDialogBackdrop.current,
        modifier = modifier, title = title, startAction = startAction, endAction = endAction,
        insideMargin = insideMargin, stroke = GlassEdgeStrokes.forTheme(LocalDarkTheme.current),
        onDismissFinished = onDismissFinished, content = content)
}

private fun DropdownItem.withConfirmFeedback(feedback: () -> Unit): DropdownItem = copy(
    onClick = onClick?.let { action -> { feedback(); action() } },
    children = children?.map { it.withConfirmFeedback(feedback) },
)
