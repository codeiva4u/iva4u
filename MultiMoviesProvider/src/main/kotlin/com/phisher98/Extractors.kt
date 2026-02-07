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

// ═══════════════════════════════════════════════════════════════════════════════
// MULTIMOVIES EXTRACTORS - DOWNLOAD LINKS ONLY (NO M3U8/HLS STREAMING)
// ═══════════════════════════════════════════════════════════════════════════════

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

// Quality detection from filename
fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// Parse file size string to MB
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

// ═══════════════════════════════════════════════════════════════════════════════
// QUALITY PRIORITY SYSTEM:
// 1. X264 1080p > X264 720p > HEVC 1080p > HEVC 720p
// 2. Within same quality: smaller file size preferred
// 3. Within same size: faster server preferred
// ═══════════════════════════════════════════════════════════════════════════════
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()
    
    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
    
    // PRIORITY 1: Codec + Quality Group
    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000  // X264 1080p
        isX264 && quality >= 720  -> 20000  // X264 720p
        isHEVC && quality >= 1080 -> 10000  // HEVC 1080p
        isHEVC && quality >= 720  -> 9000   // HEVC 720p
        quality >= 1080 -> 8000
        quality >= 720  -> 7000
        quality >= 480  -> 6000
        else -> 5000
    }
    
    // PRIORITY 2: File Size (smaller = higher score)
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
    
    // PRIORITY 3: Server Speed
    val serverScore = getServerPriority(serverName)
    
    return codecQualityScore + sizeScore + serverScore
}

fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100
        serverName.contains("Direct", true) -> 95
        serverName.contains("Workers", true) -> 90
        serverName.contains("Stream", true) -> 85
        serverName.contains("FSL", true) -> 80
        serverName.contains("10Gbps", true) -> 85
        serverName.contains("Buzzheavier", true) -> 70
        serverName.contains("Fpress", true) -> 65
        serverName.contains("Rpmshare", true) -> 60
        serverName.contains("Upnshare", true) -> 55
        serverName.contains("Streamp2p", true) -> 50
        else -> 40
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

