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

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ROT13 decoder for GadgetsWeb bypass
fun String.rot13(): String {
    return this.map { char ->
        when (char) {
            in 'A'..'Z' -> ((char - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((char - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> char
        }
    }.joinToString("")
}

// GadgetsWeb URL decoder: Base64 -> ROT13 -> Base64 = Final URL
fun decodeGadgetsWebUrl(encodedId: String): String? {
    return try {
        val firstDecode = String(android.util.Base64.decode(encodedId, android.util.Base64.DEFAULT))
        val rot13Applied = firstDecode.rot13()
        val finalUrl = String(android.util.Base64.decode(rot13Applied, android.util.Base64.DEFAULT))
        if (finalUrl.startsWith("http")) finalUrl else null
    } catch (_: Exception) {
        null
    }
}

// Cached URLs for session-level caching
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
            val response = app.get(url, allowRedirects = true)
            val document = response.document
            
            // Find HubCloud button
            document.select("a.btn").amap { button ->
                val href = button.attr("href")
                val text = button.text()
                
                if (href.isNotBlank() && (text.contains("HubCloud", ignoreCase = true) ||
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
// Based on MoviesDrive implementation - proven to work

class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestUrl = getLatestUrl(url, "hubcloud")
            val baseUrl = getBaseUrl(url)
            val newUrl = url.replace(baseUrl, latestUrl)
            
            val doc = app.get(newUrl).document
            
            // Extract link from script tag OR #download button
            var link = if (newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }
            
            // If script method didn't work, try #download button
            if (link.isBlank()) {
                link = doc.selectFirst("#download, a#download")?.attr("href") ?: ""
            }
            
            if (link.isBlank()) return
            
            if (!link.startsWith("https://")) {
                link = latestUrl + link
            }
            
            // Get the final page with download buttons
            val document = app.get(link, allowRedirects = true).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)
            val sizeLabel = if (size.isNotEmpty()) "[$size]" else ""
            
            // Extract all download buttons
            div?.select("h2 a.btn, a.btn")?.amap { btn ->
                val btnLink = btn.attr("href")
                val text = btn.text()
                
                if (btnLink.isBlank() || btnLink.contains("google.com/search")) return@amap
                
                when {
                    text.contains("[FSL Server]") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[FSL]",
                                "$name[FSL] $header $sizeLabel",
                                btnLink
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("[FSLv2 Server]") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[FSLv2]",
                                "$name[FSLv2] $header $sizeLabel",
                                btnLink
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("[Mega Server]") -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[Mega]",
                                "$name[Mega] $header $sizeLabel",
                                btnLink
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("Download File") -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header $sizeLabel",
                                btnLink
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("BuzzServer") -> {
                        try {
                            val dlink = app.get("$btnLink/download", referer = btnLink, allowRedirects = false)
                                .headers["hx-redirect"] ?: ""
                            if (dlink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[BuzzServer]",
                                        "$name[BuzzServer] $header $sizeLabel",
                                        getBaseUrl(btnLink) + dlink
                                    ) { this.quality = quality }
                                )
                            }
                        } catch (_: Exception) { }
                    }
                    btnLink.contains("pixeldra") -> {
                        val pixelBaseUrl = getBaseUrl(btnLink)
                        val finalURL = if (btnLink.contains("download", true)) btnLink
                        else "$pixelBaseUrl/api/file/${btnLink.substringAfterLast("/")}"
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $header $sizeLabel",
                                finalURL
                            ) { this.quality = quality }
                        )
                    }
                    text.contains("Download [Server : 10Gbps]") -> {
                        val dlink = app.get(btnLink, allowRedirects = false).headers["location"] ?: ""
                        callback.invoke(
                            newExtractorLink(
                                "$name[10Gbps]",
                                "$name[10Gbps] $header $sizeLabel",
                                dlink.substringAfter("link=")
                            ) { this.quality = quality }
                        )
                    }
                    else -> {
                        // Fallback: if it's a video link, add it
                        if (!btnLink.contains(".zip") && (btnLink.contains(".mkv") || btnLink.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $header $sizeLabel",
                                    btnLink
                                ) { this.quality = quality }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Error: ${e.message}")
        }
    }
}
