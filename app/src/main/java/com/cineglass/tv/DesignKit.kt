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

/** CineSays 2 palette (from the approved screen concepts). */
object Cs {
    val bg = Color(0xFF050C18)
    val surface = Color(0xFF0F1C2E)
    val surfaceHigh = Color(0xFF13263D)
    val panel = Color(0xFF0B1626)
    val line = Color(0xFF22395A)
    val lineStrong = Color(0xFF3A5578)
    val accent = Color(0xFF3DA9FF)
    val onAccent = Color(0xFF04111F)
    val text = Color(0xFFF2F7FF)
    val textSoft = Color(0xFFC9D6E8)
    val textMuted = Color(0xFFA9BBD3)
    val good = Color(0xFF8EE6B5)
    val warn = Color(0xFFFFCF91)
    val focusRing = Color(0xFFF2F7FF)
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
        Text(label, color = if (selected) Cs.bg else Cs.textSoft, fontSize = if (compact) 13.sp else 14.sp,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun CsButton(label: String, primary: Boolean = false, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Box(modifier.graphicsLayer { alpha = if (enabled) 1f else .45f }.onFocusChanged { focused = it.isFocused }
        .focusRing(focused, shape, if (primary) Cs.accent else Cs.lineStrong)
        .background(if (primary) Cs.accent else if (focused) Color(0xFF1C3654) else Cs.surfaceHigh, shape)
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (primary) Cs.onAccent else Cs.text, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
fun CsSectionTitle(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = Cs.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)

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

/** Portrait poster with focus scale + ring, optional rating and badge. */
@Composable
fun CsPoster(item: TitleItem, width: Dp = 140.dp, badge: String? = null, showTitle: Boolean = true, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(width).graphicsLayer { val s = if (focused) 1.07f else 1f; scaleX = s; scaleY = s },
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }
            .focusRing(focused, shape).clip(shape).background(Cs.surfaceHigh).clickable(onClick = onClick)) {
            AsyncImage(item.image(), item.label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
fun CsPosterGrid(items: List<TitleItem>, posterWidth: Dp = 132.dp, gap: Dp = 14.dp, onClick: (TitleItem) -> Unit) {
    if (items.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth + gap) / (posterWidth + gap)).toInt().coerceAtLeast(2)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item -> key(item.ref) { CsPoster(item, posterWidth) { onClick(item) } } }
                }
            }
        }
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