// ═══════════════════════════════════════════════════════════════════════════════
// EXTRACTOR FACTORY - Selects appropriate extractor based on URL
// ═══════════════════════════════════════════════════════════════════════════════
object ExtractorFactory {
    fun getExtractor(url: String): ExtractorApi? {
        return when {
            url.contains("techinmind.space") -> TechInMindStream()
            url.contains("ssn.techinmind.space") -> TechInMindSSN()
            url.contains("ddn.iqsmartgames.com") -> GDMirrorDownload()
            url.contains("pro.iqsmartgames.com") -> IQSmartStream()
            url.contains("jakcminasi.workers.dev") -> WorkersDownload()
            url.contains("multimoviesshg.com") -> StreamHGExtractor()
            url.contains("rpmhub.site") || url.contains("multimovies.rpmhub") -> RpmShareExtractor()
            url.contains("uns.bio") || url.contains("server1.uns.bio") -> UpnShareExtractor()
            url.contains("p2pplay.pro") || url.contains("multimovies.p2pplay") -> StreamP2PExtractor()
            url.contains("buzzheavier") -> BuzzheavierExtractor()
            url.contains("fpress") -> FpressExtractor()
            else -> null
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TECHINMIND STREAM EXTRACTOR
// Handles: stream.techinmind.space/embed/movie/{imdb}
// ═══════════════════════════════════════════════════════════════════════════════
class TechInMindStream : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMind", "Extracting from: $url")
            
            val doc = app.get(url, referer = referer ?: mainUrl).document
            
            // Extract quality links from menu
            val qualityLinks = doc.select("#quality-links a[data-link]")
            
            qualityLinks.forEach { link ->
                val videoUrl = link.attr("data-link")
                val qualityName = link.text().trim()
                
                if (videoUrl.isNotEmpty()) {
                    Log.d("TechInMind", "Found quality: $qualityName -> $videoUrl")
                    
                    // Use SSN extractor for ssn.techinmind.space URLs
                    if (videoUrl.contains("ssn.techinmind.space")) {
                        TechInMindSSN().getUrl(videoUrl, url, subtitleCallback, callback)
                    }
                }
            }
            
            // Also try iframe src
            val iframeSrc = doc.select("iframe#player").attr("src")
            if (iframeSrc.isNotEmpty() && iframeSrc.contains("ssn.techinmind.space")) {
                TechInMindSSN().getUrl(iframeSrc, url, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            Log.e("TechInMind", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TECHINMIND SSN EXTRACTOR  
// Handles: ssn.techinmind.space/evid/{slug} and /svid/{slug}
// ═══════════════════════════════════════════════════════════════════════════════
class TechInMindSSN : ExtractorApi() {
    override val name = "TechInMindSSN"
    override val mainUrl = "https://ssn.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("TechInMindSSN", "Extracting from: $url")
            
            // Follow redirect from /evid/ to /svid/
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url
            
            Log.d("TechInMindSSN", "Final URL: $finalUrl")
            
            // Extract video servers from the player page
            val videoLinks = doc.select("#videoLinks li.server-item")
            
            videoLinks.forEach { serverItem ->
                val serverLink = serverItem.attr("data-link")
                val serverName = serverItem.select(".server-name").text().trim()
                val serverMeta = serverItem.select(".server-meta").text().trim()
                
                Log.d("TechInMindSSN", "Server: $serverName - $serverLink")
                
                when {
                    serverName.contains("SMWH", true) || serverLink.contains("multimoviesshg.com") -> {
                        StreamHGExtractor().getUrl(serverLink, finalUrl, subtitleCallback, callback)
                    }
                    serverName.contains("RPMSHRE", true) || serverLink.contains("rpmhub.site") -> {
                        RpmShareExtractor().getUrl(serverLink, finalUrl, subtitleCallback, callback)
                    }
                    serverName.contains("UPNSHR", true) || serverLink.contains("uns.bio") -> {
                        UpnShareExtractor().getUrl(serverLink, finalUrl, subtitleCallback, callback)
                    }
                    serverName.contains("STRMP2", true) || serverLink.contains("p2pplay.pro") -> {
                        StreamP2PExtractor().getUrl(serverLink, finalUrl, subtitleCallback, callback)
                    }
                }
            }
            
            // Extract direct download link
            val downloadLink = doc.select("a.dlvideoLinks").attr("href")
            if (downloadLink.isNotEmpty() && downloadLink.contains("ddn.iqsmartgames.com")) {
                Log.d("TechInMindSSN", "Download link found: $downloadLink")
                GDMirrorDownload().getUrl(downloadLink, finalUrl, subtitleCallback, callback)
            }
            
        } catch (e: Exception) {
            Log.e("TechInMindSSN", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// GDMIRROR DOWNLOAD EXTRACTOR
// Handles: ddn.iqsmartgames.com/file/{slug} and /files/{encrypted}
// ═══════════════════════════════════════════════════════════════════════════════
class GDMirrorDownload : ExtractorApi() {
    override val name = "GDMirror"
    override val mainUrl = "https://ddn.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDMirror", "Extracting from: $url")
            
            // Follow redirects to get final page
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            val finalUrl = response.url
            
            // Extract filename and direct download URL from JavaScript
            val scripts = doc.select("script").map { it.html() }
            
            var directUrl: String? = null
            var fileName: String? = null
            var fileSize: String? = null
            
            scripts.forEach { script ->
                // Extract fileurl
                val fileurlMatch = Regex("""const\s+fileurl\s*=\s*"([^"]+)"""").find(script)
                if (fileurlMatch != null) {
                    directUrl = fileurlMatch.groupValues[1]
                        .replace("\\/", "/")
                        .replace("\\\"", "\"")
                }
                
                // Extract filename
                val filenameMatch = Regex("""const\s+filename\s*=\s*"([^"]+)"""").find(script)
                if (filenameMatch != null) {
                    fileName = filenameMatch.groupValues[1]
                }
            }
            
            // Get file size from page
            fileSize = doc.select(".file-stat:contains(File size) div:last-child").text().trim()
            
            if (!directUrl.isNullOrEmpty()) {
                Log.d("GDMirror", "Direct URL: $directUrl")
                Log.d("GDMirror", "Filename: $fileName")
                Log.d("GDMirror", "Size: $fileSize")
                
                val quality = getIndexQuality(fileName)
                val adjustedQuality = getAdjustedQuality(quality, fileSize ?: "", "Workers", fileName ?: "")
                
                callback.invoke(
                    newExtractorLink(
                        "GDMirror[Direct]",
                        "GDMirror[Direct] ${fileName ?: ""}[${fileSize ?: ""}]",
                        directUrl!!,
                    ) {
                        this.quality = adjustedQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
            // Also extract mirror links
            val mirrorItems = doc.select(".mirror-item")
            mirrorItems.amap { mirror ->
                val mirrorName = mirror.select(".mirror-name strong").text().trim()
                val visitLink = mirror.select("a[href*=vpage]").attr("href")
                
                if (visitLink.isNotEmpty()) {
                    Log.d("GDMirror", "Mirror: $mirrorName -> $visitLink")
                    
                    try {
                        when {
                            mirrorName.contains("Buzzheavier", true) -> {
                                extractMirrorLink(visitLink, mirrorName, fileName, fileSize, callback)
                            }
                            mirrorName.contains("Fpress", true) -> {
                                extractMirrorLink(visitLink, mirrorName, fileName, fileSize, callback)
                            }
                            mirrorName.contains("Rpmshare", true) -> {
                                extractMirrorLink(visitLink, mirrorName, fileName, fileSize, callback)
                            }
                            mirrorName.contains("Upnshare", true) -> {
                                extractMirrorLink(visitLink, mirrorName, fileName, fileSize, callback)
                            }
                            mirrorName.contains("Streamp2p", true) -> {
                                extractMirrorLink(visitLink, mirrorName, fileName, fileSize, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("GDMirror", "Mirror extraction failed for $mirrorName: ${e.message}")
                    }
                }
            }
            
            // Extract stream link
            val streamForm = doc.select("form[action*='stream']")
            if (streamForm.isNotEmpty()) {
                val streamUrl = streamForm.attr("action")
                if (streamUrl.isNotEmpty()) {
                    Log.d("GDMirror", "Stream URL: $streamUrl")
                    
                    val quality = getIndexQuality(fileName)
                    val adjustedQuality = getAdjustedQuality(quality, fileSize ?: "", "Stream", fileName ?: "")
                    
                    callback.invoke(
                        newExtractorLink(
                            "GDMirror[Stream]",
                            "GDMirror[Stream] ${fileName ?: ""}[${fileSize ?: ""}]",
                            streamUrl,
                        ) {
                            this.quality = adjustedQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e("GDMirror", "Extraction failed: ${e.message}")
        }
    }
    
    private suspend fun extractMirrorLink(
        visitLink: String,
        mirrorName: String,
        fileName: String?,
        fileSize: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Follow vpage redirect to get actual mirror link
            val response = app.get(visitLink, allowRedirects = true)
            val finalUrl = response.url
            
            if (finalUrl != visitLink && (finalUrl.contains(".mkv") || finalUrl.contains(".mp4") || 
                finalUrl.contains("download") || finalUrl.contains("file"))) {
                
                val quality = getIndexQuality(fileName)
                val adjustedQuality = getAdjustedQuality(quality, fileSize ?: "", mirrorName, fileName ?: "")
                
                callback.invoke(
                    newExtractorLink(
                        "GDMirror[$mirrorName]",
                        "GDMirror[$mirrorName] ${fileName ?: ""}[${fileSize ?: ""}]",
                        finalUrl,
                    ) {
                        this.quality = adjustedQuality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("GDMirror", "Mirror link extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WORKERS DOWNLOAD EXTRACTOR
// Handles: *.workers.dev direct download links
// ═══════════════════════════════════════════════════════════════════════════════
class WorkersDownload : ExtractorApi() {
    override val name = "Workers"
    override val mainUrl = "https://jakcminasi.workers.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Workers", "Direct download URL: $url")
            
            // Extract filename from URL parameter
            val fileName = Regex("""name=([^&]+)""").find(url)?.groupValues?.get(1)
                ?.replace("%20", " ")
                ?.replace("+", " ")
            
            val quality = getIndexQuality(fileName)
            val adjustedQuality = getAdjustedQuality(quality, "", "Workers", fileName ?: "")
            
            callback.invoke(
                newExtractorLink(
                    "Workers[Direct]",
                    "Workers[Direct] ${fileName ?: "Video"}",
                    url,
                ) {
                    this.quality = adjustedQuality
                    this.headers = VIDEO_HEADERS
                }
            )
            
        } catch (e: Exception) {
            Log.e("Workers", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// IQSMART STREAM EXTRACTOR
// Handles: pro.iqsmartgames.com/stream/{slug}
// ═══════════════════════════════════════════════════════════════════════════════
class IQSmartStream : ExtractorApi() {
    override val name = "IQSmart"
    override val mainUrl = "https://pro.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("IQSmart", "Stream URL: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val finalUrl = response.url
            
            if (finalUrl != url) {
                Log.d("IQSmart", "Redirected to: $finalUrl")
                
                callback.invoke(
                    newExtractorLink(
                        "IQSmart[Stream]",
                        "IQSmart[Stream]",
                        finalUrl,
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("IQSmart", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMHG EXTRACTOR
// Handles: multimoviesshg.com/e/{id}
// ═══════════════════════════════════════════════════════════════════════════════
class StreamHGExtractor : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamHG", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl)
            val doc = response.document
            
            // Look for video source in page
            val scripts = doc.select("script").map { it.html() }
            
            scripts.forEach { script ->
                // Extract file/source URL
                val fileMatch = Regex("""(?:file|source|src)\s*[:=]\s*["']([^"']+\.(?:mp4|mkv|m4v)[^"']*)["']""").find(script)
                if (fileMatch != null) {
                    val videoUrl = fileMatch.groupValues[1]
                    Log.d("StreamHG", "Found video URL: $videoUrl")
                    
                    callback.invoke(
                        newExtractorLink(
                            "StreamHG",
                            "StreamHG",
                            videoUrl,
                        ) {
                            this.quality = Qualities.P1080.value
                            this.referer = url
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
            
            // Fallback: look for direct video tag
            val videoSrc = doc.select("video source[src]").attr("src")
            if (videoSrc.isNotEmpty()) {
                callback.invoke(
                    newExtractorLink(
                        "StreamHG",
                        "StreamHG",
                        videoSrc,
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = url
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("StreamHG", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RPMSHARE EXTRACTOR
// Handles: multimovies.rpmhub.site/#{hash}
// ═══════════════════════════════════════════════════════════════════════════════
class RpmShareExtractor : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://multimovies.rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("RpmShare", "Extracting from: $url")
            
            val hash = url.substringAfter("#")
            val response = app.get(url, referer = referer ?: mainUrl)
            val doc = response.document
            
            // Look for video source
            val scripts = doc.select("script").map { it.html() }
            
            scripts.forEach { script ->
                val sourceMatch = Regex("""(?:source|file|src)\s*[:=]\s*["']([^"']+)["']""").find(script)
                if (sourceMatch != null) {
                    val videoUrl = sourceMatch.groupValues[1]
                    if (videoUrl.contains(".mp4") || videoUrl.contains(".mkv") || videoUrl.contains("download")) {
                        Log.d("RpmShare", "Found video URL: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                "RpmShare",
                                "RpmShare",
                                videoUrl,
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = url
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("RpmShare", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UPNSHARE EXTRACTOR
// Handles: server1.uns.bio/#{hash}
// ═══════════════════════════════════════════════════════════════════════════════
class UpnShareExtractor : ExtractorApi() {
    override val name = "UpnShare"
    override val mainUrl = "https://server1.uns.bio"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("UpnShare", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl)
            val doc = response.document
            
            val scripts = doc.select("script").map { it.html() }
            
            scripts.forEach { script ->
                val sourceMatch = Regex("""(?:source|file|src)\s*[:=]\s*["']([^"']+)["']""").find(script)
                if (sourceMatch != null) {
                    val videoUrl = sourceMatch.groupValues[1]
                    if (videoUrl.contains(".mp4") || videoUrl.contains(".mkv") || videoUrl.contains("download")) {
                        Log.d("UpnShare", "Found video URL: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                "UpnShare",
                                "UpnShare",
                                videoUrl,
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = url
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("UpnShare", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMP2P EXTRACTOR
// Handles: multimovies.p2pplay.pro/#{hash}
// ═══════════════════════════════════════════════════════════════════════════════
class StreamP2PExtractor : ExtractorApi() {
    override val name = "StreamP2P"
    override val mainUrl = "https://multimovies.p2pplay.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("StreamP2P", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl)
            val doc = response.document
            
            val scripts = doc.select("script").map { it.html() }
            
            scripts.forEach { script ->
                val sourceMatch = Regex("""(?:source|file|src)\s*[:=]\s*["']([^"']+)["']""").find(script)
                if (sourceMatch != null) {
                    val videoUrl = sourceMatch.groupValues[1]
                    if (videoUrl.contains(".mp4") || videoUrl.contains(".mkv") || videoUrl.contains("download")) {
                        Log.d("StreamP2P", "Found video URL: $videoUrl")
                        
                        callback.invoke(
                            newExtractorLink(
                                "StreamP2P",
                                "StreamP2P",
                                videoUrl,
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = url
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("StreamP2P", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// BUZZHEAVIER EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class BuzzheavierExtractor : ExtractorApi() {
    override val name = "Buzzheavier"
    override val mainUrl = "https://buzzheavier.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Buzzheavier", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            // Look for download link
            val downloadLink = doc.select("a[href*=download], a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val finalUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                
                callback.invoke(
                    newExtractorLink(
                        "Buzzheavier",
                        "Buzzheavier",
                        finalUrl,
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Buzzheavier", "Extraction failed: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FPRESS EXTRACTOR
// ═══════════════════════════════════════════════════════════════════════════════
class FpressExtractor : ExtractorApi() {
    override val name = "Fpress"
    override val mainUrl = "https://fpress.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Fpress", "Extracting from: $url")
            
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val doc = response.document
            
            val downloadLink = doc.select("a[href*=download], a:contains(Download)").attr("href")
            
            if (downloadLink.isNotEmpty()) {
                val finalUrl = if (downloadLink.startsWith("http")) downloadLink else "$mainUrl$downloadLink"
                
                callback.invoke(
                    newExtractorLink(
                        "Fpress",
                        "Fpress",
                        finalUrl,
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            
        } catch (e: Exception) {
            Log.e("Fpress", "Extraction failed: ${e.message}")
        }
    }
}
