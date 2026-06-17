package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive"
)

fun decodeRadix(str: String, radix: Int): Int? {
    if (radix <= 36) {
        return str.toIntOrNull(radix)
    }
    var result = 0
    val base62Chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    for (char in str) {
        val value = base62Chars.indexOf(char)
        if (value == -1 || value >= radix) {
            return null
        }
        result = result * radix + value
    }
    return result
}

fun unpack(packed: String): String {
    try {
        val pattern = Regex("""eval\(function\(p,a,c,k,e,d\).+?\}\((['"].*?['"])\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"].*?['"])\.split\(['"]\|['"]\)""", RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(packed) ?: return ""
        
        var p = match.groupValues[1]
        p = p.substring(1, p.length - 1)
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            
        val a = match.groupValues[2].toInt()
        val c = match.groupValues[3].toInt()
        val k = match.groupValues[4].substring(1, match.groupValues[4].length - 1).split("|")
        
        fun getWord(n: Int): String {
            return if (n < k.size && k[n].isNotEmpty()) k[n] else n.toString(a)
        }
        
        val wordPattern = Regex("""\b\w+\b""")
        return wordPattern.replace(p) { result ->
            val word = result.value
            val n = decodeRadix(word, a)
            if (n != null && n < k.size && k[n].isNotEmpty()) {
                k[n]
            } else {
                word
            }
        }
    } catch (e: Exception) {
        return ""
    }
}

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
    val shgRegex = Regex("""multimoviesshg\.com|hanerix\.com|audinifer\.xyz""")
    val fmRegex = Regex("""bysetayico\.com|filemoon""")
    val evRegex = Regex("""smoothpre\.com|minochinos\.com|vidhide|earnvids|flls""")
    val gfRegex = Regex("""gofile""")
    val screenscapeRegex = Regex("""screenscape\.me""")
    val cineverseRegex = Regex("""modiplay\.xyz""")

    when {
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
        screenscapeRegex.containsMatchIn(cleanUrl) -> {
            Screenscape().getUrl(url, referer, subtitleCallback, callback)
        }
        cineverseRegex.containsMatchIn(cleanUrl) -> {
            Cineverse().getUrl(url, referer, subtitleCallback, callback)
        }
        else -> {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════════
// CUSTOM EXTRACTORS
// ═══════════════════════════════════════════════════════════════════════════════════

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
            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\(['"]\|['"]\)""")
            val matches = packerRegex.findAll(html)
            
            var m3u8Link: String? = null
            matches.forEach { match ->
                val packed = match.value
                val unpacked = unpack(packed)
                if (unpacked.isNotEmpty()) {
                    val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    val found = m3u8Regex.find(unpacked)?.groupValues?.get(1)
                    if (found != null) {
                        m3u8Link = found
                    }
                }
            }
            
            if (m3u8Link == null) {
                val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                m3u8Link = m3u8Regex.find(html)?.groupValues?.get(1)
            }
            if (m3u8Link != null) {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8Link,
                        INFER_TYPE
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
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
            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\(['"]\|['"]\)""")
            val matches = packerRegex.findAll(html)
            
            var m3u8: String? = null
            matches.forEach { match ->
                val packed = match.value
                val unpacked = unpack(packed)
                if (unpacked.isNotEmpty()) {
                    val fileRegex = Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                    var found = fileRegex.find(unpacked)?.groupValues?.get(1)
                    if (found == null) {
                        val genericM3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                        found = genericM3u8Regex.find(unpacked)?.groupValues?.get(1)
                    }
                    if (found != null) {
                        m3u8 = found
                    }
                }
            }
            
            if (m3u8 == null) {
                val fileRegex = Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
                m3u8 = fileRegex.find(html)?.groupValues?.get(1)

                if (m3u8 == null) {
                    val genericM3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                    m3u8 = genericM3u8Regex.find(html)?.groupValues?.get(1)
                }
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
                        this.referer = url
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
}

open class EarnVids : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl: String
        get() = runBlocking {
            getLatestUrl("https://smoothpre.com", "earnvids")
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

            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\(['"]\|['"]\)""")
            val matches = packerRegex.findAll(html)
            
            matches.forEach { match ->
                val packed = match.value
                val unpacked = unpack(packed)
                if (unpacked.isNotEmpty()) {
                    val m3u8Regex = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
                    m3u8Regex.findAll(unpacked).forEach { m3u8Match ->
                        val m3u8Link = m3u8Match.value.replace("\\/", "/")
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                m3u8Link,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.Unknown.value
                                this.referer = url
                                this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
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

open class Screenscape : ExtractorApi() {
    override val name = "Screenscape"
    override val mainUrl = "https://screenscape.me"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val html = response.text

            // Extract the direct mp4 link proxied via workers.dev
            val videoUrl = Regex("""<video[^>]*src=["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""source\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.getOrNull(1)

            if (!videoUrl.isNullOrBlank()) {
                val decodedUrl = videoUrl.replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name,
                        name,
                        decodedUrl,
                        INFER_TYPE
                    ) {
                        this.referer = url
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error: \${e.message}")
        }
    }
}

open class Cineverse : ExtractorApi() {
    override val name = "Cineverse"
    override val mainUrl = "https://modiplay.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer)
            val doc = Jsoup.parse(response.text)
            
            // Extract proxy iframe source
            val iframeSrc = doc.select("iframe").attr("src")
            if (iframeSrc.isNotBlank()) {
                // Determine proxy referer base
                val proxyBase = getBaseUrl(iframeSrc)
                val proxyResponse = app.get(iframeSrc, referer = url)
                
                // Usually proxies to another embed (like rpmshare, etc)
                // We'll extract and route it
                val html = proxyResponse.text
                val m3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(html)?.groupValues?.getOrNull(1)
                
                if (!m3u8.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            name,
                            name,
                            m3u8,
                            INFER_TYPE
                        ) {
                            this.referer = proxyBase
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: \${e.message}")
        }
    }
}
