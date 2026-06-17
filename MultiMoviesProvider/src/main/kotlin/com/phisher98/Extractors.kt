package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

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
        val pattern = Regex("""eval\(function\(p,a,c,k,e,d\).+?\}\((['"].*?['"])\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"].*?['"])\.split\(['"]\\?\|['"]\)""", RegexOption.DOT_MATCHES_ALL)
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
    val vibuxerRegex = Regex("""vibuxer\.com""")
    val gdmRegex = Regex("""streams\.iqsmartgames\.com|pro\.iqsmartgames\.com|ddn\.iqsmartgames\.com|gdmirrorbot""")
    val evRegex = Regex("""smoothpre\.com|minochinos\.com|vidhide|earnvids|flls""")
    val screenscapeRegex = Regex("""screenscape\.me""")
    val technocosmosRegex = Regex("""technocosmos\.surf""")
    val peachifyRegex = Regex("""peachify\.top""")
    val nxshaRegex = Regex("""nxsha\.app""")

    when {
        vibuxerRegex.containsMatchIn(cleanUrl) || mName.contains("vibuxer") -> {
            Vibuxer().getUrl(url, referer, subtitleCallback, callback)
        }
        gdmRegex.containsMatchIn(cleanUrl) || mName.contains("gdmirror") -> {
            GDMIRROR().getUrl(url, referer, subtitleCallback, callback)
        }
        technocosmosRegex.containsMatchIn(cleanUrl) || mName.contains("rpmshare") || mName.contains("rpmshre") || mName.contains("upnshare") || mName.contains("upnshr") || mName.contains("strmp2") || mName.contains("streamp2p") -> {
            TechnocosmosPlayer().getUrl(url, referer, subtitleCallback, callback)
        }
        evRegex.containsMatchIn(cleanUrl) || mName.contains("earnvids") || mName.contains("flls") -> {
            EarnVids().getUrl(url, referer, subtitleCallback, callback)
        }
        screenscapeRegex.containsMatchIn(cleanUrl) -> {
            Screenscape().getUrl(url, referer, subtitleCallback, callback)
        }
        peachifyRegex.containsMatchIn(cleanUrl) -> {
            Peachify().getUrl(url, referer, subtitleCallback, callback)
        }
        nxshaRegex.containsMatchIn(cleanUrl) -> {
            Nxsha().getUrl(url, referer, subtitleCallback, callback)
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
    override val mainUrl = "https://(?:streams\\.iqsmartgames\\.com|pro\\.iqsmartgames\\.com|ddn\\.iqsmartgames\\.com|gdmirrorbot\\..*)"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMIRROR"
        try {
            val response = app.get(url, referer = referer)
            val html = response.text

            val finalId = Regex("""let\s+FinalID\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val idType = Regex("""let\s+idType\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: "imdbid"
            val myKey = Regex("""let\s+myKey\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            val playerBase = Regex("""let\s+player_base\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: "https://pro.iqsmartgames.com"
            val apiUrlBase = Regex("""let\s+api_url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: "https://streams.iqsmartgames.com"

            if (finalId != null && myKey != null) {
                val season = Regex("""let\s+season\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val epname = Regex("""let\s+epname\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                val apiUrl = if (season != null && epname != null) {
                    "$apiUrlBase/myseriesapi?$idType=$finalId&season=$season&epname=$epname&key=$myKey"
                } else {
                    "$apiUrlBase/mymovieapi?$idType=$finalId&key=$myKey"
                }
                
                try {
                    val apiResponse = app.get(apiUrl, referer = url).text
                    val json = org.json.JSONObject(apiResponse)
                    if (json.getBoolean("success")) {
                        val dataArray = json.optJSONArray("data")
                        for (i in 0 until (dataArray?.length() ?: 0)) {
                            val item = dataArray?.getJSONObject(i)
                            val filename = item?.optString("filename") ?: ""
                            val fileslug = item?.optString("fileslug") ?: ""
                            
                            if (fileslug.isNotBlank()) {
                                val evidUrl = "$playerBase/evid/$fileslug"
                                processEvidOrSvid(evidUrl, filename, response.url, originalId = null, subtitleCallback = subtitleCallback, callback = callback)
                            }
                        }
                        return
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to parse API response: ${e.message}")
                }
            }

            // Fallback: Check for direct quality-links or player iframe src in HTML
            val innerEvidRegex = Regex("""(?:https?://[^\s"'<>]+)?/(?:evid|svid)/[a-zA-Z0-9_-]+""")
            val innerEvidMatches = innerEvidRegex.findAll(html).map { match ->
                val path = match.value
                if (path.startsWith("http")) path else getBaseUrl(response.url) + path
            }.distinct().filter { it != response.url && it != url }.toList()
            
            if (innerEvidMatches.isNotEmpty()) {
                innerEvidMatches.forEach { innerUrl ->
                    processEvidOrSvid(innerUrl, "GDMIRROR", response.url, originalId = null, subtitleCallback, callback)
                }
                return
            }

            // Fallback 2: Try treating the url itself as an evid/svid/embed link
            val targetUrl = if (response.url.contains("/evid/") || response.url.contains("/svid/") || response.url.contains("/embed/")) response.url else url
            if (targetUrl.contains("/evid/") || targetUrl.contains("/svid/") || targetUrl.contains("/embed/")) {
                val originalId = finalId ?: url.substringAfterLast("/")
                processEvidOrSvid(targetUrl, "GDMIRROR", response.url, originalId, subtitleCallback, callback)
            } else {
                Log.e(tag, "Could not extract variables or quality links from embed page")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in GDMIRROR extractor: ${e.message}")
        }
    }

    private suspend fun processEvidOrSvid(
        svidUrl: String,
        qualityText: String,
        referer: String,
        originalId: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMIRROR"
        try {
            val fileslug = originalId ?: svidUrl.substringAfterLast("/")
            val playerBase = when {
                svidUrl.contains("/evid/") -> svidUrl.substringBefore("/evid/")
                svidUrl.contains("/svid/") -> svidUrl.substringBefore("/svid/")
                svidUrl.contains("/embed/") -> svidUrl.substringBefore("/embed/")
                else -> svidUrl.substringBeforeLast("/")
            }
            

            // 1. Fetch svidUrl HTML to parse pre-rendered server list
            var parsedDirectly = false
            val skipKeys = setOf("gdtot", "buzzheavier", "gofs", "flps", "flmn")
            try {
                val svidResponse = app.get(svidUrl, referer = referer)
                val svidHtml = svidResponse.text
                val doc = Jsoup.parse(svidHtml)
                val serverItems = doc.select("li.server-item[data-link]")
                
                if (serverItems.isNotEmpty()) {
                    Log.d(tag, "Parsed ${serverItems.size} server items directly from HTML")
                    serverItems.forEach { item ->
                        val iframeUrl = item.attr("data-link")
                        val mirrorName = item.attr("data-source-key")
                        if (mirrorName in skipKeys) return@forEach
                        if (iframeUrl.isNotBlank() && !iframeUrl.contains("iqsmartgames.com") && !iframeUrl.contains("gdmirrorbot")) {
                            Log.d(tag, "Routing parsed server: $iframeUrl ($mirrorName)")
                            routeExtractor(iframeUrl, referer = svidResponse.url, subtitleCallback, callback, mirrorName)
                        }
                    }
                    parsedDirectly = true
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse servers from HTML: ${e.message}")
            }

            if (!parsedDirectly) {
                // 2. Extract mirror links using embedhelper.php (fallback)
                val helperUrl = "$playerBase/embedhelper.php"
                val currentDomain = playerBase.substringAfter("://")
                
                // The API expects a JSON array string
                val currentDomainJson = "[\"$currentDomain\"]"
                val postData = mapOf("sid" to fileslug, "UserFavSite" to "", "currentDomain" to currentDomainJson)
                val response = app.post(helperUrl, data = postData, referer = referer)
                val jsonStr = response.text
                val json = org.json.JSONObject(jsonStr)
                
                val mresultBase64 = json.optString("mresult")
                val siteUrls = json.optJSONObject("siteUrls")
                val siteFriendlyNames = json.optJSONObject("siteFriendlyNames")
                
                if (mresultBase64.isNotBlank() && siteUrls != null) {
                    val mresultJson = String(java.util.Base64.getDecoder().decode(mresultBase64))
                    val mresultObj = org.json.JSONObject(mresultJson)
                    
                    mresultObj.keys().forEach { key ->
                        if (key in skipKeys) return@forEach
                        val value = mresultObj.getString(key)
                        val siteUrl = siteUrls.optString(key)
                        if (siteUrl.isNotBlank()) {
                            val iframeUrl = siteUrl + value
                            val mirrorName = siteFriendlyNames?.optString(key) ?: key
                            Log.d(tag, "Found iframe link: $iframeUrl for $mirrorName")
                            if (!iframeUrl.contains("iqsmartgames.com") && !iframeUrl.contains("gdmirrorbot")) {
                                routeExtractor(iframeUrl, referer = helperUrl, subtitleCallback, callback, mirrorName)
                            }
                        }
                    }
                } else {
                    Log.e(tag, "Failed to get mresult or siteUrls from embedhelper for $fileslug")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error processing evid/svid: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// TECHNOCOSMOS PLAYER (RpmShare / UpnShare via plyr.technocosmos.surf)
// ═══════════════════════════════════════════════════════════════════════════════════

open class TechnocosmosPlayer : ExtractorApi() {
    override val name = "TechnocosmosPlayer"
    override val mainUrl = "https://plyr.technocosmos.surf"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "TechnocosmosPlayer"
        try {
            // URL format: https://plyr.technocosmos.surf/hlsplayer?url=https://server1.uns.bio/#9obc9m
            // or: https://plyr.technocosmos.surf/hlsplayer?url=https://multimovies.rpmhub.site/#a65ok
            // The real video source is fetched via /watch?url=<encoded_input_url>
            
            val inputUrl = if (url.contains("?url=")) {
                url.substringAfter("?url=")
            } else {
                url
            }
            
            if (inputUrl.isBlank()) return
            
            // Generate timestamp and signature like the player JS does
            val timestamp = System.currentTimeMillis().toString()
            val secretStr = "$timestamp:$inputUrl:WATCH_SECRET"
            val signature = java.security.MessageDigest.getInstance("SHA-256")
                .digest(secretStr.toByteArray())
                .joinToString("") { "%02x".format(it) }
            
            val watchUrl = "$mainUrl/watch?url=${java.net.URLEncoder.encode(inputUrl, "UTF-8")}"
            val watchResponse = app.get(
                watchUrl,
                referer = url,
                headers = mapOf(
                    "x-ts" to timestamp,
                    "x-signature" to signature,
                    "Origin" to "https://plyr.technocosmos.surf",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            ).text
            
            try {
                val json = JSONObject(watchResponse)
                val sources = json.optJSONArray("sources")
                if (sources != null && sources.length() > 0) {
                    for (i in 0 until sources.length()) {
                        val source = sources.getJSONObject(i)
                        val file = source.optString("file")
                        val sourceName = source.optString("name", "TechnocosmosPlayer")
                        if (file.isNotBlank()) {
                            callback(
                                newExtractorLink(
                                    "MultiMovies $sourceName",
                                    "MultiMovies $sourceName",
                                    file,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.referer = mainUrl
                                    this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse watch response: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

open class Vibuxer : ExtractorApi() {
    override val name = "Vibuxer"
    override val mainUrl = "https://vibuxer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanUrl = url.replace(Regex("\\s"), "")
            val response = app.get(cleanUrl, referer = referer)
            val html = response.text
            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\(['"]\\?\|['"]\)""")
            val matches = packerRegex.findAll(html)
            
            matches.forEach { match ->
                val packed = match.value
                val unpacked = unpack(packed)
                if (unpacked.isNotEmpty()) {
                    val m3u8Regex = Regex("""["']hls\d*["']\s*:\s*["']([^"']+)["']""")
                    m3u8Regex.findAll(unpacked).forEach { m3u8Match ->
                        val matchedPath = m3u8Match.groupValues[1].replace("\\/", "/")
                        val m3u8Link = if (matchedPath.startsWith("http")) {
                            matchedPath
                        } else {
                            val baseUrl = getBaseUrl(cleanUrl)
                            if (matchedPath.startsWith("/")) {
                                baseUrl + matchedPath
                            } else {
                                "$baseUrl/$matchedPath"
                            }
                        }
                        callback(
                            newExtractorLink(
                                name,
                                name,
                                m3u8Link,
                                ExtractorLinkType.M3U8
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = cleanUrl
                                this.headers = mapOf("Referer" to cleanUrl, "Origin" to getBaseUrl(cleanUrl))
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

            val packerRegex = Regex("""eval\(function\(p,a,c,k,e,d\)[\s\S]*?\.split\(['"]\\?\|['"]\)""")
            val matches = packerRegex.findAll(html)
            
            matches.forEach { match ->
                val packed = match.value
                val unpacked = unpack(packed)
                if (unpacked.isNotEmpty()) {
                    val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
                    m3u8Regex.findAll(unpacked).forEach { m3u8Match ->
                        val matchedPath = m3u8Match.groupValues[1].replace("\\/", "/")
                        val m3u8Link = if (matchedPath.startsWith("http")) {
                            matchedPath
                        } else {
                            val baseUrl = getBaseUrl(url)
                            if (matchedPath.startsWith("/")) {
                                baseUrl + matchedPath
                            } else {
                                "$baseUrl/$matchedPath"
                            }
                        }
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
            Log.e(name, "Error: ${e.message}")
        }
    }
}


// ═══════════════════════════════════════════════════════════════════════════════════
// PEACHIFY EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════════

open class Peachify : ExtractorApi() {
    override val name = "Peachify"
    override val mainUrl = "https://peachify.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Peachify"
        try {
            // URL format: https://peachify.top/embed/movie/tt29540862?dub=Hindi&sub=English&accent=1DB954
            // This is a Next.js app that fetches video sources via API
            val response = app.get(url, referer = referer)
            val html = response.text

            // Try to extract m3u8 URLs from the RSC payload or page scripts
            val m3u8Regex = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
            val m3u8Matches = m3u8Regex.findAll(html)
            
            m3u8Matches.forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                        this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
                    }
                )
            }

            // If no m3u8 found in HTML, try the API endpoint
            if (!m3u8Matches.iterator().hasNext()) {
                // Extract IMDB ID and type from URL
                val urlPath = URI(url).path
                val pathParts = urlPath.split("/")
                // /embed/movie/tt29540862 or /embed/tv/tt12345/1/1
                val contentType = pathParts.getOrNull(2) ?: "movie"
                val imdbId = pathParts.getOrNull(3) ?: return
                
                // Try peachify API
                val apiUrl = "$mainUrl/api/source/$contentType/$imdbId"
                try {
                    val apiResp = app.get(apiUrl, referer = url, headers = mapOf(
                        "Accept" to "application/json"
                    )).text
                    val apiJson = JSONObject(apiResp)
                    val sources = apiJson.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val source = sources.getJSONObject(i)
                            val file = source.optString("file") ?: source.optString("url") ?: ""
                            val quality = source.optString("quality", "Auto")
                            if (file.isNotBlank()) {
                                callback(
                                    newExtractorLink(
                                        name,
                                        "$name $quality",
                                        file,
                                        INFER_TYPE
                                    ) {
                                        this.referer = mainUrl
                                        this.headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "API fallback failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// NHDAPI EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════════════════════
// NXSHA EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════════

open class Nxsha : ExtractorApi() {
    override val name = "Nxsha"
    override val mainUrl = "https://web.nxsha.app"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Nxsha"
        try {
            val response = app.get(url, referer = referer, timeout = 15)
            val html = response.text
            
            // Extract m3u8 links
            val m3u8Regex = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
            m3u8Regex.findAll(html).forEach { match ->
                val m3u8Url = match.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name,
                        name,
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = mainUrl
                    }
                )
            }
            
            // Try mp4 links too
            val mp4Regex = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""")
            mp4Regex.findAll(html).forEach { match ->
                val mp4Url = match.groupValues[1].replace("\\/", "/")
                callback(
                    newExtractorLink(
                        name,
                        name,
                        mp4Url,
                        INFER_TYPE
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Error: ${e.message}")
        }
    }
}
