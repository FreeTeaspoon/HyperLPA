package app.hyperlpa.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hyperlpa.BuildConfig
import app.hyperlpa.R
import app.hyperlpa.ui.adaptive.horizontalCutoutPadding
import app.hyperlpa.ui.components.BlurredBar
import app.hyperlpa.ui.components.effect.AboutBackgroundEffect
import app.hyperlpa.ui.components.rememberAppBackdrop
import app.hyperlpa.ui.theme.LocalBlurEnabled
import app.hyperlpa.ui.theme.LocalDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    val scrollProgress by remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }

    val backdrop = rememberAppBackdrop()
    val blurActive = backdrop != null && scrollProgress == 1f
    val barColor = when {
        blurActive -> Color.Transparent
        scrollProgress == 1f -> MiuixTheme.colorScheme.surface
        else -> Color.Transparent
    }

    Scaffold(
        topBar = {
            BlurredBar(
                backdrop = if (blurActive) backdrop else null,
                alpha = 0.8f,
            ) {
                SmallTopAppBar(
                    modifier = Modifier.horizontalCutoutPadding(),
                    title = stringResource(R.string.about_title),
                    scrollBehavior = scrollBehavior,
                    color = barColor,
                    titleColor = MiuixTheme.colorScheme.onSurface.copy(
                        alpha = ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f),
                    ),
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        val layoutDirection = LocalLayoutDirection.current
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(R.string.common_back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            AboutContent(
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                lazyListState = lazyListState,
                scrollProgress = scrollProgress,
            )
        }
    }
}

@Composable
private fun AboutContent(
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgress: Float,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    val uriHandler = LocalUriHandler.current
    val contentBackdrop = rememberLayerBackdrop()
    val isDark = LocalDarkTheme.current
    val blurEnabled = LocalBlurEnabled.current && isRuntimeShaderSupported()
    val cardBlendColors = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
                BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
                BlendColorEntry(Color(0xB3FFFFFF.toInt()), BlurBlendMode.HardLight),
            )
        }
    }
    val logoBlendColors = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xE6A1A1A1.toInt()), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4DE6E6E6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF500.toInt()), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xCC4A4A4A.toInt()), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xFF4F4F4F.toInt()), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xFF1AF200.toInt()), BlurBlendMode.Lab),
            )
        }
    }

    var logoHeight by remember { mutableStateOf(300.dp) }
    val versionProgress = ((scrollProgress - 0.05f) / 0.15f).coerceIn(0f, 1f)
    val projectNameProgress = ((scrollProgress - 0.20f) / 0.15f).coerceIn(0f, 1f)
    val iconProgress = ((scrollProgress - 0.35f) / 0.15f).coerceIn(0f, 1f)
    val scrollPadding = PaddingValues(
        top = innerPadding.calculateTopPadding(),
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )
    val logoPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )

    AboutBackgroundEffect(
        dynamicBackground = blurEnabled,
        modifier = Modifier.fillMaxSize(),
        backdropModifier = Modifier.layerBackdrop(contentBackdrop),
        alpha = { 1f - scrollProgress },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(layoutDirection),
                    end = logoPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size ->
                    with(density) { logoHeight = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .clipToBounds()
                    .graphicsLayer {
                        alpha = 1f - iconProgress
                        scaleX = 1f - iconProgress * 0.05f
                        scaleY = 1f - iconProgress * 0.05f
                    },
            ) {
                Image(
                    modifier = Modifier.requiredSize(108.dp),
                    painter = painterResource(R.drawable.about_logo),
                    contentDescription = stringResource(R.string.app_name),
                )
            }
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        alpha = 1f - projectNameProgress
                        scaleX = 1f - projectNameProgress * 0.05f
                        scaleY = 1f - projectNameProgress * 0.05f
                    }
                    .then(
                        if (blurEnabled) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                colors = BlurColors(blendColors = logoBlendColors),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = stringResource(R.string.app_name),
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1f - versionProgress
                        scaleX = 1f - versionProgress * 0.05f
                        scaleY = 1f - versionProgress * 0.05f
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = stringResource(
                    R.string.about_version_summary,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateStartPadding(layoutDirection),
                end = scrollPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            item(key = "logoSpacer") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeight + 52.dp + logoPadding.calculateTopPadding() -
                                scrollPadding.calculateTopPadding() + 126.dp,
                    ),
                    contentAlignment = Alignment.TopCenter,
                    content = {},
                )
            }

            item(key = "about") {
                Box {
                    Spacer(Modifier.fillParentMaxHeight())
                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        SmallTitle(text = stringResource(R.string.about_info))
                        AboutCard(
                            blurEnabled = blurEnabled,
                            backdrop = contentBackdrop,
                            blendColors = cardBlendColors,
                        ) {
                            BasicComponent(
                                title = stringResource(R.string.about_app_version),
                                summary = BuildConfig.VERSION_NAME,
                            )
                            BasicComponent(
                                title = stringResource(R.string.about_build_version),
                                summary = "${BuildConfig.VERSION_CODE}",
                            )
                        }

                        SmallTitle(text = stringResource(R.string.about_project))
                        AboutCard(
                            blurEnabled = blurEnabled,
                            backdrop = contentBackdrop,
                            blendColors = cardBlendColors,
                        ) {
                            ProjectPreference(
                                title = "HyperLPA",
                                summary = "github.com/FreeTeaspoon/HyperLPA",
                                url = "https://github.com/FreeTeaspoon/HyperLPA",
                                onOpenUrl = uriHandler::openUri,
                            )
                            ProjectPreference(
                                title = "OpenEUICC",
                                summary = "github.com/estkme-group/openeuicc",
                                url = "https://github.com/estkme-group/openeuicc",
                                onOpenUrl = uriHandler::openUri,
                            )
                            ProjectPreference(
                                title = "miuix",
                                summary = "github.com/compose-miuix-ui/miuix",
                                url = "https://github.com/compose-miuix-ui/miuix",
                                onOpenUrl = uriHandler::openUri,
                            )
                            ProjectPreference(
                                title = "quickie",
                                summary = "github.com/G00fY2/quickie",
                                url = "https://github.com/G00fY2/quickie",
                                onOpenUrl = uriHandler::openUri,
                            )
                        }

                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutCard(
    blurEnabled: Boolean,
    backdrop: LayerBackdrop,
    blendColors: List<BlendColorEntry>,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .then(
                if (blurEnabled) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RoundedCornerShape(16.dp),
                        blurRadius = 60f,
                        colors = BlurColors(blendColors = blendColors),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            color = if (blurEnabled) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
            contentColor = Color.Transparent,
        ),
        content = content,
    )
}

@Composable
private fun ProjectPreference(
    title: String,
    summary: String,
    url: String,
    onOpenUrl: (String) -> Unit,
) {
    ArrowPreference(
        title = title,
        summary = summary,
        onClick = { onOpenUrl(url) },
    )
}
