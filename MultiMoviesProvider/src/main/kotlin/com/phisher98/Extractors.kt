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
import kotlinx.coroutines.runBlocking

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

suspend fun routeExtractor(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    mirrorName: String? = null
) {
    val cleanUrl = url.lowercase()
    val mName = mirrorName?.lowercase() ?: ""
    val gdmRegex = Regex("""streams\.iqsmartgames\.com|pro\.iqsmartgames\.com|ddn\.iqsmartgames\.com""")
    val shgRegex = Regex("""multimoviesshg\.com|hanerix\.com|audinifer\.xyz""")
    val fmRegex = Regex("""bysetayico\.com|filemoon""")
    val evRegex = Regex("""smoothpre\.com|minochinos\.com|vidhide|earnvids|flls""")
    val gfRegex = Regex("""gofile""")

    when {
        gdmRegex.containsMatchIn(cleanUrl) || mName.contains("gdmirror") -> {
            GDMIRROR().getUrl(url, referer, subtitleCallback, callback)
        }
        shgRegex.containsMatchIn(cleanUrl) || mName.contains("streamhg") -> {
            StreamHG().getUrl(url, referer, subtitleCallback, callback)
        }
        fmRegex.containsMatchIn(cleanUrl) || mName.contains("filemoon") -> {
            FileMoon().getUrl(url, referer, subtitleCallback, callback)
        }
        evRegex.containsMatchIn(cleanUrl) || mName.contains("earnvids") || mName.contains("flls") -> {
            EarnVids().getUrl(url, referer, subtitleCallback, callback)
        }
        gfRegex.containsMatchIn(cleanUrl) || mName.contains("gofile") -> {
            Gofile().getUrl(url, referer, subtitleCallback, callback)
        }
        else -> {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
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
                val mirrorName = item.select(".mirror-name strong").text().trim().lowercase()
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
                            routeExtractor(finalUrl, filesResponse.url, subtitleCallback, callback, mirrorName)
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
    override val mainUrl: String
        get() = runBlocking {
            getLatestUrl("https://multimoviesshg.com", "multimoviesshg")
        }
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
    override val mainUrl: String
        get() = runBlocking {
            getLatestUrl("https://bysetayico.com", "filemoon")
        }
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
    override var mainUrl: String
        get() = runBlocking {
            getLatestUrl("https://smoothpre.com", "earnvids")
        }
        set(value) {}
}

open class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl: String
        get() = runBlocking {
            getLatestUrl("https://gofile.io", "gofile")
        }
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val latestMainUrl = getLatestUrl(url, "gofile")
            val latestApiUrl = latestMainUrl.replace("://", "://api.")

            val requestHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Origin" to latestMainUrl,
                "Referer" to latestMainUrl,
            )
            val id = url.substringAfter("d/").substringBefore("/")
            val genAccountRes = app.post("$latestApiUrl/accounts", headers = requestHeaders).text
            val jsonResp = JSONObject(genAccountRes)
            val token = jsonResp.getJSONObject("data").getString("token")
            val globalRes = app.get("$latestMainUrl/dist/js/config.js", headers = requestHeaders).text
            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

            val response = app.get("$latestApiUrl/contents/$id?cache=true&sortField=createTime&sortDirection=1",
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Origin" to latestMainUrl,
                    "Referer" to latestMainUrl,
                    "Authorization" to "Bearer $token",
                    "X-Website-Token" to wt
                )
            ).text

            val jsonResponse = JSONObject(response)
            val data = jsonResponse.getJSONObject("data")
            val children = data.getJSONObject("children")
            val oId = children.keys().next()
            val link = children.getJSONObject(oId).getString("link")
            val fileName = children.getJSONObject(oId).getString("name")
            val size = children.getJSONObject(oId).getLong("size")
            val formattedSize = if (size < 1024L * 1024 * 1024) {
                val sizeInMB = size.toDouble() / (1024 * 1024)
                "%.2f MB".format(sizeInMB)
            } else {
                val sizeInGB = size.toDouble() / (1024 * 1024 * 1024)
                "%.2f GB".format(sizeInGB)
            }

            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    "Gofile $fileName[$formattedSize]",
                    link,
                ) {
                    this.quality = getQuality(fileName)
                    this.headers = mapOf(
                        "Cookie" to "accountToken=$token"
                    )
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
