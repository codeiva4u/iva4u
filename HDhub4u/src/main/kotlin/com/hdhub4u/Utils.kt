package com.hdhub4u

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import org.json.JSONObject


// Pre-compiled regex for better performance
private val REDIRECT_REGEX = Regex("s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'")

suspend fun getRedirectLinks(url: String): String {
    // Early return for invalid or direct links
    if (url.isBlank() || !url.startsWith("http")) return url
    if (!url.contains("?")) return url
    
    return try {
        val doc = app.get(url, timeout = 15).toString()
    val combinedString = buildString {
        REDIRECT_REGEX.findAll(doc).forEach { matchResult ->
            val extractedValue = matchResult.groups[1]?.value ?: matchResult.groups[2]?.value
            if (!extractedValue.isNullOrEmpty()) append(extractedValue)
        }
    }
        
        // Fast path for empty combined string
        if (combinedString.isBlank()) return url
        
        val decodedString = base64Decode(pen(base64Decode(base64Decode(combinedString))))
        val jsonObject = JSONObject(decodedString)
        val encodedurl = base64Decode(jsonObject.optString("o", "")).trim()
        
        // Return immediately if we have a valid encoded URL
        if (encodedurl.isNotBlank() && encodedurl.startsWith("http")) {
            return encodedurl
        }
        
        val data = encode(jsonObject.optString("data", "")).trim()
        val wphttp1 = jsonObject.optString("blog_url", "").trim()
        val directlink = runCatching {
            app.get("$wphttp1?re=$data".trim(), timeout = 10).document.select("body").text().trim()
        }.getOrDefault("").trim()

        encodedurl.ifEmpty { directlink.ifEmpty { url } }
    } catch (e: Exception) {
        Log.e("LinkResolver", "Error processing links: ${e.message}")
        url // Return original URL on failure
    }
}

@SuppressLint("NewApi")
fun encode(value: String): String {
    return String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
}

fun pen(value: String): String {
    return value.map {
        when (it) {
            in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> it
        }
    }.joinToString("")
}


fun cleanTitle(title: String): String {
    val parts = title.split(".", "-", "_")

    val qualityTags = listOf(
        "WEBRip", "WEB-DL", "WEB", "BluRay", "HDRip", "DVDRip", "HDTV",
        "CAM", "TS", "R5", "DVDScr", "BRRip", "BDRip", "DVD", "PDTV",
        "HD"
    )

    val audioTags = listOf(
        "AAC", "AC3", "DTS", "MP3", "FLAC", "DD5", "EAC3", "Atmos"
    )

    val subTags = listOf(
        "ESub", "ESubs", "Subs", "MultiSub", "NoSub", "EnglishSub", "HindiSub"
    )

    val codecTags = listOf(
        "x264", "x265", "H264", "HEVC", "AVC"
    )

    val startIndex = parts.indexOfFirst { part ->
        qualityTags.any { tag -> part.contains(tag, ignoreCase = true) }
    }

    val endIndex = parts.indexOfLast { part ->
        subTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                audioTags.any { tag -> part.contains(tag, ignoreCase = true) } ||
                codecTags.any { tag -> part.contains(tag, ignoreCase = true) }
    }

    return if (startIndex != -1 && endIndex != -1 && endIndex >= startIndex) {
        parts.subList(startIndex, endIndex + 1).joinToString(".")
    } else if (startIndex != -1) {
        parts.subList(startIndex, parts.size).joinToString(".")
    } else {
        parts.takeLast(3).joinToString(".")
    }
}


data class Domains(
    @JsonProperty("HDHUB4u")
    val hdhub4u: String,
    @JsonProperty("4khdhub")
    val n4khdhub: String,
)

// Simple cache for frequent requests
private val linkCache = mutableMapOf<String, String>()

// Fast link validation
fun isValidStreamingLink(url: String): Boolean {
    if (url.isBlank()) return false
    return url.startsWith("http") && 
           (url.contains("hubdrive") || url.contains("hubcloud") || 
            url.contains("hdstream4u") || url.contains("hubstream") ||
            url.contains("taazabull24"))
}

// Optimized quality extraction
fun extractQualityFromText(text: String): Int {
    val lowercaseText = text.lowercase()
    return when {
        "2160" in lowercaseText || "4k" in lowercaseText -> 2160
        "1080" in lowercaseText -> 1080
        "720" in lowercaseText -> 720
        "480" in lowercaseText -> 480
        else -> -1
    }
}

suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    // Skip invalid URLs early
    if (!isValidStreamingLink(url)) return
    
    loadExtractor(url, referer, subtitleCallback) { link ->
        CoroutineScope(Dispatchers.IO).launch {
            callback.invoke(
                newExtractorLink(
                    "$source[${link.source}]",
                    "$source[${link.source}]",
                    link.url,
                ) {
                    this.quality = link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    }
}
