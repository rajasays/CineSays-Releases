package com.cineglass.tv

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// ───────────────────────────── Stremio add-on protocol (parsing only) ─────────────────────────────
// Implements the documented protocol: GET {base}/manifest.json, and GET {base}/stream/{type}/{id}.json
// returning {"streams":[…]}. Each stream object may carry url, ytId, infoHash (+fileIdx), externalUrl,
// name, title/description, subtitles[] and behaviorHints. CineSays treats add-on output as untrusted
// data: nothing is executed, every field is type-checked, and a stream is only playable when it is an
// https URL on a host the CineSays add-on list allows for that add-on. BitTorrent results (infoHash /
// magnet) are recognised. Approved torrent hashes can use free local or home-server playback.

data class StremioManifest(
    val id: String, val name: String, val version: String,
    val types: List<String>, val streamTypes: List<String>, val idPrefixes: List<String>
) {
    fun servesStreams(type: String, id: String): Boolean =
        (streamTypes.isEmpty() || type in streamTypes) && (idPrefixes.isEmpty() || idPrefixes.any { id.startsWith(it) })
}

object StremioProtocol {
    const val MAX_STREAMS = 60

    /** The add-on's base URL: the manifest URL without "/manifest.json". */
    fun baseOf(manifestUrl: String): String = manifestUrl.trim().removeSuffix("/manifest.json").trimEnd('/')

    /** {base}/stream/{type}/{id}.json, with the id percent-encoded as a path segment. */
    fun streamUrl(base: String, type: String, id: String): String =
        "$base/stream/${enc(type)}/${enc(id)}.json"

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")

    /** Parses and validates a manifest; null when it isn't a usable stream add-on. */
    fun parseManifest(json: String): StremioManifest? = runCatching {
        val o = JsonParser.parseString(json).asJsonObject
        val id = o.str("id") ?: return null
        val name = o.str("name") ?: return null
        val version = o.str("version") ?: "0"
        val types = o.strings("types")
        val prefixes = o.strings("idPrefixes")
        // resources: ["stream", …] or [{"name":"stream","types":[…],"idPrefixes":[…]}, …]
        var streamTypes: List<String>? = null
        var streamPrefixes: List<String> = prefixes
        o.arr("resources")?.forEach { r ->
            when {
                r.isJsonPrimitive && r.asString == "stream" -> streamTypes = types
                r.isJsonObject && r.asJsonObject.str("name") == "stream" -> {
                    val ro = r.asJsonObject
                    streamTypes = ro.strings("types").ifEmpty { types }
                    ro.strings("idPrefixes").takeIf { it.isNotEmpty() }?.let { streamPrefixes = it }
                }
            }
        }
        val st = streamTypes ?: return null // no stream resource
        StremioManifest(id, name, version, types, st, streamPrefixes)
    }.getOrNull()

    /**
     * Parses a stream response. [allowedHosts] are the hosts the CineSays add-on list permits for this
     * add-on; anything else is listed as unsupported with the reason.
     */
    fun parseStreams(json: String, addonId: String, addonName: String, allowedHosts: Collection<String>, authorizedHashes: Set<String> = emptySet(), allowTorrentStreams: Boolean = false): List<PlayableStream> {
        val arr = runCatching { JsonParser.parseString(json).asJsonObject.arr("streams") }.getOrNull() ?: return emptyList()
        return arr.take(MAX_STREAMS).mapNotNull { e -> if (e.isJsonObject) runCatching { parseStream(e.asJsonObject, addonId, addonName, allowedHosts, authorizedHashes, allowTorrentStreams) }.getOrNull() else null }
    }

    private fun parseStream(o: JsonObject, addonId: String, addonName: String, allowedHosts: Collection<String>, authorizedHashes: Set<String>, allowTorrentStreams: Boolean): PlayableStream? {
        val name = o.str("name")?.take(80)
        val title = (o.str("title") ?: o.str("description"))?.take(if (allowTorrentStreams) 2000 else 200)
        val label = listOfNotNull(name, title).joinToString(" · ").ifBlank { null }
        val hints = o.obj("behaviorHints")
        val size = hints?.get("videoSize")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0 }
        val subs = o.arr("subtitles")?.mapNotNull { s ->
            val so = s.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val u = so.str("url")?.takeIf { StreamUrls.allowed(it, allowedHosts) } ?: return@mapNotNull null
            SubtitleTrack(u, so.str("lang"), so.str("id") ?: so.str("lang"), subtitleMime(u))
        }.orEmpty()
        fun stream(url: String?, kind: StreamKind, playable: Boolean, reason: String?) = PlayableStream(
            providerId = addonId, providerName = addonName, sourceName = name ?: addonName, url = url, kind = kind,
            label = label, sizeBytes = size, subtitles = if (playable) subs else emptyList(),
            trusted = true, playable = playable, unsupportedReason = reason)

