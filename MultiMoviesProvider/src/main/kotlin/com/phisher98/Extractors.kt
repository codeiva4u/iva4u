package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════════
// Utility Functions
// ═══════════════════════════════════════════════════════════════════════════════

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

// Cached URLs for session-level caching (fetch once, use throughout session)
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

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ═══════════════════════════════════════════════════════════════════════════════
// GDMirror Extractor — Main entry point for stream.techinmind.space embed
//
// Chain: stream.techinmind.space/embed/{type}/{imdb_id}
//    ├── API: mymovieapi?imdbid={id}&key={key} → file slugs
//    └── ssn.techinmind.space/evid/{slug} → /svid/ page → server links
//        ├── SMWH: multimoviesshg.com/e/{id} (StreamHG JWPlayer HLS)
//        ├── RPMSHRE: rpmhub.site/#{hash}
//        ├── UPNSHR: server1.uns.bio/#{hash}
//        └── STRMP2: p2pplay.pro/#{hash}
// ═══════════════════════════════════════════════════════════════════════════════

class GDMirrorExtractor : ExtractorApi() {
    override val name = "GDMirror"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDMirror", "Starting extraction for: $url")

            val latestBaseUrl = getLatestUrl(url, "techinmind")

            // Replace domain if it changed
            val actualUrl = if (latestBaseUrl.isNotEmpty() && latestBaseUrl != getBaseUrl(url)) {
                url.replace(getBaseUrl(url), latestBaseUrl)
            } else url

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: "https://multimovies.sarl/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            // Step 1: Fetch the embed page
            val embedResponse = app.get(actualUrl, headers = headers)
            val embedHtml = embedResponse.text

