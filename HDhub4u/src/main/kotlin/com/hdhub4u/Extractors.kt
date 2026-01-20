package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// ==================== UTILITY FUNCTIONS ====================

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

fun getBaseUrl(url: String): String {
    return URI(url).let { "${it.scheme}://${it.host}" }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ROT13 decoder
fun String.rot13(): String {
    return this.map { char ->
        when (char) {
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> char
        }
    }.joinToString("")
}

// GadgetsWeb URL decoder: Base64 -> ROT13 -> Base64
fun decodeGadgetsWebUrl(encodedId: String): String? {
    return try {
        val firstDecode = String(android.util.Base64.decode(encodedId, android.util.Base64.DEFAULT))
        val rot13Applied = firstDecode.rot13()
        val finalUrl = String(android.util.Base64.decode(rot13Applied, android.util.Base64.DEFAULT))
        if (finalUrl.startsWith("http")) finalUrl else null
    } catch (_: Exception) { null }
}

// Cached URLs JSON
private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
        } catch (_: Exception) {
            return getBaseUrl(url)
        }
    }
    val link = cachedUrlsJson?.optString(source)
    return if (link.isNullOrEmpty()) getBaseUrl(url) else link
}

// ==================== HUBDRIVE EXTRACTOR ====================

class HubDrive : ExtractorApi() {
    override val name = "HubDrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, allowRedirects = true).document
            
            // Find HubCloud button and route to HubCloud extractor
            doc.select("a.btn").amap { button ->
                val href = button.attr("href")
                val text = button.text()
                
                if (href.isNotBlank() && (
                    text.contains("HubCloud", ignoreCase = true) ||
                    href.contains("hubcloud", ignoreCase = true))) {
                    HubCloud().getUrl(href, url, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e("HubDrive", "Error: ${e.message}")
        }
    }
}

// ==================== HUBCLOUD EXTRACTOR ====================
// EXACT COPY FROM MOVIESDRIVE - PROVEN TO WORK

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "hubcloud")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        val doc = app.get(newUrl).document
        var link = if (newUrl.contains("drive")) {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        } else {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        }

        if (!link.startsWith("https://")) {
            link = latestUrl + link
        }

        val document = app.get(link).document
        val div = document.selectFirst("div.card-body")
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val baseQuality = getIndexQuality(header)

        div?.select("h2 a.btn")?.amap {
            val btnLink = it.attr("href")
            val text = it.text()

            if (text.contains("[FSL Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSL Server]",
                        "$name[FSL Server] $header[$size]",
                        btnLink,
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("[FSLv2 Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSLv2 Server]",
                        "$name[FSLv2 Server] $header[$size]",
                        btnLink,
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("[Mega Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[Mega Server]",
                        "$name[Mega Server] $header[$size]",
                        btnLink,
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("Download File")) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name $header[$size]",
                        btnLink,
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false).headers["hx-redirect"] ?: ""
                val buzzBaseUrl = getBaseUrl(btnLink)
                if (dlink.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            "$name[BuzzServer]",
                            "$name[BuzzServer] $header[$size]",
                            buzzBaseUrl + dlink,
                        ) {
                            this.quality = baseQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }

            else if (btnLink.contains("pixeldra")) {
                val pixelBaseUrl = getBaseUrl(btnLink)
                val finalURL = if (btnLink.contains("download", true)) btnLink
                else "$pixelBaseUrl/api/file/${btnLink.substringAfterLast("/")}?download"

                callback.invoke(
                    newExtractorLink(
                        "Pixeldrain",
                        "Pixeldrain $header[$size]",
                        finalURL,
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else if (text.contains("Download [Server : 10Gbps]")) {
                val dlink = app.get(btnLink, allowRedirects = false).headers["location"] ?: ""
                callback.invoke(
                    newExtractorLink(
                        "$name[Download]",
                        "$name[Download] $header[$size]",
                        dlink.substringAfter("link="),
                    ) {
                        this.quality = baseQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else
            {
                if (!btnLink.contains(".zip") && (btnLink.contains(".mkv") || btnLink.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header[$size]",
                            btnLink,
                        ) {
                            this.quality = baseQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
        }
    }
}
