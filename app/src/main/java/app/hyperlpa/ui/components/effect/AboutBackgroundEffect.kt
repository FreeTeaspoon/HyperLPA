package app.hyperlpa.ui.components.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import app.hyperlpa.ui.theme.LocalDarkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

@Composable
fun AboutBackgroundEffect(
    dynamicBackground: Boolean,
    modifier: Modifier = Modifier,
    backdropModifier: Modifier = Modifier,
    alpha: () -> Float = { 1f },
    content: @Composable BoxScope.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) {
        Box(modifier = modifier, content = content)
        return
    }
    Box(modifier = modifier) {
        val surface = MiuixTheme.colorScheme.surface
        val isDark = LocalDarkTheme.current
        val painter = remember { AboutBackgroundPainter() }
        val preset = remember(isDark) { AboutBackgroundConfig.get(isDark) }
        val colorStage = remember { Animatable(0f) }

        LaunchedEffect(dynamicBackground, preset) {
            if (!dynamicBackground) return@LaunchedEffect
            var targetStage = floor(colorStage.value) + 1f
            while (isActive) {
                delay((preset.colorInterpPeriod * 500).toLong())
                colorStage.animateTo(
                    targetValue = targetStage,
                    animationSpec = spring(dampingRatio = 0.9f, stiffness = 35f),
                )
                targetStage += 1f
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .aboutBackgroundDraw(
                    painter = painter,
                    preset = preset,
                    isDark = isDark,
                    surface = surface,
                    playing = dynamicBackground,
                    colorStage = { colorStage.value },
                    alpha = alpha,
                ),
        )
        content()
    }
}

private object AboutBackgroundConfig {
    class Config(
        val points: FloatArray,
        val colors1: FloatArray,
        val colors2: FloatArray,
        val colors3: FloatArray,
        val colorInterpPeriod: Float,
        val lightOffset: Float,
        val saturateOffset: Float,
        val pointOffset: Float,
    )

    private val Light = Config(
        points = floatArrayOf(0.8f, 0.2f, 1f, 0.8f, 0.9f, 1f, 0.2f, 0.9f, 1f, 0.2f, 0.2f, 1f),
        colors1 = floatArrayOf(1f, 0.9f, 0.94f, 1f, 1f, 0.84f, 0.89f, 1f, 0.97f, 0.73f, 0.82f, 1f, 0.64f, 0.65f, 0.98f, 1f),
        colors2 = floatArrayOf(0.58f, 0.74f, 1f, 1f, 1f, 0.9f, 0.93f, 1f, 0.74f, 0.76f, 1f, 1f, 0.97f, 0.77f, 0.84f, 1f),
        colors3 = floatArrayOf(0.98f, 0.86f, 0.9f, 1f, 0.6f, 0.73f, 0.98f, 1f, 0.92f, 0.93f, 1f, 1f, 0.56f, 0.69f, 1f, 1f),
        colorInterpPeriod = 5f,
        lightOffset = 0.1f,
        saturateOffset = 0.2f,
        pointOffset = 0.2f,
    )

    private val Dark = Config(
        points = floatArrayOf(0.8f, 0.2f, 1f, 0.8f, 0.9f, 1f, 0.2f, 0.9f, 1f, 0.2f, 0.2f, 1f),
        colors1 = floatArrayOf(0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f, 0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f),
        colors2 = floatArrayOf(0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f, 0.06f, 0.25f, 0.84f, 0.5f, 0f, 0.2f, 0.78f, 0.5f),
        colors3 = floatArrayOf(0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f, 0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f),
        colorInterpPeriod = 8f,
        lightOffset = 0f,
        saturateOffset = 0.17f,
        pointOffset = 0.4f,
    )

    fun get(isDark: Boolean): Config = if (isDark) Dark else Light
}

private class AboutBackgroundPainter {
    val runtimeShader by lazy {
        RuntimeShader(AboutBackgroundShader).also {
            it.setFloatUniform("uTranslateY", 0f)
            it.setFloatUniform("uNoiseScale", 1.5f)
            it.setFloatUniform("uPointRadiusMulti", 1f)
            it.setFloatUniform("uAlphaMulti", 1f)
        }
    }
    val brush: Brush get() = runtimeShader.asBrush()

    private val resolution = FloatArray(2)
    private val bound = FloatArray(4)
    private val colors = FloatArray(16)
    private val animatedPoints = FloatArray(8)
    private var animationTime = Float.NaN
    private var darkMode: Boolean? = null
    private var logoHeight = Float.NaN
    private var totalHeight = Float.NaN
    private var totalWidth = Float.NaN
    private var colorStage = Float.NaN
    private var colorPreset: AboutBackgroundConfig.Config? = null
    private var pointsTime = Float.NaN
    private var pointsPreset: AboutBackgroundConfig.Config? = null

