package com.phisher98

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

// ═══════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ═══════════════════════════════════════════════════════════════════

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// Parse file size string to MB (e.g., "1.5GB" -> 1536, "700MB" -> 700)
fun parseSizeToMB(sizeStr: String): Double {
    val cleanSize = sizeStr.replace("[", "").replace("]", "").trim()
    val regex = Regex("""([\d.]+)\s*(GB|MB|gb|mb)""", RegexOption.IGNORE_CASE)
    val match = regex.find(cleanSize) ?: return Double.MAX_VALUE
    val value = match.groupValues[1].toDoubleOrNull() ?: return Double.MAX_VALUE
    val unit = match.groupValues[2].uppercase()
    return when (unit) {
        "GB" -> value * 1024
        "MB" -> value
        else -> Double.MAX_VALUE
    }
}

// ═══════════════════════════════════════════════════════════════════
// PRIORITY ORDER (Cloudstream - higher number = shown first):
// 
// 1. Codec + Quality (X264 1080p > X264 720p > HEVC 1080p)
// 2. Within that quality group, smallest file size
// 3. Within that quality+size, fastest server
// ═══════════════════════════════════════════════════════════════════
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()
    
    // Detect codec: X264 vs HEVC
    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
    
    // PRIORITY 1: Codec + Quality Group (10,000 points per group)
    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000  // Group 1: X264 1080p
        isX264 && quality >= 720  -> 20000  // Group 2: X264 720p
        isHEVC && quality >= 1080 -> 10000  // Group 3: HEVC 1080p
        isHEVC && quality >= 720  -> 9000   // Group 4: HEVC 720p
        quality >= 1080 -> 8000             // Unknown 1080p
        quality >= 720  -> 7000             // Unknown 720p
        quality >= 480  -> 6000             // 480p
        else -> 5000                        // Unknown
    }
    
    // PRIORITY 2: File Size (max 300 points within group)
    val sizeMB = parseSizeToMB(sizeStr)
    val sizeScore = when {
        sizeMB <= 300  -> 260
        sizeMB <= 400  -> 250
        sizeMB <= 500  -> 240
        sizeMB <= 600  -> 230
        sizeMB <= 700  -> 220
        sizeMB <= 800  -> 210
        sizeMB <= 900  -> 200
        sizeMB <= 1000 -> 190
        sizeMB <= 1200 -> 170
        sizeMB <= 1500 -> 140
        sizeMB <= 2000 -> 100
        sizeMB <= 2500 -> 60
        sizeMB <= 3000 -> 20
        else -> 0
    }
    
    // PRIORITY 3: Server Speed (max 100 points)
    val serverScore = getServerPriority(serverName)
    
    return codecQualityScore + sizeScore + serverScore
}

fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100
        serverName.contains("Direct", true) -> 90
        serverName.contains("FSL", true) -> 80
        serverName.contains("10Gbps", true) -> 85
        serverName.contains("Download", true) -> 70
        serverName.contains("Stream", true) -> 65
        else -> 50
    }
}

// ═══════════════════════════════════════════════════════════════════
// EXTRACTOR FACTORY - Auto-select correct extractor based on URL
// ═══════════════════════════════════════════════════════════════════
object ExtractorFactory {
    fun getExtractor(url: String): ExtractorApi? {
        return when {
            url.contains("stream.techinmind.space") -> TechInMindStream()
            url.contains("ssn.techinmind.space") -> SSNTechInMind()
            url.contains("ddn.iqsmartgames.com") -> DDNIQSmartGames()
            url.contains("pro.iqsmartgames.com") -> ProIQSmartGames()
            url.contains("multimoviesshg.com") -> MultiMoviesSHG()
            url.contains("rpmhub.site") -> RpmHub()
            url.contains("uns.bio") -> UnsBio()
            url.contains("p2pplay.pro") -> P2pPlay()
            url.contains("gdmirrorbot") -> GDMirrorDownload()
            url.contains("hubcloud") -> HubCloud()
            url.contains("gdflix") -> GDFlix()
            else -> null
        }
    }
    
