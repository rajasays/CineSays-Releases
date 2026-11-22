package com.cineglass.tv

import java.util.Locale

/** Display-only hints from the provider, not probed/verified media properties. */
internal object SourceDetails {
    private fun match(text: String, pattern: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.value

    fun heading(stream: PlayableStream): String {
        val quality = stream.resolution ?: match(stream.sourceName + " " + stream.label.orEmpty(),
            "\\b(?:2160p|1080p|720p|480p|4K|8K)\\b")
        return quality?.let { "$it source" } ?: "Media source"
    }

    fun description(stream: PlayableStream): String? = stream.label
        ?.removePrefix(stream.sourceName)?.trimStart(' ', '·', '\n', '\r')?.takeIf { it.isNotBlank() }

    /** Structured bytes win; otherwise interpret the provider's stated size for display ordering. */
    fun sizeForSorting(stream: PlayableStream): Long? {
        stream.sizeBytes?.takeIf { it > 0 }?.let { return it }
        val text = stream.label.orEmpty()
        val found = Regex("\\b(\\d+(?:[.,]\\d+)?)\\s*(GiB|MiB|KiB|GB|MB|KB)\\b", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val amount = found.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val multiplier = when (found.groupValues[2].lowercase(Locale.ROOT)) {
            "gib" -> 1073741824.0
            "mib" -> 1048576.0
            "kib" -> 1024.0
            "gb" -> 1_000_000_000.0
            "mb" -> 1_000_000.0
            else -> 1000.0
        }
        val bytes = amount * multiplier
        return bytes.takeIf { it.isFinite() && it >= 1 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }

    fun summary(stream: PlayableStream): String {
        val text = listOfNotNull(stream.sourceName, stream.label, stream.debridTorrent?.filename).joinToString("\n")
        val size = stream.sizeBytes?.let {
            if (it >= 1L shl 30) String.format(Locale.US, "%.2f GiB", it / (1L shl 30).toDouble())
            else String.format(Locale.US, "%.0f MiB", it / (1L shl 20).toDouble())
        } ?: match(text, "\\b\\d+(?:[.,]\\d+)?\\s*(?:GiB|MiB|GB|MB)\\b")
        val quality = stream.resolution ?: match(text, "\\b(?:2160p|1080p|720p|480p|4K|8K)\\b")
        val filename = stream.debridTorrent?.filename
        val extension = filename?.substringAfterLast('.', "")?.lowercase()?.takeIf {
            it in setOf("mkv", "mp4", "avi", "webm", "m4v", "mov", "ts", "m2ts", "ogv", "ogg")
        } ?: match(text, "\\b(?:MKV|MP4|AVI|WEBM|M4V|MOV|M2TS)\\b")
        val codec = stream.videoCodec ?: match(text, "\\b(?:HEVC|AVC|AV1|x265|x264|H[ .]?265|H[ .]?264)\\b")
        val audio = stream.audio ?: match(text, "\\b(?:DTS(?:[- .]HD)?|DDP(?:[ .]?\\d[.]\\d)?|E[- .]?AC3|AC3|AAC|TrueHD|Atmos)\\b")
        val hdr = stream.hdr ?: match(text, "\\b(?:HDR10\\+?|HDR|Dolby[ .]Vision|DV)\\b")
        val languages = linkedSetOf<String>()
        listOf("English", "Hindi", "Urdu", "Tamil", "Telugu", "Malayalam", "Kannada", "Bengali", "Punjabi",
            "French", "Spanish", "German", "Italian", "Portuguese", "Russian", "Japanese", "Korean", "Chinese", "Arabic", "Turkish")
            .filterTo(languages) { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        mapOf("🇬🇧" to "English", "🇺🇸" to "English", "🇫🇷" to "French", "🇪🇸" to "Spanish",
            "🇩🇪" to "German", "🇮🇹" to "Italian", "🇯🇵" to "Japanese", "🇰🇷" to "Korean",
            "🇷🇺" to "Russian", "🇵🇹" to "Portuguese", "🇧🇷" to "Portuguese").forEach { (flag, language) ->
            if (flag in text) languages += language
        }
        // Do not guess a language from a country with multiple common languages (e.g. India).
        if (Regex("\\b(?:MULTI|Dual[ .]Audio)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) languages += "Multiple audio tracks reported"
        val facts = listOf("Quality" to quality, "Size" to size, "Format" to extension?.uppercase(),
            "Codec" to codec, "Audio" to audio, "HDR" to hdr,
            "Language" to languages.takeIf { it.isNotEmpty() }?.joinToString(", "))
        return "Source-reported details\n" + facts.joinToString("  ·  ") { (key, value) -> "$key: ${value ?: "Not reported"}" }
    }
}