    fun updateResolution(width: Float, height: Float) {
        if (resolution[0] == width && resolution[1] == height) return
        resolution[0] = width
        resolution[1] = height
        runtimeShader.setFloatUniform("uResolution", resolution)
    }

    fun updateAnimationTime(time: Float) {
        if (animationTime == time) return
        animationTime = time
        runtimeShader.setFloatUniform("uAnimTime", time)
    }

    fun updateAnimatedPoints(time: Float, preset: AboutBackgroundConfig.Config) {
        if (pointsTime == time && pointsPreset === preset) return
        for (index in 0 until 4) {
            val sourceX = preset.points[index * 3]
            val sourceY = preset.points[index * 3 + 1]
            val animatedX = sourceX + sin(time + sourceY) * preset.pointOffset
            val animatedY = sourceY + cos(time + animatedX) * preset.pointOffset
            animatedPoints[index * 2] = animatedX
            animatedPoints[index * 2 + 1] = animatedY
        }
        runtimeShader.setFloatUniform("uPointsAnim", animatedPoints)
        pointsTime = time
        pointsPreset = preset
    }

    fun updateColors(preset: AboutBackgroundConfig.Config, stage: Float) {
        if (colorPreset === preset && colorStage == stage) return
        val base = stage.toInt()
        val fraction = stage - base
        val start = colorsFor(preset, base)
        val end = colorsFor(preset, base + 1)
        for (index in colors.indices) {
            colors[index] = start[index] + (end[index] - start[index]) * fraction
        }
        runtimeShader.setFloatUniform("uColors", colors)
        colorPreset = preset
        colorStage = stage
    }

    private fun colorsFor(preset: AboutBackgroundConfig.Config, index: Int): FloatArray = when (index.mod(4)) {
        1 -> preset.colors1
        3 -> preset.colors3
        else -> preset.colors2
    }

    fun updateBounds(drawHeight: Float, height: Float, width: Float) {
        if (logoHeight == drawHeight && totalHeight == height && totalWidth == width) return
        val heightRatio = drawHeight / height
        if (width <= height) {
            bound[0] = 0f
            bound[1] = 1f - heightRatio
            bound[2] = 1f
            bound[3] = heightRatio
        } else {
            val aspectRatio = width / height
            val contentCenterY = 1f - heightRatio / 2f
            bound[0] = 0f
            bound[1] = contentCenterY - aspectRatio / 2f
            bound[2] = 1f
            bound[3] = aspectRatio
        }
        runtimeShader.setFloatUniform("uBound", bound)
        logoHeight = drawHeight
        totalHeight = height
        totalWidth = width
    }

    fun updatePreset(isDark: Boolean, preset: AboutBackgroundConfig.Config) {
        if (darkMode == isDark) return
        runtimeShader.setFloatUniform("uPoints", preset.points)
        runtimeShader.setFloatUniform("uLightOffset", preset.lightOffset)
        runtimeShader.setFloatUniform("uSaturateOffset", preset.saturateOffset)
        darkMode = isDark
    }
}

private fun Modifier.aboutBackgroundDraw(
    painter: AboutBackgroundPainter,
    preset: AboutBackgroundConfig.Config,
    isDark: Boolean,
    surface: Color,
    playing: Boolean,
    colorStage: () -> Float,
    alpha: () -> Float,
): Modifier = this then AboutBackgroundElement(
    painter = painter,
    preset = preset,
    isDark = isDark,
    surface = surface,
    playing = playing,
    colorStage = colorStage,
    alpha = alpha,
)

private data class AboutBackgroundElement(
    val painter: AboutBackgroundPainter,
    val preset: AboutBackgroundConfig.Config,
    val isDark: Boolean,
    val surface: Color,
    val playing: Boolean,
    val colorStage: () -> Float,
    val alpha: () -> Float,
) : ModifierNodeElement<AboutBackgroundNode>() {
    override fun create() = AboutBackgroundNode(painter, preset, isDark, surface, playing, colorStage, alpha)

    override fun update(node: AboutBackgroundNode) {
        node.update(painter, preset, isDark, surface, playing, colorStage, alpha)
    }
}

