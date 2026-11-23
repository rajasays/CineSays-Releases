package com.cineglass.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * No press/focus ripple. Used on TVs, where every control already draws its own focus ring: the ripple's focus
 * animation added several extra full-screen frames to each remote press, which low-end TVs drew slowly (the jerk).
 */
object NoIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : Modifier.Node(), androidx.compose.ui.node.DrawModifierNode {
            override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() { drawContent() }
        }
    override fun equals(other: Any?) = other === this
    override fun hashCode() = 7
}

/** True on phones / narrow windows (< 700dp wide): screens stack columns and shrink padding. */
val LocalCompact = androidx.compose.runtime.staticCompositionLocalOf { false }

// ───────────────────────────── Themes ─────────────────────────────
// Settings › Themes. Classic is the original CineSays look. A theme changes colours, title type, button shape and
// a few layouts (Home banner, navigation); it never changes what the app does. Saved per device.

enum class CsTheme(val label: String, val blurb: String) {
    CLASSIC("Classic", "The original CineSays look: navy and blue."),
    CINEMATIC("Cinematic", "Deep black, an edge-to-edge Home banner and Watch Now up front."),
    FOCUS("Focus", "Graphite and teal, a side rail on TV and Up next cards that open on focus."),
    EDITORIAL("Editorial", "Warm charcoal and gold, serif titles and rounded buttons.")
}

class CsPalette(
    val bg: Color, val surface: Color, val surfaceHigh: Color, val panel: Color, val line: Color, val lineStrong: Color,
    val accent: Color, val onAccent: Color, val text: Color, val textSoft: Color, val textMuted: Color, val buttonFocus: Color,
    val good: Color = Color(0xFF8EE6B5), val warn: Color = Color(0xFFFFCF91)
)

private val palettes = mapOf(
    CsTheme.CLASSIC to CsPalette(Color(0xFF050C18), Color(0xFF0F1C2E), Color(0xFF13263D), Color(0xFF0B1626), Color(0xFF22395A), Color(0xFF3A5578),
        Color(0xFF3DA9FF), Color(0xFF04111F), Color(0xFFF2F7FF), Color(0xFFC9D6E8), Color(0xFFA9BBD3), Color(0xFF1C3654)),
    CsTheme.CINEMATIC to CsPalette(Color(0xFF07090D), Color(0xFF12161D), Color(0xFF1A202A), Color(0xFF0D1015), Color(0xFF262E3A), Color(0xFF3A4556),
        Color(0xFF7CC4FF), Color(0xFF041422), Color(0xFFF5F7FA), Color(0xFFCDD3DC), Color(0xFFA2ABB8), Color(0xFF283140)),
    CsTheme.FOCUS to CsPalette(Color(0xFF0D1117), Color(0xFF161B22), Color(0xFF1F2630), Color(0xFF11161D), Color(0xFF2A3340), Color(0xFF3D4959),
        Color(0xFF58D3B0), Color(0xFF03201A), Color(0xFFF0F4F8), Color(0xFFC8D1DC), Color(0xFFA0ACBA), Color(0xFF233A36)),
    CsTheme.EDITORIAL to CsPalette(Color(0xFF15110D), Color(0xFF1F1A14), Color(0xFF2A231B), Color(0xFF1A1510), Color(0xFF3A3026), Color(0xFF5A4A39),
        Color(0xFFE0A84F), Color(0xFF2A1C08), Color(0xFFF7ECDC), Color(0xFFE3D5BF), Color(0xFFBFAE95), Color(0xFF3A2E20)),
)

/** CineSays palette for the chosen theme. Reading a colour in a composable recomposes it when the theme changes. */
object Cs {
    var theme by mutableStateOf(CsTheme.CLASSIC)
    private val p get() = palettes.getValue(theme)
    val bg get() = p.bg
    val surface get() = p.surface
    val surfaceHigh get() = p.surfaceHigh
    val panel get() = p.panel
    val line get() = p.line
    val lineStrong get() = p.lineStrong
    val accent get() = p.accent
    val onAccent get() = p.onAccent
    val text get() = p.text
    val textSoft get() = p.textSoft
    val textMuted get() = p.textMuted
    val good get() = p.good
    val warn get() = p.warn
    val focusRing get() = p.text
    val buttonFocus get() = p.buttonFocus
    /** Editorial titles are serif; the other themes keep the system sans. */
    val titleFont: androidx.compose.ui.text.font.FontFamily? get() = if (theme == CsTheme.EDITORIAL) androidx.compose.ui.text.font.FontFamily.Serif else null
    /** Editorial uses fully rounded buttons. */
    val buttonRadius: Dp get() = if (theme == CsTheme.EDITORIAL) 999.dp else 12.dp

    private const val PREFS = "cinesays_theme"
    fun load(context: android.content.Context) {
        val name = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("theme", null)
        theme = CsTheme.entries.firstOrNull { it.name == name } ?: CsTheme.CLASSIC
    }
    fun choose(context: android.content.Context, t: CsTheme) {
        theme = t
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("theme", t.name).apply()
    }
    /** Colours of any theme, for the Themes picker's previews. */
    fun previewOf(t: CsTheme): CsPalette = palettes.getValue(t)
}

private fun Modifier.focusRing(focused: Boolean, shape: RoundedCornerShape, rest: Color = Cs.line, restWidth: Dp = 1.dp) =
    border(if (focused) 3.dp else restWidth, if (focused) Cs.focusRing else rest, shape)