            // Extract FinalID, idType, and myKey from embed page script
            val finalId = Regex("""let\s+FinalID\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1) ?: ""
            val idType = Regex("""let\s+idType\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1) ?: "imdbid"
            val myKey = Regex("""let\s+myKey\s*=\s*["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1) ?: ""

            if (finalId.isEmpty()) {
                Log.e("GDMirror", "Could not extract FinalID from embed page")
                return
            }

            Log.d("GDMirror", "FinalID=$finalId, idType=$idType")

            // Step 2: Extract direct links from embed page HTML (pre-loaded quality-links)
            val directLinks = mutableListOf<Pair<String, String>>()
            Regex("""data-link=["']([^"']+)["'][^>]*>([^<]+)""").findAll(embedHtml).forEach { match ->
                directLinks.add(Pair(match.groupValues[1], match.groupValues[2].trim()))
            }

            // Also extract iframe src from embed page
            val iframeSrc = Regex("""<iframe[^>]+id=["']player["'][^>]+src=["']([^"']+)["']""").find(embedHtml)?.groupValues?.get(1)

            // Step 3: Call the movie API to get file slugs
            val baseUrl = getBaseUrl(actualUrl)
            val apiUrl = "$baseUrl/mymovieapi?$idType=$finalId&key=$myKey"
            Log.d("GDMirror", "API URL: $apiUrl")

            try {
                val apiHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Referer" to actualUrl,
                    "Accept" to "application/json, text/plain, */*",
                    "Origin" to baseUrl
                )
                val apiResponse = app.get(apiUrl, headers = apiHeaders)
                val apiJson = JSONObject(apiResponse.text)

                if (apiJson.optBoolean("success") && apiJson.has("data")) {
                    val dataArray = apiJson.getJSONArray("data")
                    val ssnBaseUrl = "https://ssn.techinmind.space"

                    for (i in 0 until dataArray.length()) {
                        val item = dataArray.getJSONObject(i)
                        val fileSlug = item.optString("fileslug", "")
                        val quality = item.optString("filename", "Unknown Quality")

                        if (fileSlug.isNotEmpty()) {
                            try {
                                // Step 4: Fetch evid page which redirects to svid
                                val evidUrl = "$ssnBaseUrl/evid/$fileSlug"
                                Log.d("GDMirror", "Fetching evid: $evidUrl")

                                val svidResponse = app.get(evidUrl, headers = mapOf(
                                    "User-Agent" to headers["User-Agent"]!!,
                                    "Referer" to actualUrl
                                ), allowRedirects = true)

                                val svidHtml = svidResponse.text

                                // Step 5: Extract all server links from svid page
                                extractServersFromSvidPage(svidHtml, quality, subtitleCallback, callback)

                            } catch (e: Exception) {
                                Log.e("GDMirror", "Error processing slug $fileSlug: ${e.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("GDMirror", "API call failed: ${e.message}")
            }

            // Step 6: Process direct links from embed page (already loaded quality links)
            if (directLinks.isNotEmpty()) {
                directLinks.amap { (link, qualityName) ->
                    try {
                        processDirectLink(link, qualityName, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e("GDMirror", "Error processing direct link: ${e.message}")
                    }
                }
            } else if (iframeSrc != null && iframeSrc.contains("techinmind.space")) {
                // Process iframe src if no direct links found
                try {
                    processDirectLink(iframeSrc, "Default", subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("GDMirror", "Error processing iframe: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("GDMirror", "Extraction failed: ${e.message}")
        }
    }

    private suspend fun extractServersFromSvidPage(
        html: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Parse server links from svid page
        // Pattern: <li class="server-item" data-link="..." data-source-key="...">
        val serverPattern = Regex("""data-link=["']([^"']+)["'][^>]*data-source-key=["']([^"']+)["']""")
        val servers = serverPattern.findAll(html).toList()

        // Also extract the primary iframe
        val primaryIframe = Regex("""<iframe[^>]+id=["']vidFrame["'][^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)

        val processedUrls = mutableSetOf<String>()

        // Process primary iframe first
        if (primaryIframe != null && processedUrls.add(primaryIframe)) {
            try {
                dispatchToExtractor(primaryIframe, quality, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("GDMirror", "Primary iframe error: ${e.message}")
            }
        }

        // Process server links
        servers.amap { match ->
            val serverUrl = match.groupValues[1]
            val sourceKey = match.groupValues[2]

            if (processedUrls.add(serverUrl)) {
                try {
                    dispatchToExtractor(serverUrl, "$quality [$sourceKey]", subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("GDMirror", "Server $sourceKey error: ${e.message}")
                }
            }
        }
    }

    private suspend fun dispatchToExtractor(
        url: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GDMirror", "Dispatching: $url")

        val multimoviesshgUrl = MultiMoviesProvider.getMultimoviesshgUrl()

        when {
            url.contains("multimoviesshg") || url.contains(URI(multimoviesshgUrl).host) -> {
                StreamHGExtractor().getUrl(url, null, subtitleCallback, callback)
            }
            url.contains("rpmhub.site") || url.contains("multimovies.rpmhub") -> {
                RpmShareExtractor().getUrl(url, null, subtitleCallback, callback)
            }
            url.contains("uns.bio") || url.contains("server1.uns") -> {
                UpnShareExtractor().getUrl(url, null, subtitleCallback, callback)
            }
            url.contains("p2pplay.pro") || url.contains("multimovies.p2pplay") -> {
                StreamP2pExtractor().getUrl(url, null, subtitleCallback, callback)
            }
            else -> {
                Log.d("GDMirror", "Unknown server domain for URL: $url")
            }
        }
    }

    private suspend fun processDirectLink(
        url: String,
        quality: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("techinmind.space/evid/") || url.contains("techinmind.space/svid/") -> {
                val response = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                ), allowRedirects = true)
                extractServersFromSvidPage(response.text, quality, subtitleCallback, callback)
            }
            url.contains("multimoviesshg") -> {
                StreamHGExtractor().getUrl(url, null, subtitleCallback, callback)
            }
            else -> {
                dispatchToExtractor(url, quality, subtitleCallback, callback)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// StreamHG Extractor — multimoviesshg.com JWPlayer HLS
//
// Structure: multimoviesshg.com/e/{file_code}
// Player: JWPlayer with HLS master.m3u8
// Stream URL pattern: /stream/{token}/{hash}/{timestamp}/{fileid}/master.m3u8
// ═══════════════════════════════════════════════════════════════════════════════

class StreamHGExtractor : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamHG", "Extracting from: $url")

            // Dynamic URL resolution
            val latestUrl = getLatestUrl(url, "multimoviesshg")
            val actualUrl = if (latestUrl.isNotEmpty()) {
                url.replace(getBaseUrl(url), latestUrl)
            } else url
            val baseUrl = getBaseUrl(actualUrl)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: baseUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(actualUrl, headers = headers)
            val html = response.text
            val pageTitle = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)?.trim() ?: "StreamHG"

            // Method 1: Extract m3u8 from JWPlayer setup (packed JS)
            val m3u8Url = extractM3u8FromPage(html, baseUrl)

            if (m3u8Url != null) {
                Log.d("StreamHG", "Found m3u8: $m3u8Url")

                val fullM3u8 = if (m3u8Url.startsWith("http")) m3u8Url else "$baseUrl$m3u8Url"

                callback.invoke(
                    newExtractorLink(
                        name,
                        pageTitle,
                        fullM3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = actualUrl
                        this.quality = getIndexQuality(pageTitle)
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            "Referer" to baseUrl,
                            "Origin" to baseUrl
                        )
                    }
                )
                return
            }

            // Method 2: Try unpacking obfuscated JS (eval/p/a/c/k/e/d)
            val packedRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL)
            val packedMatches = packedRegex.findAll(html)

            for (packed in packedMatches) {
                try {
                    val unpacked = JsUnpacker(packed.value).unpack()
                    if (unpacked != null) {
                        val unpackedM3u8 = extractM3u8FromScript(unpacked, baseUrl)
                        if (unpackedM3u8 != null) {
                            Log.d("StreamHG", "Found m3u8 from packed JS: $unpackedM3u8")
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    pageTitle,
                                    unpackedM3u8,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = actualUrl
                                    this.quality = getIndexQuality(pageTitle)
                                    this.headers = mapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                                        "Referer" to baseUrl,
                                        "Origin" to baseUrl
                                    )
                                }
                            )
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StreamHG", "Unpack error: ${e.message}")
                }
            }

            Log.e("StreamHG", "Could not extract m3u8 from $actualUrl")

        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction failed: ${e.message}")
        }
    }

    private fun extractM3u8FromPage(html: String, baseUrl: String): String? {
        // Pattern 1: Direct m3u8 URL in page source
        val directM3u8 = Regex("""["']((?:https?://)?[^"'\s]*?/stream/[^"'\s]*?master\.m3u8[^"'\s]*)["']""").find(html)?.groupValues?.get(1)
        if (directM3u8 != null) return directM3u8

        // Pattern 2: JWPlayer sources setup
        val jwSources = Regex("""sources:\s*\[\s*\{[^}]*file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (jwSources != null) return jwSources

        // Pattern 3: General m3u8 URL pattern
        val generalM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (generalM3u8 != null) return generalM3u8

        // Pattern 4: Try unpacking all packed JS
        val packedScripts = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (packed in packedScripts) {
            try {
                val unpacked = JsUnpacker(packed.value).unpack()
                if (unpacked != null) {
                    val m3u8 = extractM3u8FromScript(unpacked, baseUrl)
                    if (m3u8 != null) return m3u8
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun extractM3u8FromScript(script: String, baseUrl: String): String? {
        // Pattern: file:"https://...master.m3u8..."
        val patterns = listOf(
            Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""sources\s*:\s*\[\s*\{[^}]*["']file["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[^\s"'<>]+/stream/[^\s"'<>]+master\.m3u8[^\s"'<>]*)"""),
            Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(script)
            if (match != null) return match.groupValues[1]
        }

        // Relative path
        val relativeM3u8 = Regex("""["'](/stream/[^"']+master\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
        if (relativeM3u8 != null) return "$baseUrl$relativeM3u8"

        return null
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RpmShare Extractor — rpmhub.site video player
//
// Structure: multimovies.rpmhub.site/#{hash}
// Uses hash fragment to load video via API/JS
// ═══════════════════════════════════════════════════════════════════════════════

class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "Extracting from: $url")

            val hash = url.substringAfter("#").takeIf { it.isNotEmpty() && it != url } ?: ""
            val baseUrl = getBaseUrl(url)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers)
            val html = response.text

            // Try extracting m3u8 from page/scripts
            val m3u8Url = extractHlsUrl(html, baseUrl, hash)

            if (m3u8Url != null) {
                Log.d("RpmShare", "Found HLS: $m3u8Url")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "RpmShare",
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            "Referer" to baseUrl,
                            "Origin" to baseUrl
                        )
                    }
                )
                return
            }

            // Fallback: Try API endpoint with hash
            if (hash.isNotEmpty()) {
                try {
                    val apiUrl = "$baseUrl/api/stream/$hash"
                    val apiResponse = app.get(apiUrl, headers = headers)
                    val apiText = apiResponse.text

                    val apiM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(apiText)?.groupValues?.get(1)
                    if (apiM3u8 != null) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "RpmShare",
                                apiM3u8,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "Referer" to baseUrl,
                                    "Origin" to baseUrl
                                )
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                    Log.e("RpmShare", "API fallback failed: ${e.message}")
                }
            }

            // Try finding iframe and extracting from that
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl$iframeSrc"
                val iframeResponse = app.get(fullIframeUrl, headers = headers)
                val iframeM3u8 = extractHlsUrl(iframeResponse.text, getBaseUrl(fullIframeUrl), "")
                if (iframeM3u8 != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "RpmShare",
                            iframeM3u8,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = fullIframeUrl
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to baseUrl,
                                "Origin" to baseUrl
                            )
                        }
                    )
                }
            }

            Log.e("RpmShare", "Could not extract video URL")

        } catch (e: Exception) {
            Log.e("RpmShare", "Extraction failed: ${e.message}")
        }
    }

    private fun extractHlsUrl(html: String, baseUrl: String, hash: String): String? {
        // Direct m3u8
        val m3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (m3u8 != null) return m3u8

        // Packed JS
        val packedScripts = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (packed in packedScripts) {
            try {
                val unpacked = JsUnpacker(packed.value).unpack()
                if (unpacked != null) {
                    val unpackedM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    if (unpackedM3u8 != null) return unpackedM3u8
                }
            } catch (_: Exception) {}
        }

        // MP4 fallback
        val mp4 = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (mp4 != null) return mp4

        return null
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UpnShare Extractor — server1.uns.bio video player
//
// Structure: server1.uns.bio/#{hash}
// Uses hash fragment to load video via API/JS
// ═══════════════════════════════════════════════════════════════════════════════

class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UpnShare", "Extracting from: $url")

            val hash = url.substringAfter("#").takeIf { it.isNotEmpty() && it != url } ?: ""
            val baseUrl = getBaseUrl(url)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers)
            val html = response.text

            // Try extracting video URL
            val videoUrl = extractVideoUrl(html, baseUrl)

            if (videoUrl != null) {
                Log.d("UpnShare", "Found video: $videoUrl")
                val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name,
                        "UpnShare",
                        videoUrl,
                        linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            "Referer" to baseUrl,
                            "Origin" to baseUrl
                        )
                    }
                )
                return
            }

            // Fallback: API with hash
            if (hash.isNotEmpty()) {
                val apiEndpoints = listOf(
                    "$baseUrl/api/stream/$hash",
                    "$baseUrl/api/file/$hash",
                    "$baseUrl/embed/$hash"
                )
                for (apiUrl in apiEndpoints) {
                    try {
                        val apiResponse = app.get(apiUrl, headers = headers)
                        val apiVideoUrl = extractVideoUrl(apiResponse.text, baseUrl)
                        if (apiVideoUrl != null) {
                            val linkType = if (apiVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "UpnShare",
                                    apiVideoUrl,
                                    linkType
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.P1080.value
                                    this.headers = mapOf(
                                        "Referer" to baseUrl,
                                        "Origin" to baseUrl
                                    )
                                }
                            )
                            return
                        }
                    } catch (_: Exception) {}
                }
            }

            // Try iframe
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl$iframeSrc"
                val iframeResponse = app.get(fullIframeUrl, headers = headers)
                val iframeVideoUrl = extractVideoUrl(iframeResponse.text, getBaseUrl(fullIframeUrl))
                if (iframeVideoUrl != null) {
                    val linkType = if (iframeVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "UpnShare",
                            iframeVideoUrl,
                            linkType
                        ) {
                            this.referer = fullIframeUrl
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to baseUrl,
                                "Origin" to baseUrl
                            )
                        }
                    )
                }
            }

            Log.e("UpnShare", "Could not extract video URL")

        } catch (e: Exception) {
            Log.e("UpnShare", "Extraction failed: ${e.message}")
        }
    }

    private fun extractVideoUrl(html: String, baseUrl: String): String? {
        // m3u8
        val m3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (m3u8 != null) return m3u8

        // Packed JS
        val packedScripts = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (packed in packedScripts) {
            try {
                val unpacked = JsUnpacker(packed.value).unpack()
                if (unpacked != null) {
                    val unpackedM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    if (unpackedM3u8 != null) return unpackedM3u8
                    val unpackedMp4 = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    if (unpackedMp4 != null) return unpackedMp4
                }
            } catch (_: Exception) {}
        }

        // MP4
        val mp4 = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (mp4 != null) return mp4

        val generalMp4 = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (generalMp4 != null) return generalMp4

        return null
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// StreamP2p Extractor — p2pplay.pro video player
//
// Structure: multimovies.p2pplay.pro/#{hash}
// Uses hash fragment to load video via API/JS
// ═══════════════════════════════════════════════════════════════════════════════

class StreamP2pExtractor : ExtractorApi() {
    override val name = "StreamP2p"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamP2p", "Extracting from: $url")

            val hash = url.substringAfter("#").takeIf { it.isNotEmpty() && it != url } ?: ""
            val baseUrl = getBaseUrl(url)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers)
            val html = response.text

            // Extract video URL from page
            val videoUrl = extractStreamUrl(html, baseUrl)

            if (videoUrl != null) {
                Log.d("StreamP2p", "Found video: $videoUrl")
                val linkType = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        name,
                        "StreamP2p",
                        videoUrl,
                        linkType
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                            "Referer" to baseUrl,
                            "Origin" to baseUrl
                        )
                    }
                )
                return
            }

            // Fallback: API with hash
            if (hash.isNotEmpty()) {
                val apiEndpoints = listOf(
                    "$baseUrl/api/stream/$hash",
                    "$baseUrl/api/file/$hash",
                    "$baseUrl/embed/$hash"
                )
                for (apiUrl in apiEndpoints) {
                    try {
                        val apiResponse = app.get(apiUrl, headers = headers)
                        val apiVideoUrl = extractStreamUrl(apiResponse.text, baseUrl)
                        if (apiVideoUrl != null) {
                            val linkType = if (apiVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "StreamP2p",
                                    apiVideoUrl,
                                    linkType
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.P1080.value
                                    this.headers = mapOf(
                                        "Referer" to baseUrl,
                                        "Origin" to baseUrl
                                    )
                                }
                            )
                            return
                        }
                    } catch (_: Exception) {}
                }
            }

            // Try iframe
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc != null) {
                val fullIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$baseUrl$iframeSrc"
                val iframeResponse = app.get(fullIframeUrl, headers = headers)
                val iframeVideoUrl = extractStreamUrl(iframeResponse.text, getBaseUrl(fullIframeUrl))
                if (iframeVideoUrl != null) {
                    val linkType = if (iframeVideoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "StreamP2p",
                            iframeVideoUrl,
                            linkType
                        ) {
                            this.referer = fullIframeUrl
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to baseUrl,
                                "Origin" to baseUrl
                            )
                        }
                    )
                }
            }

            Log.e("StreamP2p", "Could not extract video URL")

        } catch (e: Exception) {
            Log.e("StreamP2p", "Extraction failed: ${e.message}")
        }
    }

    private fun extractStreamUrl(html: String, baseUrl: String): String? {
        // m3u8
        val m3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (m3u8 != null) return m3u8

        // Packed JS
        val packedScripts = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\{.*?\}\(.*?\)\)""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (packed in packedScripts) {
            try {
                val unpacked = JsUnpacker(packed.value).unpack()
                if (unpacked != null) {
                    val unpackedM3u8 = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    if (unpackedM3u8 != null) return unpackedM3u8
                    val unpackedMp4 = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(unpacked)?.groupValues?.get(1)
                    if (unpackedMp4 != null) return unpackedMp4
                }
            } catch (_: Exception) {}
        }

        // MP4
        val mp4 = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)
        if (mp4 != null) return mp4

        val generalMp4 = Regex("""(https?://[^\s"'<>]+\.mp4[^\s"'<>]*)""").find(html)?.groupValues?.get(1)
        if (generalMp4 != null) return generalMp4

        return null
    }
}
