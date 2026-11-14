package com.cineglass.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ───────────────────────────── Friends (redesign) ─────────────────────────────
// One screen for people and picks: you on the left (profile, code, add, requests),
// them on the right (recommended to you, your friends, what you sent).

private val avatarColors = listOf(Color(0xFF3DA9FF), Color(0xFFFFA94D), Color(0xFF8EE6B5), Color(0xFFC59BFF), Color(0xFFFF8FA3), Color(0xFF7FDBFF))
private fun avatarColor(seed: String) = avatarColors[(seed.hashCode() and 0x7fffffff) % avatarColors.size]
private fun initials(name: String) = name.trim().split(Regex("\\s+|-")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() }.ifBlank { "?" }
/** "866FD3F961" → "866F D3F9 61": easier to read out across a room. */
private fun groupedCode(code: String) = code.chunked(4).joinToString(" ")
private fun ago(ms: Long): String {
    if (ms <= 0) return ""
    val mins = (System.currentTimeMillis() - ms) / 60_000
    return when { mins < 60 -> "just now"; mins < 1440 -> "${mins / 60}h ago"; mins < 10080 -> "${mins / 1440}d ago"; else -> "${mins / 10080}w ago" }
}

@Composable
fun Avatar(name: String, size: Dp, seed: String = name) {
    Box(Modifier.size(size).clip(CircleShape).background(avatarColor(seed)), contentAlignment = Alignment.Center) {
        Text(initials(name), color = Cs.onAccent, fontSize = (size.value * .38f).sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Styled single-line text field with a TV focus ring. */
@Composable
fun CsTextField(value: String, hint: String, modifier: Modifier = Modifier, mono: Boolean = false, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(value, onChange, modifier.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.lineStrong, shape)
        .background(Cs.surfaceHigh, shape).padding(horizontal = 14.dp, vertical = 12.dp), singleLine = true,
        textStyle = TextStyle(color = Cs.text, fontSize = 17.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default, letterSpacing = if (mono) 2.sp else 0.sp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = Cs.textMuted, fontSize = 16.sp); inner() } })
}

@Composable
fun FriendsScreen(social: SocialRepository, open: (String, Int) -> Unit) {
    val state by social.state.collectAsState()
    var focusFriend by remember { mutableStateOf<String?>(null) }   // uid filter for "Recommended to you"
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Friends", color = Cs.text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            if (state.enabled) {
                FriendStat("${state.friends.size}", if (state.friends.size == 1) "friend" else "friends")
                FriendStat("${state.received.size}", "picks for you")
                if (state.invites.isNotEmpty()) FriendStat("${state.invites.size}", "requests", highlight = true)
            }
        }
        if (!state.enabled) { ConnectingPanel(state.message); return@Column }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            // ── Left: you ──
            Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProfileCard(state, social)
                AddFriendCard(social)
                if (state.invites.isNotEmpty()) CsPanel(Modifier.fillMaxWidth()) {
                    Text("Friend requests", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    state.invites.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Avatar(inv.senderName.ifBlank { "?" }, 36.dp, inv.senderId)
                            Text(inv.senderName.ifBlank { "CineSays user" }, Modifier.weight(1f), color = Cs.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CsButton("Accept", primary = true) { social.accept(inv) }
                            CsButton("Decline") { social.decline(inv) }
                        }
                    }
                }
                if (state.message.isNotBlank()) Text(state.message, color = Cs.accent, fontSize = 13.sp)
            }

            // ── Right: them ──
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val picks = state.received.filter { focusFriend == null || it.senderId == focusFriend }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CsSectionTitle(focusFriend?.let { id -> "From ${state.friends.firstOrNull { it.uid == id }?.name ?: "friend"}" } ?: "Recommended to you", Modifier.weight(1f))
                        if (focusFriend != null) CsChip("Show everyone  ✕", false, compact = true) { focusFriend = null }
                    }
                    if (picks.isEmpty()) EmptyHint(
                        if (state.friends.isEmpty()) "Add a friend with their code, and the movies and shows they send you will appear here."
                        else "Nothing yet. Ask ${state.friends.first().name} to recommend something, or send them a pick from any title's Details page.")
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
                        items(picks, key = { it.id }) { rec -> RecommendationCard(rec, incoming = true) { open(rec.kind, rec.titleId.toInt()) } }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CsSectionTitle("Your friends")
                    if (state.friends.isEmpty()) HowItWorks(state.friendCode)
                    else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.friends.forEach { f ->
                            FriendCard(f, picksFrom = state.received.count { it.senderId == f.uid }, selected = focusFriend == f.uid) {
                                focusFriend = if (focusFriend == f.uid) null else f.uid
                            }
                        }
                    }
                }

                if (state.sent.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CsSectionTitle("You recommended")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp)) {
                        items(state.sent, key = { it.id }) { rec -> RecommendationCard(rec, incoming = false) { open(rec.kind, rec.titleId.toInt()) } }
                    }
                }
            }
        }
    }
}

