package com.cineglass.tv

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───────────────────────────── Settings › Themes ─────────────────────────────

@Composable
fun ThemesSection() {
    val context = LocalContext.current
    Text("Themes", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Cs.titleFont)
    Text("Change how CineSays looks on this device. Everything works the same in every theme.", color = Cs.textSoft, fontSize = 14.sp)
    val perRow = if (LocalCompact.current) 2 else 4
    CsTheme.entries.chunked(perRow).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { t -> ThemeCard(t, t == Cs.theme, Modifier.weight(1f)) { Cs.choose(context, t) } }
            repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** One theme: a small preview drawn in that theme's own colours, its name and what it changes. */
@Composable
private fun ThemeCard(t: CsTheme, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val p = Cs.previewOf(t)
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else if (selected) 2.dp else 1.dp, if (focused) Cs.focusRing else if (selected) Cs.accent else Cs.line, shape)
        .clip(shape).background(Cs.surface).clickable(onClick = onClick).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Preview: page, a banner block, a button and a row of cards, in the theme's colours.
        Column(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp)).background(p.bg).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (t == CsTheme.FOCUS) Box(Modifier.size(6.dp, 34.dp).background(p.surfaceHigh, RoundedCornerShape(3.dp)))
                Column(Modifier.weight(1f).height(if (t == CsTheme.CINEMATIC) 44.dp else 34.dp).background(p.surfaceHigh, RoundedCornerShape(if (t == CsTheme.CINEMATIC) 2.dp else 6.dp)).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Title", color = p.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp,
                        fontFamily = if (t == CsTheme.EDITORIAL) FontFamily.Serif else null)
                    Box(Modifier.size(26.dp, 8.dp).background(p.accent, RoundedCornerShape(if (t == CsTheme.EDITORIAL) 8.dp else 2.dp)))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(4) { i -> Box(Modifier.weight(1f).height(18.dp).background(if (i == 0 && t == CsTheme.FOCUS) p.lineStrong else p.surface, RoundedCornerShape(3.dp))) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.label, Modifier.weight(1f), color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (selected) Text("✓ In use", color = Cs.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Text(t.blurb, color = Cs.textMuted, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────────── Focus theme: side rail (TV) ─────────────────────────────

/** One destination in the Focus theme's side rail. */
data class RailEntry(val name: String, val icon: String, val label: String, val modifier: Modifier, val onClick: () -> Unit)

/**
 * Slim icon rail on the left (Focus theme, TV only). It opens to show names while the remote is on it,
 * so the screen keeps its full height for content.
 */
@Composable
fun SideRail(entries: List<RailEntry>, selected: String, onFocusChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (open) 196.dp else 68.dp, label = "rail")
    Column(Modifier.fillMaxHeight().width(width).background(Cs.panel).onFocusChanged { open = it.hasFocus; onFocusChange(it.hasFocus) }
        .padding(vertical = 18.dp, horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(if (open) "CineSays" else "CS", Modifier.padding(start = 8.dp, bottom = 12.dp), color = Cs.text, fontSize = if (open) 18.sp else 15.sp,
            fontWeight = FontWeight.ExtraBold, maxLines = 1)
        entries.forEach { e ->
            if (e.name == "Search") Spacer(Modifier.weight(1f))
            RailItem(e, e.name == selected, open)
        }
    }
}

@Composable
private fun RailItem(e: RailEntry, selected: Boolean, open: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Row(e.modifier.fillMaxWidth().height(44.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) Cs.focusRing else Color.Transparent, shape)
        .background(if (selected) Cs.buttonFocus else Color.Transparent, shape).clickable(onClick = e.onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(e.icon, Modifier.width(24.dp), color = if (selected) Cs.accent else Cs.textSoft, fontSize = 18.sp, maxLines = 1)
        if (open) Text(e.label, color = if (selected || focused) Cs.text else Cs.textSoft, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
