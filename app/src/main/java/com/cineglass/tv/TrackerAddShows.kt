package com.cineglass.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Build a tracker without an import: answer Watching / Watched it / Skip on popular series. */
@Composable
fun AddShowsPanel(state: TrackerUiState, vm: TrackerViewModel) {
    LaunchedEffect(Unit) { vm.loadPicker() }
    val columns = if (LocalCompact.current) 1 else 4
    Text("Build your tracker", color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Tell CineSays which shows you watch. “Watching” follows the show; “Watched it” also marks every aired episode as seen.",
        color = Cs.textSoft, fontSize = 14.sp)
    if (state.pickerBusy && state.picker.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Cs.accent)
    state.picker.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { item ->
                Column(Modifier.weight(1f).border(1.dp, Cs.line, RoundedCornerShape(14.dp)).background(Cs.surface, RoundedCornerShape(14.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AsyncImage(item.image("w185"), item.label, Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(8.dp)).background(Cs.surfaceHigh),
                            contentScale = ContentScale.Crop)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(item.label, color = Cs.text, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(item.year, if (item.rating > 0) "★ ${"%.1f".format(item.rating)}" else "").filter { it.isNotBlank() }.joinToString(" · "),
                                color = Cs.textMuted, fontSize = 12.sp)
                        }
                    }
                    CsButton("Watching", primary = true, modifier = Modifier.fillMaxWidth()) { vm.answerPicker(item, "watching") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CsButton("Watched it", modifier = Modifier.weight(1f)) { vm.answerPicker(item, "watched") }
                        CsButton("Skip", modifier = Modifier.weight(1f)) { vm.answerPicker(item, "skip") }
                    }
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (state.picker.isNotEmpty()) CsButton(if (state.pickerBusy) "Loading…" else "Show more series") { vm.loadPicker(more = true) }
    Text("Looking for something specific? Use My Shows › Search TV Shows, or + Track episodes on any series' details.", color = Cs.textMuted, fontSize = 12.sp)
}
