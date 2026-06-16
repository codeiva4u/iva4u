package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import java.net.URI
import org.jsoup.Jsoup
import org.json.JSONObject

// ═══════════════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
        } catch (e: Exception) {
            return getBaseUrl(url)
        }
    }
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
    return link
}

// ═══════════════════════════════════════════════════════════════════════════════════
// CUSTOM EXTRACTORS
// ═══════════════════════════════════════════════════════════════════════════════════

open class GDMIRROR : ExtractorApi() {
    override val name = "GDMIRROR"
    override val mainUrl = "https://(?:streams\\.iqsmartgames\\.com|pro\\.iqsmartgames\\.com|ddn\\.iqsmartgames\\.com)"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMIRROR"
        try {
            // 1. Fetch embed URL (which redirects to /svid/<token>)
            val response = app.get(url, referer = referer)
            val html = response.text

            // 2. Parse the sid (file ID)
            val sid = Regex("""id="gdmrfid"\s+value="([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: Regex("""const\s+sid\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

            if (sid.isNullOrBlank()) {
                Log.e(tag, "Could not find sid from svid page")
                return
            }
            Log.d(tag, "Found sid: $sid")

            // 3. Construct files URL and fetch it
            val filesUrl = "https://ddn.iqsmartgames.com/file/$sid"
            val filesResponse = app.get(filesUrl)
            val filesHtml = filesResponse.text
            val filesDoc = Jsoup.parse(filesHtml, filesResponse.url)

            // 4. Extract direct worker streaming link if present (fileurl)
            val fileurl = Regex("""const\s+fileurl\s*=\s*"([^"]+)"""").find(filesHtml)
                ?.groupValues?.get(1)
                ?.replace("\\/", "/")

            if (!fileurl.isNullOrBlank()) {
                Log.d(tag, "Found direct fileurl: $fileurl")
                callback(
                    newExtractorLink(
                        "GDMIRROR Direct",
                        "GDMIRROR Direct",
                        fileurl
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // 5. Extract all Mirror Links from the page
            val mirrors = filesDoc.select(".mirror-item")
            mirrors.forEach { item ->
                val mirrorName = item.select(".mirror-name strong").text().trim()
                val visitUrl = item.select(".mirror-actions a.mirror-btn").attr("href")
                if (visitUrl.isNotBlank()) {
                    val absVisitUrl = if (visitUrl.startsWith("http")) {
                        visitUrl
                    } else {
                        "https://pro.iqsmartgames.com" + if (visitUrl.startsWith("/")) visitUrl else "/$visitUrl"
                    }

                    Log.d(tag, "Mirror: $mirrorName, visitUrl: $absVisitUrl")
                    try {
                        val finalResponse = app.get(absVisitUrl, referer = filesResponse.url)
                        val finalUrl = finalResponse.url
                        Log.d(tag, "Resolved mirror: $mirrorName -> $finalUrl")

                        if (finalUrl.isNotBlank() && !finalUrl.contains("iqsmartgames.com")) {
                            loadExtractor(finalUrl, filesResponse.url, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to resolve mirror $mirrorName: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in GDMIRROR extractor: ${e.message}")
        }
    }
}

open class StreamHG : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://(?:multimoviesshg\\.com|hanerix\\.com|audinifer\\.xyz)"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val html = response.text
            val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
            val m3u8Link = m3u8Regex.find(html)?.groupValues?.get(1)
            if (m3u8Link != null) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8Link,
                        INFER_TYPE
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://(?:bysetayico\\.com|filemoon\\..*)"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val html = response.text
            val fileRegex = Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            var m3u8 = fileRegex.find(html)?.groupValues?.get(1)

            if (m3u8 == null) {
                val genericM3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                m3u8 = genericM3u8Regex.find(html)?.groupValues?.get(1)
            }

            if (m3u8 != null) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8,
                        INFER_TYPE
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class EarnVids : VidhideExtractor() {
    override var name = "EarnVids"
    override var mainUrl = "https://smoothpre.com"
}
