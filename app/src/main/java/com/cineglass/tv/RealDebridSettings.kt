package com.cineglass.tv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RealDebridSettings() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(DebridCredentials.configured(context)) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    CsChip("Real-Debrid", editing, compact = true) { if (!busy) editing = !editing }
    if (editing) {
        var token by remember { mutableStateOf("") }
        Text("Use a personal API token from real-debrid.com/apitoken. Use only add-ons and media you’re authorized to access.",
            color = Cs.textSoft, fontSize = 13.sp)
        Text(if (configured) "API token saved" else "No API token saved", color = Cs.textMuted, fontSize = 13.sp)
        OutlinedTextField(token, { if (it.length <= 256) token = it }, singleLine = true, enabled = !busy,
            label = { Text("Real-Debrid API token") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Row {
            CsButton("Save token") {
                if (!busy) {
                    val value = token.trim()
                    if (!RealDebrid.validToken(value)) {
                        error = "Enter a valid Real-Debrid API token."
                    } else {
                        busy = true
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { DebridCredentials.save(context, value) }
                            configured = DebridCredentials.configured(context)
                            error = if (saved) null else "Couldn't save the API token."
                            if (saved) token = ""
                            busy = false
                        }
                    }
                }
            }
            if (configured) CsButton("Remove token") {
                if (!busy) {
                    busy = true
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { DebridCredentials.clear(context) }
                        configured = DebridCredentials.configured(context)
                        error = if (removed) null else "Couldn't remove the API token."
                        if (removed) token = ""
                        busy = false
                    }
                }
            }
        }
        error?.let { Text(it, color = Cs.warn, fontSize = 13.sp) }
    }
}