        val url = o.str("url")
        val infoHash = o.str("infoHash")
        val torrent = if (authorizedHashes.isNotEmpty() || allowTorrentStreams) {
            val hash = infoHash ?: url?.takeIf { it.startsWith("magnet:?", true) }
                ?.substringAfter('?')?.split('&')?.firstOrNull { it.startsWith("xt=urn:btih:", true) }
                ?.substringAfterLast(':')
            val index = o.get("fileIdx")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asString?.toIntOrNull()?.takeIf { it >= 0 }
            hash?.lowercase()?.takeIf { Regex("[0-9a-f]{40}").matches(it) && (allowTorrentStreams || it in authorizedHashes) && (!o.has("fileIdx") || o.get("fileIdx").isJsonNull || index != null) }
                ?.let { DebridTorrent(it, index, hints?.str("filename")?.take(512), directPlayback = (allowTorrentStreams || it in authorizedHashes), debridPlayback = false, trackers = approvedTrackers(o, allowedHosts)) }
        } else null
        if (torrent != null && (url == null || url.startsWith("magnet:", true))) {
            return stream(null, StreamKind.TORRENT, false, null).copy(debridTorrent = torrent)
        }
        val ytId = o.str("ytId")
        val external = o.str("externalUrl")
        return when {
            url != null && url.startsWith("magnet:", true) -> stream(null, StreamKind.TORRENT, false, "BitTorrent source is missing a valid hash or file index, or its hash is not approved by this add-on")
            url != null -> when {
                !StreamUrls.isHttps(url) -> stream(null, StreamKind.UNKNOWN, false, "Not a secure (https) link")
                !StreamUrls.allowed(url, allowedHosts) -> stream(null, StreamKind.UNKNOWN, false, "Source isn't on this add-on's approved list")
                hints?.get("proxyHeaders") != null -> stream(null, StreamKind.UNKNOWN, false, "Needs custom request headers, which CineSays doesn't send")
                else -> stream(url, StreamUrls.kindOf(url), true, null)
            }
            infoHash != null -> stream(null, StreamKind.TORRENT, false, "BitTorrent source is missing a valid hash or file index, or its hash is not approved by this add-on")
            ytId != null -> stream(null, StreamKind.YOUTUBE, false, "YouTube video: open it from the title's trailer instead")
            external != null -> stream(null, StreamKind.EXTERNAL, false, "Opens on another website")
            else -> null
        }
    }

    private fun approvedTrackers(stream: JsonObject, allowedHosts: Collection<String>): List<String> =
        stream.arr("sources")?.take(32)?.mapNotNull { source ->
            if (!source.isJsonPrimitive || !source.asJsonPrimitive.isString) return@mapNotNull null
            val hint = source.asString
            if (!hint.startsWith("tracker:") || hint.length > 2048) return@mapNotNull null
            val tracker = hint.removePrefix("tracker:")
            val uri = runCatching { java.net.URI(tracker) }.getOrNull() ?: return@mapNotNull null
            val host = uri.host?.lowercase() ?: return@mapNotNull null
            tracker.takeIf {
                uri.scheme?.lowercase() in setOf("https", "udp") &&
                    uri.rawUserInfo == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535) &&
                    allowedHosts.any { approved -> approved.equals(host, ignoreCase = true) }
            }
        }?.distinct()?.take(8).orEmpty()

    fun subtitleMime(url: String): String? {
        val p = runCatching { java.net.URI(url).path }.getOrNull().orEmpty().lowercase()
        return when {
            p.endsWith(".vtt") -> "text/vtt"
            p.endsWith(".srt") -> "application/x-subrip"
            p.endsWith(".ttml") || p.endsWith(".dfxp") -> "application/ttml+xml"
            p.endsWith(".ass") || p.endsWith(".ssa") -> "text/x-ssa"
            else -> null
        }
    }

    private fun JsonObject.str(k: String): String? = get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.ifEmpty { null }
    private fun JsonObject.arr(k: String): JsonArray? = get(k)?.takeIf { it.isJsonArray }?.asJsonArray
    private fun JsonObject.obj(k: String): JsonObject? = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.strings(k: String): List<String> = arr(k)?.mapNotNull { e: JsonElement -> e.takeIf { it.isJsonPrimitive }?.asString } .orEmpty()
}