private class AboutBackgroundNode(
    private var painter: AboutBackgroundPainter,
    private var preset: AboutBackgroundConfig.Config,
    private var isDark: Boolean,
    private var surface: Color,
    private var playing: Boolean,
    private var colorStage: () -> Float,
    private var alpha: () -> Float,
) : Modifier.Node(), DrawModifierNode {
    private var animationJob: Job? = null
    private var animationTime = 0f
    private var startOffset = 0f

    override fun onAttach() {
        if (playing) startAnimation()
    }

    override fun onDetach() {
        animationJob?.cancel()
        animationJob = null
    }

    fun update(
        painter: AboutBackgroundPainter,
        preset: AboutBackgroundConfig.Config,
        isDark: Boolean,
        surface: Color,
        playing: Boolean,
        colorStage: () -> Float,
        alpha: () -> Float,
    ) {
        this.painter = painter
        this.preset = preset
        this.isDark = isDark
        this.surface = surface
        this.colorStage = colorStage
        this.alpha = alpha
        if (this.playing != playing) {
            this.playing = playing
            if (playing) startAnimation() else {
                animationJob?.cancel()
                animationJob = null
            }
        }
        invalidateDraw()
    }

    private fun startAnimation() {
        animationJob?.cancel()
        startOffset = animationTime
        animationJob = coroutineScope.launch {
            val minimumFrameNanos = 1_000_000_000L / 60L
            val origin = withFrameNanos { it }
            var lastFrame = origin
            while (isActive) {
                val now = withFrameNanos { it }
                if (now - lastFrame < minimumFrameNanos) continue
                lastFrame = now
                animationTime = startOffset + (now - origin) / 1_000_000_000f
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawRect(surface)
        val currentAlpha = alpha()
        if (currentAlpha > 0f) {
            val drawHeight = size.height * 0.8f
            painter.updateResolution(size.width, size.height)
            painter.updateBounds(drawHeight, size.height, size.width)
            painter.updatePreset(isDark, preset)
            painter.updateColors(preset, colorStage())
            painter.updateAnimationTime(animationTime)
            painter.updateAnimatedPoints(animationTime, preset)
            drawRect(painter.brush, alpha = currentAlpha)
        }
        drawContent()
    }
}

private const val AboutBackgroundShader = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform float uTranslateY;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uAlphaMulti;
    uniform float uNoiseScale;
    uniform float uPointRadiusMulti;
    uniform float uSaturateOffset;
    uniform float uLightOffset;

    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
        vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
        float d = q.x - min(q.w, q.y);
        float e = 1.0e-10;
        return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
    }

    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
        vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
        return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
    }

    float hash(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx) * 0.13);
        p3 += dot(p3, p3.yzx + 3.333);
        return fract((p3.x + p3.y) * p3.z);
    }

    float perlin(vec2 x) {
        vec2 i = floor(x); vec2 f = fract(x);
        float a = hash(i); float b = hash(i + vec2(1.0, 0.0));
        float c = hash(i + vec2(0.0, 1.0)); float d = hash(i + vec2(1.0, 1.0));
        vec2 u = f * f * (3.0 - 2.0 * f);
        return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;
    }

    float gradientNoise(in vec2 uv) {
        return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
    }

    vec4 main(vec2 fragCoord) {
        vec2 vUv = fragCoord / uResolution;
        vUv.y = 1.0 - vUv.y;
        vec2 uv = vUv;
        uv -= vec2(0.0, uTranslateY);
        uv.xy -= uBound.xy;
        uv.xy /= uBound.zw;
        vec4 color = vec4(0.0);
        float noiseValue = perlin(vUv * uNoiseScale + vec2(-uAnimTime, -uAnimTime));
        for (int i = 0; i < 4; i++) {
            vec4 pointColor = uColors[i];
            pointColor.rgb *= pointColor.a;
            vec2 point = uPointsAnim[i];
            float rad = uPoints[i].z * uPointRadiusMulti;
            float d = distance(uv, point);
            float pct = smoothstep(rad, 0.0, d);
            color.rgb = mix(color.rgb, pointColor.rgb, pct);
            color.a = mix(color.a, pointColor.a, pct);
        }
        float oppositeNoise = smoothstep(0.0, 1.0, noiseValue);
        color.rgb /= color.a;
        vec3 hsv = rgb2hsv(color.rgb);
        hsv.y = mix(hsv.y, 0.0, oppositeNoise * uSaturateOffset);
        color.rgb = hsv2rgb(hsv);
        color.rgb += oppositeNoise * uLightOffset;
        color.a = clamp(color.a, 0.0, 1.0);
        color.a *= uAlphaMulti;
        color += (10.0 / 255.0) * gradientNoise(fragCoord.xy) - (5.0 / 255.0);
        return vec4(color.rgb * color.a, color.a);
    }
"""
