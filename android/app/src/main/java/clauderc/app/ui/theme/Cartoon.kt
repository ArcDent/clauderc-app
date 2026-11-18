package clauderc.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import clauderc.app.data.UiStyle

// 贴纸's cartoon icons (the demo's <symbol id="ci-…">): round-capped strokes on a 24 grid, with the
// outlined shapes filled faintly when picked (--ci-fill) and small dots for eyes and knobs. Icon tints
// the whole vector, so the faint fill stays a share of the tint.

/** The stroke of 贴纸's icons (--ci-w). */
private const val CartoonStroke = 2.2f

/** --ci-fill of a picked nav icon: the tint at 22%. */
private const val PickedFill = 0.22f

/** One shape: [d] is stroked; [outlined] shapes get the picked fill, [dot] ones are filled solid and not stroked. */
private class Part(val d: String, val outlined: Boolean = false, val dot: Boolean = false)

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
    "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"

private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"

private fun cartoon(name: String, picked: Boolean, vararg parts: Part): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (p in parts) {
            val nodes = addPathNodes(p.d)
            when {
                p.dot -> addPath(nodes, fill = SolidColor(Color.Black))
                else -> addPath(
                    nodes,
                    fill = if (p.outlined && picked) SolidColor(Color.Black.copy(alpha = PickedFill)) else null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = CartoonStroke,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
    }.build()

private fun computer(picked: Boolean) = cartoon(
    "ci-computer", picked,
    Part(rect(2.5f, 3.5f, 19f, 13f, 3.5f), outlined = true),
    Part("M9 20.5h6M12 16.5v4"),
    Part(circle(9f, 9.4f, 1.15f), dot = true),
    Part(circle(15f, 9.4f, 1.15f), dot = true),
    Part("M10.2 12.2q1.8 1.3 3.6 0"),
)

private fun forum(picked: Boolean) = cartoon(
    "ci-forum", picked,
    Part("M5 4.5h14A2.5 2.5 0 0 1 21.5 7v8a2.5 2.5 0 0 1-2.5 2.5h-6.5l-4.5 3.5v-3.5H5A2.5 2.5 0 0 1 2.5 15V7A2.5 2.5 0 0 1 5 4.5z", outlined = true),
    Part(circle(8f, 11f, 1.2f), dot = true),
    Part(circle(12f, 11f, 1.2f), dot = true),
    Part(circle(16f, 11f, 1.2f), dot = true),
)

private fun accountTree(picked: Boolean) = cartoon(
    "ci-account_tree", picked,
    Part("M6.5 7.8v8.4M17.5 10.3c0 4.4-11 2.2-11 6"),
    Part(circle(6.5f, 5.5f, 2.4f), outlined = true),
    Part(circle(6.5f, 18.5f, 2.4f), outlined = true),
    Part(circle(17.5f, 8f, 2.4f), outlined = true),
)

private fun settings() = cartoon(
    "ci-settings", false,
    Part("M3.5 8h17M3.5 16h17"),
    Part(circle(9f, 8f, 2.7f), dot = true),
    Part(circle(15.5f, 16f, 2.7f), dot = true),
)

/** 贴纸's icons, keyed by the Material symbol they stand in for. */
object Cartoon {
    val Computer by lazy { computer(false) }
    val ComputerPicked by lazy { computer(true) }
    val Forum by lazy { forum(false) }
    val ForumPicked by lazy { forum(true) }
    val AccountTree by lazy { accountTree(false) }
    val AccountTreePicked by lazy { accountTree(true) }
    val Settings by lazy { settings() }
    val Dns by lazy {
        cartoon(
            "ci-dns", false,
            Part(rect(3f, 3.5f, 18f, 7.5f, 2.6f), outlined = true),
            Part(rect(3f, 13f, 18f, 7.5f, 2.6f), outlined = true),
            Part(circle(7.2f, 7.25f, 1.1f), dot = true),
            Part(circle(7.2f, 16.75f, 1.1f), dot = true),
            Part("M11.5 7.25h5M11.5 16.75h5"),
        )
    }
    val Add by lazy { cartoon("ci-add", false, Part("M12 5v14M5 12h14")) }
    val MoreVert by lazy {
        cartoon("ci-more_vert", false, Part(circle(12f, 5.5f, 1.9f), dot = true), Part(circle(12f, 12f, 1.9f), dot = true), Part(circle(12f, 18.5f, 1.9f), dot = true))
    }
    val ArrowBack by lazy { cartoon("ci-arrow_back", false, Part("M11 5.5 4.5 12l6.5 6.5M5 12h14.5")) }
    val ArrowUpward by lazy { cartoon("ci-arrow_upward", false, Part("M12 19.5V5M5.5 11.5 12 5l6.5 6.5")) }
    val Terminal by lazy { cartoon("ci-terminal", false, Part(rect(2.5f, 4f, 19f, 16f, 4f), outlined = true), Part("M7 9.5l3 2.5-3 2.5M12.5 15H17")) }
    val Image by lazy {
        cartoon("ci-image", false, Part(rect(3f, 4f, 18f, 16f, 4f), outlined = true), Part(circle(9f, 9.5f, 1.8f)), Part("M3.5 17l5-4.5 3.5 3 3-2.5 5.5 4.5"))
    }
    val Refresh by lazy { cartoon("ci-refresh", false, Part("M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v4h-4")) }
    val Close by lazy { cartoon("ci-close", false, Part("M6.5 6.5l11 11M17.5 6.5l-11 11")) }
    val Folder by lazy {
        cartoon("ci-folder", false, Part("M3 7.5A2.5 2.5 0 0 1 5.5 5h3.6l2.2 2.2h7.2A2.5 2.5 0 0 1 21 9.7v7.8a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5z", outlined = true))
    }

    /** The cartoon version of [symbol], or null when 贴纸 keeps the Material one. */
    fun of(symbol: ImageVector): ImageVector? = when (symbol) {
        Symbols.Computer -> Computer
        Symbols.ComputerFilled -> ComputerPicked
        Symbols.Forum -> Forum
        Symbols.ForumFilled -> ForumPicked
        Symbols.AccountTree -> AccountTree
        Symbols.AccountTreeFilled -> AccountTreePicked
        Symbols.Settings, Symbols.SettingsFilled -> Settings
        Symbols.Dns -> Dns
        Symbols.Add -> Add
        Symbols.MoreVert -> MoreVert
        Symbols.ArrowBack -> ArrowBack
        Symbols.ArrowUpward -> ArrowUpward
        Symbols.Terminal -> Terminal
        Symbols.Image -> Image
        Symbols.Refresh -> Refresh
        Symbols.Close -> Close
        Symbols.Folder -> Folder
        else -> null
    }
}

/** [this] symbol as the current style draws it: 贴纸 swaps in its cartoon icon where it has one. */
val ImageVector.styled: ImageVector
    @Composable @ReadOnlyComposable get() = if (LocalUiStyle.current == UiStyle.Sticker) Cartoon.of(this) ?: this else this