    val extractorList = listOf(
        TechInMindStream(),
        SSNTechInMind(),
        DDNIQSmartGames(),
        ProIQSmartGames(),
        MultiMoviesSHG(),
        RpmHub(),
        UnsBio(),
        P2pPlay(),
        GDMirrorDownload(),
        HubCloud(),
        GDFlix()
    )
}

// ═══════════════════════════════════════════════════════════════════
// TECHINMIND STREAM EXTRACTOR
// Main player page: stream.techinmind.space/embed/movie/{id}
// Uses API: stream.techinmind.space/mymovieapi?imdbid={id}&key={key}
// ═══════════════════════════════════════════════════════════════════
class TechInMindStream : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    // Data class for API response
    data class MovieApiResponse(
        val success: Boolean,
        val message: String?,
        val data: List<MovieData>?
    )
    
    data class MovieData(
        val filename: String,
        val fileslug: String,
        val fsize: String?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Fetching: $url")
            
            // Parse URL to extract IMDB ID and key
            // URL format: https://stream.techinmind.space/embed/movie/tt33046197?key=e11a7debaaa4f5d25b671706ffe4d2acb56efbd4
            val uri = URI(url)
            val path = uri.path
            val query = uri.query ?: ""
            
            // Extract IMDB ID from path (last segment)
            val imdbId = path.substringAfterLast("/")
            
            // Extract key from query string
            val keyMatch = Regex("""key=([^&]+)""").find(query)
            val key = keyMatch?.groupValues?.get(1) ?: ""
            
            if (imdbId.isBlank()) {
                Log.e("TechInMind", "Could not extract IMDB ID from URL: $url")
                return
            }
            
            Log.d("TechInMind", "Extracted IMDB ID: $imdbId, key: $key")
            
            // Determine ID type (imdbid vs tmdbid)
            val idType = if (imdbId.startsWith("tt")) "imdbid" else "tmdbid"
            
            // Build API URL
            val apiUrl = "$mainUrl/mymovieapi?$idType=$imdbId&key=$key"
            Log.d("TechInMind", "Calling API: $apiUrl")
            
            // Call the API
            val apiResponse = app.get(apiUrl, referer = url)
            
            Log.d("TechInMind", "API response: ${apiResponse.text}")
            
            // Parse JSON response
            val json = JSONObject(apiResponse.text)
            val success = json.optBoolean("success", false)
            
            if (!success) {
                Log.w("TechInMind", "API returned success=false")
                // Fallback to HTML parsing
                parseHtmlFallback(url, referer, subtitleCallback, callback)
                return
            }
            
            val dataArray = json.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Log.w("TechInMind", "No data in API response, trying HTML fallback")
                parseHtmlFallback(url, referer, subtitleCallback, callback)
                return
            }
            
            Log.d("TechInMind", "Found ${dataArray.length()} quality options")
            
            // Process each quality option
            for (i in 0 until dataArray.length()) {
                try {
                    val item = dataArray.getJSONObject(i)
                    val filename = item.optString("filename", "Unknown")
                    val fileslug = item.optString("fileslug", "")
                    val fsize = item.optString("fsize", "")
                    
                    if (fileslug.isBlank()) continue
                    
                    // Build SSN URL
                    val ssnUrl = "https://ssn.techinmind.space/evid/$fileslug"
                    
                    Log.d("TechInMind", "Processing: $filename ($fsize) -> $ssnUrl")
                    
                    // Extract through SSN extractor
                    val ssnExtractor = SSNTechInMind()
                    ssnExtractor.getUrl(ssnUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("TechInMind", "Error processing quality item: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TechInMind", "Error: ${e.message}")
            // Try HTML fallback on error
            try {
                parseHtmlFallback(url, referer, subtitleCallback, callback)
            } catch (e2: Exception) {
                Log.e("TechInMind", "HTML fallback also failed: ${e2.message}")
            }
        }
    }
    
    // Fallback: Try to parse HTML directly (may not work if content is JS-rendered)
    private suspend fun parseHtmlFallback(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("TechInMind", "Trying HTML fallback for: $url")
        val response = app.get(url, referer = referer)
        val document = response.document
        
        // Try to find quality links in HTML
        val qualityLinks = document.select("#quality-links a[data-link], a[data-link]")
        
        Log.d("TechInMind", "HTML fallback found ${qualityLinks.size} links")
        
        qualityLinks.amap { element ->
            try {
                val dataLink = element.attr("data-link")
                val qualityName = element.text().trim()
                
                if (dataLink.isBlank()) return@amap
                
                Log.d("TechInMind", "HTML fallback quality: $qualityName -> $dataLink")
                
                val ssnExtractor = SSNTechInMind()
                ssnExtractor.getUrl(dataLink, url, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e("TechInMind", "Error in HTML fallback: ${e.message}")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// SSN TECHINMIND EXTRACTOR
// Handles: ssn.techinmind.space/evid/{id} -> ssn.techinmind.space/svid/{hash}
// ═══════════════════════════════════════════════════════════════════
class SSNTechInMind : ExtractorApi() {
    override val name = "SSN-TechInMind"
    override val mainUrl = "https://ssn.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("SSNTechInMind", "Fetching: $url")
            
            // Follow redirect to get svid page (evid/{id} -> svid/{hash})
            val response = app.get(url, referer = referer, allowRedirects = true)
            val document = response.document
            
            Log.d("SSNTechInMind", "Final URL: ${response.url}")
            
            // Extract download link - a.dlvideoLinks contains the DDN download URL
            val downloadLink = document.selectFirst("a.dlvideoLinks")?.attr("href")
            if (!downloadLink.isNullOrBlank()) {
                Log.d("SSNTechInMind", "Found download link: $downloadLink")
                // Use DDN extractor for download link
                val ddnExtractor = DDNIQSmartGames()
                ddnExtractor.getUrl(downloadLink, response.url, subtitleCallback, callback)
            }
            
            // Also extract streaming server links from li.server-item elements
            // Structure: <li class="server-item" data-link="https://...">
            val serverItems = document.select("li.server-item[data-link]")
            Log.d("SSNTechInMind", "Found ${serverItems.size} server items")
            
            serverItems.amap { item ->
                try {
                    val serverLink = item.attr("data-link")
                    val serverName = item.selectFirst(".server-name")?.text()?.trim() ?: "Unknown"
                    val serverMeta = item.selectFirst(".server-meta")?.text()?.trim() ?: ""
                    
                    if (serverLink.isBlank()) return@amap
                    
                    Log.d("SSNTechInMind", "Found server: $serverName ($serverMeta) -> $serverLink")
                    
                    // Skip download server item (first li without data-link that has the DDN URL)
                    if (serverLink.contains("iqsmartgames.com")) {
                        Log.d("SSNTechInMind", "Skipping DDN server (already processed)")
                        return@amap
                    }
                    
                    // Get extractor based on URL
                    val extractor = ExtractorFactory.getExtractor(serverLink)
                    if (extractor != null) {
                        Log.d("SSNTechInMind", "Using extractor: ${extractor.name}")
                        extractor.getUrl(serverLink, response.url, subtitleCallback, callback)
                    } else {
                        Log.w("SSNTechInMind", "No extractor found for: $serverLink")
                    }
                } catch (e: Exception) {
                    Log.e("SSNTechInMind", "Error processing server: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("SSNTechInMind", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// DDN IQSMARTGAMES EXTRACTOR
// Handles: ddn.iqsmartgames.com/file/{id} -> final download link
// ═══════════════════════════════════════════════════════════════════
class DDNIQSmartGames : ExtractorApi() {
    override val name = "DDN-Download"
    override val mainUrl = "https://ddn.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("DDNIQSmartGames", "Fetching: $url")
            
            // Follow redirect to get final page (file/{id} -> files/{hash})
            val response = app.get(url, referer = referer, allowRedirects = true)
            val html = response.text
            
            Log.d("DDNIQSmartGames", "Final URL: ${response.url}")
            
            // Extract file URL from JavaScript - handles escaped URLs like: https:\\/\\/domain.com
            // Pattern: const fileurl = "https:\/\/..."
            val fileUrlRegex = Regex("""const\s+fileurl\s*=\s*["']([^"']+)["']""")
            val fileNameRegex = Regex("""const\s+filename\s*=\s*["']([^"']+)["']""")
            
            val rawFileUrl = fileUrlRegex.find(html)?.groupValues?.get(1)
            val fileName = fileNameRegex.find(html)?.groupValues?.get(1) ?: "Unknown"
            
            // Clean up escaped URL - convert \/ to / and remove any other escapes
            val fileUrl = rawFileUrl
                ?.replace("\\/", "/")
                ?.replace("\\\\", "")
                ?.replace("\\", "")
            
            Log.d("DDNIQSmartGames", "Raw fileurl: $rawFileUrl")
            Log.d("DDNIQSmartGames", "Cleaned fileurl: $fileUrl")
            Log.d("DDNIQSmartGames", "Filename: $fileName")
            
            // Get file size from page - look for "2.46 GB" pattern in file-stat div
            val document = response.document
            val fileSizeElement = document.select("div.file-stat").find { 
                it.text().contains("File size", ignoreCase = true) 
            }
            val fileSize = fileSizeElement?.select("div")?.lastOrNull()?.text()?.trim() ?: ""
            
            Log.d("DDNIQSmartGames", "File size: $fileSize")
            
            if (!fileUrl.isNullOrBlank() && fileUrl.startsWith("http")) {
                Log.d("DDNIQSmartGames", "Found direct URL: $fileUrl")
                
                val quality = getIndexQuality(fileName)
                val adjustedQuality = getAdjustedQuality(quality, fileSize, "Direct", fileName)
                
                callback.invoke(
                    newExtractorLink(
                        "DDN[Direct]",
                        "DDN[Direct] $fileName [$fileSize]",
                        fileUrl
                    ) {
                        this.quality = adjustedQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
            // Also extract mirror links from mirror-item divs
            val mirrorItems = document.select("div.mirror-item")
            Log.d("DDNIQSmartGames", "Found ${mirrorItems.size} mirror items")
            
            mirrorItems.amap { item ->
                try {
                    val mirrorName = item.selectFirst(".mirror-name strong")?.text()?.trim() ?: "Mirror"
                    val mirrorLink = item.selectFirst("a.btn, a.mirror-btn")?.attr("href") ?: return@amap
                    
                    if (mirrorLink.isBlank() || !mirrorLink.startsWith("http")) return@amap
                    
                    Log.d("DDNIQSmartGames", "Found mirror: $mirrorName -> $mirrorLink")
                    
                    // Resolve mirror link
                    resolveMirrorLink(mirrorLink, mirrorName, fileName, fileSize, callback)
                } catch (e: Exception) {
                    Log.e("DDNIQSmartGames", "Error processing mirror: ${e.message}")
                }
            }
            
            // Extract stream button form
            val streamForm = document.selectFirst("form[action*='pro.iqsmartgames.com/stream']")
            if (streamForm != null) {
                val streamUrl = streamForm.attr("action")
                if (streamUrl.isNotBlank()) {
                    val quality = getIndexQuality(fileName)
                    val adjustedQuality = getAdjustedQuality(quality, fileSize, "Stream", fileName)
                    
                    callback.invoke(
                        newExtractorLink(
                            "DDN[Stream]",
                            "DDN[Stream] $fileName [$fileSize]",
                            streamUrl
                        ) {
                            this.quality = adjustedQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("DDNIQSmartGames", "Error: ${e.message}")
        }
    }
    
    private suspend fun resolveMirrorLink(
        url: String,
        mirrorName: String,
        fileName: String,
        fileSize: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Follow redirect to get final URL
            val response = app.get(url, allowRedirects = true)
            val finalUrl = response.url
            
            val quality = getIndexQuality(fileName)
            val adjustedQuality = getAdjustedQuality(quality, fileSize, mirrorName, fileName)
            
            callback.invoke(
                newExtractorLink(
                    "DDN[$mirrorName]",
                    "DDN[$mirrorName] $fileName [$fileSize]",
                    finalUrl
                ) {
                    this.quality = adjustedQuality
                    this.headers = VIDEO_HEADERS
                }
            )
        } catch (e: Exception) {
            Log.e("DDNIQSmartGames", "Error resolving mirror: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// PRO IQSMARTGAMES EXTRACTOR
// Handles: pro.iqsmartgames.com/stream/{id}
// ═══════════════════════════════════════════════════════════════════
class ProIQSmartGames : ExtractorApi() {
    override val name = "Pro-Stream"
    override val mainUrl = "https://pro.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("ProIQSmartGames", "Fetching: $url")
            
            val response = app.get(url, referer = referer, allowRedirects = true)
            val document = response.document
            val html = document.html()
            
            // Extract video source from script
            val sourceRegex = Regex("""source["\s:]+["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val source = sourceRegex.find(html)?.groupValues?.get(1)
            
            if (!source.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Stream",
                        source
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("ProIQSmartGames", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// MULTIMOVIES SHG EXTRACTOR
// Handles: multimoviesshg.com/e/{id}
// ═══════════════════════════════════════════════════════════════════
class MultiMoviesSHG : ExtractorApi() {
    override val name = "MultiMoviesSHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("MultiMoviesSHG", "Fetching: $url")
            
            val response = app.get(url, referer = referer)
            val html = response.text
            
            // Extract source URL from script
            val sourceRegex = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
            val source = sourceRegex.find(html)?.groupValues?.get(1)
            
            // Try alternate pattern
            val altSourceRegex = Regex("""src\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val altSource = altSourceRegex.find(html)?.groupValues?.get(1)
            
            val finalSource = source ?: altSource
            
            if (!finalSource.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Stream",
                        finalSource
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("MultiMoviesSHG", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// RPM HUB EXTRACTOR
// Handles: *.rpmhub.site
// ═══════════════════════════════════════════════════════════════════
class RpmHub : ExtractorApi() {
    override val name = "RpmHub"
    override val mainUrl = "https://rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmHub", "Fetching: $url")
            
            val response = app.get(url, referer = referer, allowRedirects = true)
            val html = response.text
            
            // Extract video source
            val sourceRegex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val source = sourceRegex.find(html)?.groupValues?.get(1)
            
            if (!source.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Stream",
                        source
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("RpmHub", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// UNS BIO EXTRACTOR
// Handles: server1.uns.bio
// ═══════════════════════════════════════════════════════════════════
class UnsBio : ExtractorApi() {
    override val name = "UnsBio"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UnsBio", "Fetching: $url")
            
            val response = app.get(url, referer = referer, allowRedirects = true)
            val html = response.text
            
            // Extract video source
            val sourceRegex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val source = sourceRegex.find(html)?.groupValues?.get(1)
            
            if (!source.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Stream",
                        source
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("UnsBio", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// P2P PLAY EXTRACTOR
// Handles: *.p2pplay.pro
// ═══════════════════════════════════════════════════════════════════
class P2pPlay : ExtractorApi() {
    override val name = "P2pPlay"
    override val mainUrl = "https://p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("P2pPlay", "Fetching: $url")
            
            val response = app.get(url, referer = referer, allowRedirects = true)
            val html = response.text
            
            // Extract video source
            val sourceRegex = Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val source = sourceRegex.find(html)?.groupValues?.get(1)
            
            if (!source.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Stream",
                        source
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = VIDEO_HEADERS + mapOf("Referer" to url)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("P2pPlay", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// GD MIRROR DOWNLOAD EXTRACTOR
// Main fallback extractor for gdmirrorbot links
// ═══════════════════════════════════════════════════════════════════
class GDMirrorDownload : ExtractorApi() {
    override val name = "GDMirror"
    override val mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDMirrorDownload", "Fetching: $url")
            
            // First, try to get embed URL if it's a stream.techinmind.space URL
            if (url.contains("stream.techinmind.space")) {
                val techExtractor = TechInMindStream()
                techExtractor.getUrl(url, referer, subtitleCallback, callback)
                return
            }
            
            // Otherwise follow redirects
            val response = app.get(url, referer = referer, allowRedirects = true)
            val finalUrl = response.url
            
            // Detect which extractor to use based on final URL
            val extractor = ExtractorFactory.getExtractor(finalUrl)
            if (extractor != null && extractor.name != this.name) {
                extractor.getUrl(finalUrl, url, subtitleCallback, callback)
            } else {
                // Direct link fallback
                val html = response.text
                val sourceRegex = Regex("""(?:file|src|source|url)\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
                val source = sourceRegex.find(html)?.groupValues?.get(1)
                
                if (!source.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name Stream",
                            source
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GDMirrorDownload", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// HUB CLOUD EXTRACTOR
// ═══════════════════════════════════════════════════════════════════
open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.day"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            var link = if (url.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if (link.isBlank()) return
            
            if (!link.startsWith("https://")) {
                link = getBaseUrl(url) + link
            }

            val document = app.get(link).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val baseQuality = getIndexQuality(header)

            div?.select("h2 a.btn")?.amap {
                val btnLink = it.attr("href")
                val text = it.text()
                val serverQuality = getAdjustedQuality(baseQuality, size, text, header)

                when {
                    text.contains("Download File") -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                btnLink,
                            ) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    btnLink.contains("pixeldra") -> {
                        val pixelBaseUrl = getBaseUrl(btnLink)
                        val finalURL = if (btnLink.contains("download", true)) btnLink
                        else "$pixelBaseUrl/api/file/${btnLink.substringAfterLast("/")}?download"

                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $header[$size]",
                                finalURL,
                            ) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    !btnLink.contains(".zip") && (btnLink.contains(".mkv") || btnLink.contains(".mp4")) -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                btnLink,
                            ) {
                                this.quality = serverQuality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Error: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// GD FLIX EXTRACTOR
// ═══════════════════════════════════════════════════════════════════
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val baseQuality = getIndexQuality(fileName)

            val allAnchors = document.select("div.text-center a")
            
            for (anchor in allAnchors) {
                val text = anchor.select("a").text()
                val link = anchor.attr("href")
                val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)
                
                // Skip ZIP servers
                if (text.contains("FAST CLOUD", true) || 
                    text.contains("ZIPDISK", true) ||
                    text.contains("ZIP", true) ||
                    link.contains(".zip", true)) {
                    continue
                }
                
                try {
                    when {
                        text.contains("Instant DL") -> {
                            val instantDownloadLink = app.get(link, allowRedirects = false)
                                .headers["location"]?.substringAfter("url=").orEmpty()
                            if (instantDownloadLink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", instantDownloadLink) {
                                        this.quality = serverQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                                return
                            }
                        }
                        text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> {
                            callback.invoke(
                                newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                    this.quality = serverQuality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                            return
                        }
                        link.contains("pixeldra") -> {
                            val baseUrlLink = getBaseUrl(link)
                            val finalURL = if (link.contains("download", true)) link
                            else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                            callback.invoke(
                                newExtractorLink("Pixeldrain", "GDFlix[Pixeldrain] $fileName[$fileSize]", finalURL) {
                                    this.quality = serverQuality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.d("GDFlix", "Error processing link: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("GDFlix", "Error: ${e.message}")
        }
    }
}
