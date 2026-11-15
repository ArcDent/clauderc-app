package clauderc.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task 11.2: every --c-, --fs-, --sp-, --r-, --ease- and --dur- variable in the
 * approved mockup has a Kotlin token with the same value, in both themes.
 */
class ThemeTokensTest {

    private val html: String by lazy {
        val path = System.getProperty("clauderc.mockup") ?: error("system property clauderc.mockup is not set")
        File(path).readText()
    }

    private fun block(selector: String): Map<String, String> {
        val start = html.indexOf(selector).also { check(it >= 0) { "$selector not found in mockup" } }
        val open = html.indexOf('{', start)
        val close = html.indexOf('}', open)
        return Regex("""(--[a-z0-9-]+)\s*:\s*([^;]+);""").findAll(html.substring(open + 1, close))
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    private val light by lazy { block(":root {") }
    private val dark by lazy { block(""":root[data-theme="dark"] {""") }
    private val cardLight by lazy { block(""":root[data-style="card"] {""") }
    private val cardDark by lazy { block(""":root[data-style="card"][data-theme="dark"] {""") }

    private fun colors(c: ClaudercColors) = mapOf(
        "--c-bg" to c.bg, "--c-sheet" to c.sheet, "--c-card" to c.card, "--c-surface" to c.surface, "--c-surface-2" to c.surface2,
        "--c-line" to c.line, "--c-fg" to c.fg, "--c-fg-2" to c.fg2, "--c-fg-3" to c.fg3,
        "--c-accent" to c.accent, "--c-on-accent" to c.onAccent, "--c-danger" to c.danger,
        "--c-add-bg" to c.addBg, "--c-add-fg" to c.addFg, "--c-del-bg" to c.delBg, "--c-del-fg" to c.delFg,
        "--c-inverse" to c.inverse, "--c-on-inverse" to c.onInverse, "--c-scrim" to c.scrim,
        "--c-camera" to c.camera, "--c-on-camera" to c.onCamera,
        "--c-lane-1" to c.lane1, "--c-lane-2" to c.lane2, "--c-lane-3" to c.lane3,
        "--c-glass" to c.glass, "--c-glass-ind" to c.glassInd, "--c-glass-hi" to c.glassHi, "--c-glass-lo" to c.glassLo,
        "--c-glass-edge" to c.glassEdge, "--c-glass-sheen" to c.glassSheen, "--c-glass-shadow" to c.glassShadow,
    )

    private fun parseColor(css: String): Int {
        Regex("""#([0-9A-Fa-f]{6})""").matchEntire(css)?.let { return (0xFF000000 or it.groupValues[1].toLong(16)).toInt() }
        val m = Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),\s*([0-9.]+)\)""").matchEntire(css) ?: error("unsupported color $css")
        val (r, g, b) = m.groupValues.drop(1).take(3).map { it.toInt() }
        return Color(r, g, b, (m.groupValues[4].toFloat() * 255).roundToInt()).toArgb()
    }

    @Test fun lightColorsMatchMockup() {
        val tokens = colors(LightColors)
        val css = light.filterKeys { it.startsWith("--c-") }
        assertEquals("every light --c- variable has a token", css.keys, tokens.keys)
        css.forEach { (name, value) -> assertEquals(name, parseColor(value), tokens.getValue(name).toArgb()) }
    }

    @Test fun darkColorsMatchMockup() {
        val tokens = colors(DarkColors)
        // The dark block only overrides some variables; the rest inherit the light value.
        val css = light.filterKeys { it.startsWith("--c-") } + dark.filterKeys { it.startsWith("--c-") }
        assertEquals(css.keys, tokens.keys)
        css.forEach { (name, value) -> assertEquals(name, parseColor(value), tokens.getValue(name).toArgb()) }
    }

    @Test fun systemDarkBlockEqualsExplicitDarkBlock() {
        assertEquals(dark, block(""":root:not([data-theme="light"]) {"""))
    }

    // The card blocks only override some variables; the rest come from the minimal style of the same brightness.
    @Test fun cardLightColorsMatchMockup() {
        val tokens = colors(CardLightColors)
        val css = light.filterKeys { it.startsWith("--c-") } + cardLight.filterKeys { it.startsWith("--c-") }
        assertEquals(css.keys, tokens.keys)
        css.forEach { (name, value) -> assertEquals(name, parseColor(value), tokens.getValue(name).toArgb()) }
    }

    @Test fun cardDarkColorsMatchMockup() {
        val tokens = colors(CardDarkColors)
        val css = light.filterKeys { it.startsWith("--c-") } + dark.filterKeys { it.startsWith("--c-") } +
            cardDark.filterKeys { it.startsWith("--c-") }
        assertEquals("the card dark block overrides everything the card light block does", cardLight.keys, cardDark.keys)
        assertEquals(css.keys, tokens.keys)
        css.forEach { (name, value) -> assertEquals(name, parseColor(value), tokens.getValue(name).toArgb()) }
    }

    @Test fun systemCardDarkBlockEqualsExplicitCardDarkBlock() {
        assertEquals(cardDark, block(""":root[data-style="card"]:not([data-theme="light"]) {"""))
    }

    @Test fun fontSizesSpacingAndRadiiMatchMockup() {
        val f = ClaudercFontSizes()
        val s = ClaudercSpacing()
        val r = ClaudercRadii()
        val tokens = mapOf(
            "--fs-display" to f.display.value, "--fs-headline" to f.headline.value, "--fs-title" to f.title.value,
            "--fs-body" to f.body.value, "--fs-label" to f.label.value, "--fs-caption" to f.caption.value,
            "--fs-mono" to f.mono.value, "--fs-mono-sm" to f.monoSm.value,
            "--sp-1" to s.sp1.value, "--sp-2" to s.sp2.value, "--sp-3" to s.sp3.value, "--sp-4" to s.sp4.value,
            "--sp-5" to s.sp5.value, "--sp-6" to s.sp6.value, "--sp-8" to s.sp8.value, "--sp-page" to s.page.value,
            "--r-sm" to r.sm.value, "--r-md" to r.md.value, "--r-lg" to r.lg.value, "--r-card" to r.card.value, "--r-pill" to r.pill.value,
        )
        val css = light.filterKeys { it.startsWith("--fs-") || it.startsWith("--sp-") || it.startsWith("--r-") }
        assertEquals(css.keys, tokens.keys)
        css.forEach { (name, value) -> assertEquals(name, value.removeSuffix("px").toFloat(), tokens.getValue(name), 0f) }
    }

    @Test fun motionMatchesMockup() {
        val m = ClaudercMotion()
        val easings = mapOf("--ease-emph" to m.easeEmph, "--ease-emph-in" to m.easeEmphIn, "--ease-spring" to m.easeSpring, "--ease-reveal" to m.easeReveal)
        val durations = mapOf("--dur-s" to m.durS, "--dur-m" to m.durM, "--dur-l" to m.durL, "--dur-reveal" to m.durReveal)
        assertEquals(light.keys.filter { it.startsWith("--ease-") }.toSet(), easings.keys)
        assertEquals(light.keys.filter { it.startsWith("--dur-") }.toSet(), durations.keys)
        easings.forEach { (name, easing) ->
            val p = Regex("""cubic-bezier\(([^)]+)\)""").find(light.getValue(name))!!.groupValues[1].split(',').map { it.trim().toFloat() }
            val expected = CubicBezierEasing(p[0], p[1], p[2], p[3])
            for (x in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
                assertEquals("$name at $x", expected.transform(x), easing.transform(x), 1e-6f)
            }
        }
        durations.forEach { (name, ms) -> assertEquals(name, light.getValue(name).removeSuffix("ms").toInt(), ms) }
    }
}