@Composable private fun FriendStat(value: String, label: String, highlight: Boolean = false) {
    Row(Modifier.padding(start = 18.dp), verticalAlignment = Alignment.Bottom) {
        Text(value, color = if (highlight) Cs.warn else Cs.text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(" $label", color = Cs.textMuted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable private fun EmptyHint(text: String) =
    Text(text, Modifier.fillMaxWidth().border(1.dp, Cs.line, RoundedCornerShape(14.dp)).padding(16.dp), color = Cs.textSoft, fontSize = 15.sp, lineHeight = 22.sp)

@Composable
private fun ConnectingPanel(message: String) {
    CsPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (message.isBlank()) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Cs.accent)
            Text(message.ifBlank { "Connecting to CineSays friends…" }, color = if (message.isBlank()) Cs.textSoft else Cs.warn, fontSize = 16.sp)
        }
        Text("Friends lets you swap movie and show picks with people who also use CineSays on their TV.", color = Cs.textMuted, fontSize = 14.sp)
    }
}

@Composable
private fun ProfileCard(state: SocialState, social: SocialRepository) {
    var editing by remember { mutableStateOf(false) }
    var name by remember(state.name) { mutableStateOf(state.name) }
    CsPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Avatar(state.name, 56.dp, state.friendCode)
            Column(Modifier.weight(1f)) {
                Text(state.name, color = Cs.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("That's you", color = Cs.textMuted, fontSize = 12.sp)
            }
            CsChip(if (editing) "Cancel" else "Edit", false, compact = true) { editing = !editing; name = state.name }
        }
        if (editing) {
            CsTextField(name, "Display name", Modifier.fillMaxWidth()) { name = it.take(40) }
            CsButton("Save name", primary = true) { social.changeName(name); editing = false }
        }
        Text("YOUR FRIEND CODE", color = Cs.textMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(groupedCode(state.friendCode), Modifier.fillMaxWidth().background(Cs.bg, RoundedCornerShape(10.dp)).padding(vertical = 12.dp),
            color = Cs.accent, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, textAlign = TextAlign.Center)
        Text("Share it with a friend; they enter it on their CineSays.", color = Cs.textMuted, fontSize = 12.sp)
    }
}

@Composable
private fun AddFriendCard(social: SocialRepository) {
    var code by remember { mutableStateOf("") }
    CsPanel(Modifier.fillMaxWidth()) {
        Text("Add a friend", color = Cs.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        CsTextField(code, "Their 10-character code", Modifier.fillMaxWidth(), mono = true) { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(10) }
        CsButton(if (code.length == 10) "Send request" else "Send request (${code.length}/10)", primary = code.length == 10, enabled = code.length == 10) {
            social.addByCode(code); code = ""
        }
    }
}

@Composable
private fun FriendCard(friend: CineFriend, picksFrom: Int, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.width(150.dp).onFocusChanged { focused = it.isFocused }
        .border(if (focused) 3.dp else if (selected) 2.dp else 1.dp, if (focused) Cs.focusRing else if (selected) Cs.accent else Cs.line, shape)
        .background(if (selected) Color(0xFF0A3A6E) else Cs.surface, shape).clickable(onClick = onClick).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(friend.name, 64.dp, friend.uid)
        Text(friend.name.ifBlank { "Friend" }, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when (picksFrom) { 0 -> "No picks yet"; 1 -> "1 pick for you"; else -> "$picksFrom picks for you" }, color = Cs.textMuted, fontSize = 12.sp)
        if (focused) Text(if (selected) "Select: show all" else "Select: their picks", color = Cs.accent, fontSize = 11.sp)
    }
}

@Composable
private fun RecommendationCard(rec: CineRecommendation, incoming: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.width(150.dp).graphicsLayer { val s = if (focused) 1.06f else 1f; scaleX = s; scaleY = s }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { focused = it.isFocused }
            .border(if (focused) 3.dp else 1.dp, if (focused) Cs.focusRing else Cs.line, shape).clip(shape).background(Cs.surfaceHigh).clickable(onClick = onClick)) {
            AsyncImage(rec.poster.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/w342$it" }, rec.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Incoming: who sent it. Sent: who it went to.
            val who = if (incoming) rec.senderName.ifBlank { "Friend" } else rec.recipientName.ifBlank { "Friend" }
            Row(Modifier.align(Alignment.BottomStart).padding(6.dp).background(Cs.bg.copy(alpha = .85f), RoundedCornerShape(999.dp)).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Avatar(who, 22.dp, if (incoming) rec.senderId else who)
                Text(if (incoming) who else "To $who", color = Cs.text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Text(rec.title, color = if (focused) Cs.text else Cs.textSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (rec.note.isNotBlank()) Text("“${rec.note}”", color = Cs.textMuted, fontSize = 12.sp, maxLines = if (focused) 4 else 2, overflow = TextOverflow.Ellipsis)
        Text(ago(rec.createdAt), color = Cs.textMuted, fontSize = 11.sp)
    }
}

@Composable
private fun HowItWorks(code: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            "1" to "Share your code" to groupedCode(code),
            "2" to "Enter theirs" to "Use Add a friend",
            "3" to "Swap picks" to "Recommend from any title"
        ).forEach { (head, body) ->
            Column(Modifier.weight(1f).border(1.dp, Cs.line, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(28.dp).background(Cs.accent, CircleShape), contentAlignment = Alignment.Center) { Text(head.first, color = Cs.onAccent, fontWeight = FontWeight.Bold) }
                Text(head.second, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(body, color = Cs.textMuted, fontSize = 13.sp)
            }
        }
    }
}

// ───────────────────────────── Recommend dialog ─────────────────────────────

@Composable
fun RecommendToFriendsDialog(item: TitleItem, social: SocialRepository, dismiss: () -> Unit) {
    val state by social.state.collectAsState()
    var selected by remember(item.id) { mutableStateOf(setOf<String>()) }
    var note by remember(item.id) { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().background(Cs.panel, RoundedCornerShape(22.dp)).border(1.dp, Cs.lineStrong, RoundedCornerShape(22.dp)).padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        AsyncImage(item.image(), item.label, Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Cs.surfaceHigh), contentScale = ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("RECOMMEND", color = Cs.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(item.label, color = Cs.text, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!state.enabled) Text(state.message.ifBlank { "Connecting to friends…" }, color = Cs.warn)
            else if (state.friends.isEmpty()) Text("Add friends on the Friends tab first.", color = Cs.warn)
            else {
                Text("Send to", color = Cs.textMuted, fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.friends.forEach { f ->
                        val on = f.uid in selected
                        var focused by remember { mutableStateOf(false) }
                        Row(Modifier.onFocusChanged { focused = it.isFocused }
                            .border(if (focused) 3.dp else if (on) 2.dp else 1.dp, if (focused) Cs.focusRing else if (on) Cs.accent else Cs.line, RoundedCornerShape(999.dp))
                            .background(if (on) Color(0xFF0A3A6E) else Cs.surface, RoundedCornerShape(999.dp))
                            .clickable { selected = if (on) selected - f.uid else selected + f.uid }.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Avatar(f.name, 30.dp, f.uid)
                            Text((if (on) "✓ " else "") + f.name, color = Cs.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                CsTextField(note, "Add a note (optional), e.g. “You'll love the ending”", Modifier.fillMaxWidth()) { note = it.take(280) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CsButton(when (selected.size) { 0 -> "Send"; 1 -> "Send to 1 friend"; else -> "Send to ${selected.size} friends" }, primary = selected.isNotEmpty(), enabled = selected.isNotEmpty()) {
                    social.recommend(item, selected, note); dismiss()
                }
                CsButton("Cancel") { dismiss() }
            }
        }
    }
}