/** Pill used for tabs and filters. Selected = light fill; focused = white ring. */
@Composable
fun CsChip(label: String, selected: Boolean, modifier: Modifier = Modifier, compact: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(999.dp)
    Box(modifier.onFocusChanged { focused = it.isFocused }
        .focusRing(focused, shape, if (selected) Cs.text else Cs.line)
        .background(if (selected) Cs.text else if (focused) Cs.surfaceHigh else Color.Transparent, shape)
        .clickable(onClick = onClick).padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 6.dp else 8.dp), contentAlignment = Alignment.Center) {
        // One weight in every state: a bolder focused label would widen the chip and shift its neighbours.
        Text(label, color = if (selected) Cs.bg else if (focused) Cs.text else Cs.textSoft, fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun CsButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Cs.buttonRadius)
    Box(modifier.graphicsLayer { alpha = if (enabled) 1f else .45f }.onFocusChanged { focused = it.isFocused }
        .focusRing(focused, shape, if (primary) Cs.accent else Cs.lineStrong)
        .background(if (primary) Cs.accent else if (focused) Cs.buttonFocus else Cs.surfaceHigh, shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Cs.onAccent else Cs.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun CsSectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = Cs.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = Cs.titleFont)

@Composable
fun CsPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.border(1.dp, Cs.line, RoundedCornerShape(16.dp)).background(Cs.panel, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

/** Small light tag over artwork, e.g. a provider or "NEW". */
@Composable
fun CsBadge(text: String, modifier: Modifier = Modifier, dark: Boolean = false) {
    Text(text, modifier.background(if (dark) Cs.bg else Cs.text, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        color = if (dark) Cs.text else Cs.bg, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/**
 * Artwork with a flat placeholder colour shown only until the picture loads (or when there is none). Cheaper than a
 * solid background under the picture, which the GPU would otherwise paint on every frame.
 */
@Composable
fun CsArt(model: Any?, description: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop, placeholder: Color = Cs.surfaceHigh) {
    val fill = remember(placeholder) { androidx.compose.ui.graphics.painter.ColorPainter(placeholder) }
    AsyncImage(model, description, modifier, placeholder = fill, error = fill, fallback = fill, contentScale = contentScale)
}

/** Portrait poster with focus scale + ring, optional rating and badge. */
@Composable
fun CsPoster(item: TitleItem, width: Dp = 140.dp, badge: String? = null, showTitle: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).graphicsLayer { val s = if (focused) 1.07f else 1f; scaleX = s; scaleY = s },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }
            .focusRing(focused, shape).clip(shape).clickable(onClick = onClick)) {
            CsArt(item.image(), item.label, Modifier.fillMaxSize())
            if (item.rating > 0) CsBadge("★ ${"%.1f".format(item.rating)}", Modifier.align(Alignment.TopEnd).padding(6.dp), dark = true)
            badge?.let { CsBadge(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
        }
        if (showTitle) Text(listOf(item.label, item.year).filter { it.isNotBlank() }.joinToString(" · "),
            color = if (focused) Cs.text else Cs.textSoft, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun CsPosterRow(title: String, items: List<TitleItem>, badgeFor: (TitleItem) -> String? = { null }, onClick: (TitleItem) -> Unit) {
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CsSectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 6.dp, bottom = 6.dp, end = 48.dp)) {
            items(items, key = { it.ref }) { item -> CsPoster(item, badge = badgeFor(item)) { onClick(item) } }
        }
    }
}

/** Wrapping poster grid sized to the available width. */
@Composable
fun CsPosterGrid(items: List<TitleItem>, posterWidth: Dp = 132.dp, gap: Dp = 14.dp, pageSize: Int = 60, onClick: (TitleItem) -> Unit) {
    if (items.isEmpty()) return
    // Long lists (Tracker › Movies has hundreds) draw a page at a time, so the TV doesn't hold every poster in memory.
    var limit by remember(items.size) { mutableIntStateOf(pageSize) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (posterWidth + gap)).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.take(limit).chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item -> key(item.ref) { CsPoster(item, posterWidth) { onClick(item) } } }
                }
            }
            if (items.size > limit) CsButton("Show more (${items.size - limit} left)") { limit += pageSize }
        }
    }
}

/**
 * Two panes side by side on TV, stacked on phones. Give the left pane a fixed [leftWidth] or a
 * [leftWeight] (right pane takes weight 1).
 */
@Composable
fun CsTwoPane(
    gap: Dp = 24.dp, leftWidth: Dp? = null, leftWeight: Float = 1f,
    left: @Composable (Modifier) -> Unit, right: @Composable (Modifier) -> Unit
) {
    if (LocalCompact.current) Column(verticalArrangement = Arrangement.spacedBy(gap)) { left(Modifier.fillMaxWidth()); right(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
        left(if (leftWidth != null) Modifier.width(leftWidth) else Modifier.weight(leftWeight))
        right(Modifier.weight(1f))
    }
}

/** A labelled row of chips for filters (Discover, Roulette). */
@Composable
fun CsFilterRow(label: String, options: List<Pair<String, String>>, selected: String, labelWidth: Dp = 74.dp, onPick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.width(labelWidth), color = Cs.textMuted, fontSize = 13.sp)
        Row(Modifier.horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (text, value) -> CsChip(text, value == selected) { onPick(value) } }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.then(
    Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())
)
