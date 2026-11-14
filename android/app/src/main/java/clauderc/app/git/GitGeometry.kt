package clauderc.app.git

import clauderc.app.protocol.Commit
import clauderc.app.protocol.RefKind

/** A point in row-local coordinates: x from the left edge, y from the top of the row. */
data class Pt(val x: Float, val y: Float)

/** One cubic piece of a timeline line inside a single row; [lane] picks the color. */
data class Segment(val p0: Pt, val p1: Pt, val p2: Pt, val p3: Pt, val lane: Int)

data class RowGeometry(
    val node: Pt,
    val lane: Int,
    /** Merge commits and HEAD are drawn as rings. */
    val hollow: Boolean,
    val segments: List<Segment>,
)

/** Mockup sizes: 64dp rows, first lane at 24dp, 14dp between lanes. */
data class TimelineMetrics(val row: Float = 64f, val x0: Float = 24f, val lane: Float = 14f) {
    fun x(lane: Int) = x0 + lane * this.lane
}

/**
 * Turns the agent's layout into drawable pieces (task 14.1). Each edge runs
 * from its lane on row i to its lane on row i+1 as an S curve; it is split at
 * the row boundary so every row draws only its own pieces: the upper half of
 * its outgoing edges and the lower half of the previous row's edges. A line
 * takes the color of the higher of its two lanes, like the mockup.
 */
fun timelineGeometry(commits: List<Commit>, headBranch: String?, m: TimelineMetrics = TimelineMetrics()): List<RowGeometry> {
    val r = m.row
    val c = r / 2
    return commits.mapIndexed { i, commit ->
        val segments = mutableListOf<Segment>()
        commits.getOrNull(i - 1)?.edges?.forEach { e ->
            val (_, lower) = splitEdge(m.x(e.from), m.x(e.to), r)
            segments += lower.shift(-r + c, maxOf(e.from, e.to))
        }
        commit.edges.forEach { e ->
            val (upper, _) = splitEdge(m.x(e.from), m.x(e.to), r)
            segments += upper.shift(c, maxOf(e.from, e.to))
        }
        val isHead = headBranch != null && commit.refs.orEmpty().any { it.kind == RefKind.Branch && it.name == headBranch }
        RowGeometry(Pt(m.x(commit.lane), c), commit.lane, hollow = commit.parents.size > 1 || isHead, segments = segments)
    }
}

private data class Cubic(val p0: Pt, val p1: Pt, val p2: Pt, val p3: Pt) {
    fun shift(dy: Float, lane: Int) = Segment(p0.dy(dy), p1.dy(dy), p2.dy(dy), p3.dy(dy), lane)
}

private fun Pt.dy(d: Float) = Pt(x, y + d)

/** The edge from (xa, 0) to (xb, r) with vertical tangents, split at t = 0.5 (de Casteljau). */
private fun splitEdge(xa: Float, xb: Float, r: Float): Pair<Cubic, Cubic> {
    val p0 = Pt(xa, 0f)
    val p1 = Pt(xa, r / 2)
    val p2 = Pt(xb, r / 2)
    val p3 = Pt(xb, r)
    val mid = Pt((p0.x + 3 * p1.x + 3 * p2.x + p3.x) / 8, (p0.y + 3 * p1.y + 3 * p2.y + p3.y) / 8)
    val upper = Cubic(p0, Pt((p0.x + p1.x) / 2, (p0.y + p1.y) / 2), Pt((p0.x + 2 * p1.x + p2.x) / 4, (p0.y + 2 * p1.y + p2.y) / 4), mid)
    val lower = Cubic(mid, Pt((p1.x + 2 * p2.x + p3.x) / 4, (p1.y + 2 * p2.y + p3.y) / 4), Pt((p2.x + p3.x) / 2, (p2.y + p3.y) / 2), p3)
    return upper to lower
}

/** Where the subject text starts so it clears the widest lane. */
fun graphWidth(commits: List<Commit>, m: TimelineMetrics = TimelineMetrics()): Float {
    val maxLane = commits.maxOfOrNull { c -> maxOf(c.lane, c.edges.maxOfOrNull { maxOf(it.from, it.to) } ?: 0) } ?: 0
    return maxOf(68f, m.x(maxLane) + 20f)
}

enum class DiffKind { Hunk, Context, Add, Del }

data class DiffLine(val kind: DiffKind, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

/** Parses unified diff text, skipping the file headers before the first hunk. */
fun parsePatch(patch: String): List<DiffLine> {
    val out = mutableListOf<DiffLine>()
    var old = 0
    var new = 0
    var inHunk = false
    val header = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@.*""")
    for (line in patch.removeSuffix("\n").split('\n')) {
        val h = header.matchEntire(line)
        when {
            h != null -> {
                old = h.groupValues[1].toInt()
                new = h.groupValues[2].toInt()
                inHunk = true
                out += DiffLine(DiffKind.Hunk, line)
            }
            !inHunk -> Unit
            line.startsWith("+") -> out += DiffLine(DiffKind.Add, line.drop(1), newNo = new++)
            line.startsWith("-") -> out += DiffLine(DiffKind.Del, line.drop(1), oldNo = old++)
            line.startsWith("\\") -> Unit // "\ No newline at end of file"
            else -> out += DiffLine(DiffKind.Context, line.drop(1), oldNo = old++, newNo = new++)
        }
    }
    return out
}
