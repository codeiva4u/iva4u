package com.moviesdrive

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

val VIDEO_HEADERS = mapOf(
    "User-Agent" to "VLC/3.6.0 LibVLC/3.0.18 (Android)",
    "Accept" to "*/*",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Range" to "bytes=0-",
    "Icy-MetaData" to "1"
)

fun getIndexQuality(str: String?): Int {
    return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
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
// SAHI PRIORITY ORDER (Cloudstream - higher number = shown first):
// 
// 1️⃣ पहली: Codec + Quality (X264 1080p > X264 720p > HEVC 1080p)
// 2️⃣ दूसरी: उस quality group के भीतर सबसे छोटी file
// 3️⃣ तीसरी: उस quality+size के भीतर सबसे तेज़ server
//
// Example correct order:
//   X264 1080p 400MB Instant = 30000 + 250 + 100 = 30350 ✅ #1
//   X264 1080p 800MB Direct  = 30000 + 200 + 90  = 30290    #2
//   X264 1080p 1.5GB FSL     = 30000 + 100 + 80  = 30180    #3
//   X264 720p 300MB Instant  = 20000 + 260 + 100 = 20360    #4
//   X264 720p 600MB Direct   = 20000 + 220 + 90  = 20310    #5
//   HEVC 1080p 400MB Instant = 10000 + 250 + 100 = 10350    #6
//   HEVC 1080p 800MB Direct  = 10000 + 200 + 90  = 10290    #7
// ═══════════════════════════════════════════════════════════════════
fun getAdjustedQuality(quality: Int, sizeStr: String, serverName: String = "", fileName: String = ""): Int {
    val text = (fileName + sizeStr + serverName).lowercase()
    
    // Detect codec: X264 vs HEVC
    val isHEVC = text.contains("hevc") || text.contains("x265") || text.contains("h265") || text.contains("h.265")
    val isX264 = text.contains("x264") || text.contains("h264") || text.contains("h.264")
    
    // ★★★ PRIORITY 1: Codec + Quality Group (10,000 points per group) ★★★
    val codecQualityScore = when {
        isX264 && quality >= 1080 -> 30000  // ✅ Group 1: X264 1080p (30000-30999)
        isX264 && quality >= 720  -> 20000  // ✅ Group 2: X264 720p (20000-20999)
        isHEVC && quality >= 1080 -> 10000  // ✅ Group 3: HEVC 1080p (10000-10999)
        isHEVC && quality >= 720  -> 9000   //    Group 4: HEVC 720p
        quality >= 1080 -> 8000             //    Unknown 1080p
        quality >= 720  -> 7000             //    Unknown 720p
        quality >= 480  -> 6000             //    480p
        else -> 5000                        //    Unknown
    }
    
    // ★★★ PRIORITY 2: File Size (max 300 points within group) ★★★
    // Smaller file = HIGHER score (inverse of file size)
    val sizeMB = parseSizeToMB(sizeStr)
    val sizeScore = when {
        sizeMB <= 300  -> 260  // 300MB = +260
        sizeMB <= 400  -> 250  // 400MB = +250
        sizeMB <= 500  -> 240  // 500MB = +240
        sizeMB <= 600  -> 230  // 600MB = +230
        sizeMB <= 700  -> 220  // 700MB = +220
        sizeMB <= 800  -> 210  // 800MB = +210
        sizeMB <= 900  -> 200  // 900MB = +200
        sizeMB <= 1000 -> 190  // 1GB = +190
        sizeMB <= 1200 -> 170  // 1.2GB = +170
        sizeMB <= 1500 -> 140  // 1.5GB = +140
        sizeMB <= 2000 -> 100  // 2GB = +100
        sizeMB <= 2500 -> 60   // 2.5GB = +60
        sizeMB <= 3000 -> 20   // 3GB = +20
        else -> 0              // >3GB = no bonus
    }
    
    // ★★★ PRIORITY 3: Server Speed (max 100 points) ★★★
    val serverScore = getServerPriority(serverName)
    
    return codecQualityScore + sizeScore + serverScore
}

// Server speed priority (higher = faster/preferred)
fun getServerPriority(serverName: String): Int {
    return when {
        serverName.contains("Instant", true) -> 100  // Instant DL = fastest
        serverName.contains("Direct", true) -> 90
        serverName.contains("FSL", true) -> 80
        serverName.contains("10Gbps", true) -> 85
        serverName.contains("Download File", true) -> 70
        serverName.contains("Pixeldrain", true) -> 60
        else -> 50
    }
}

// Cached URLs for session-level caching (fetch once, use throughout session)
private var cachedUrlsJson: JSONObject? = null

suspend fun getLatestUrl(url: String, source: String): String {
    // Use cached JSON if available (fetch only once per session)
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
            Log.d("DomainResolver", "Successfully fetched domains from GitHub")
        } catch (e: Exception) {
            Log.d("DomainResolver", "Failed to fetch domain from GitHub: ${e.message}, using fallback")
            return getBaseUrl(url)
        }
    }
    
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        Log.d("DomainResolver", "No domain found for source: $source, using base URL")
        return getBaseUrl(url)
    }
    Log.d("DomainResolver", "Using domain for $source: $link")
    return link
}
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
            val serverQuality = getAdjustedQuality(baseQuality, size, text, header)

            if (text.contains("[FSL Server]"))
            {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSL Server]",
                        "$name[FSL Server] $header[$size]",
                        btnLink,
                    ) {
                        this.quality = serverQuality
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
                        this.quality = serverQuality
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
                        this.quality = serverQuality
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
                        this.quality = serverQuality
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
                            this.quality = serverQuality
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
                        this.quality = serverQuality
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
                        this.quality = serverQuality
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
                            this.quality = serverQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }
            }
        }
    }
}

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink.*"
}
class GDFlixNet : GDFlix() {
    override var mainUrl = "https://new10.gdflix.*"
}
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val latestUrl = getLatestUrl(url, "gdflix")
        val baseUrl = getBaseUrl(url)
        val newUrl = url.replace(baseUrl, latestUrl)
        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val baseQuality = getIndexQuality(fileName)

        // Track if we found a fast link (to skip slow fallbacks)
        var fastLinkFound = false
        
        // Collect all anchors and categorize them
        val anchors = document.select("div.text-center a")
        
        // PHASE 1: Process FAST servers first (DIRECT DL, Instant DL, CLOUD DOWNLOAD, Pixeldrain)
        anchors.forEach { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)
            
            when {
                text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> {
                    fastLinkFound = true
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = serverQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    fastLinkFound = true
                    val cloudLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                    callback.invoke(
                        newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", cloudLink) {
                            this.quality = serverQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                link.contains("pixeldra") -> {
                    fastLinkFound = true
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                    callback.invoke(
                        newExtractorLink(
                            "Pixeldrain",
                            "GDFlix[Pixeldrain] $fileName[$fileSize]",
                            finalURL
                        ) {
                            this.quality = serverQuality
                            this.headers = VIDEO_HEADERS
                        }
                    )
                }

                text.contains("Instant DL") -> {
                    try {
                        val instantDownloadLink = app.get(link, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()
                        if (instantDownloadLink.isNotEmpty()) {
                            fastLinkFound = true
                            callback.invoke(
                                newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", instantDownloadLink) {
                                    this.quality = serverQuality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }
            }
        }
        
        // PHASE 2: If fast links found, skip slow fallbacks (DRIVEBOT, Index Links, GoFile)
        // This is the key optimization - avoid 10+ minute delays
        if (fastLinkFound) {
            Log.d("GDFlix", "Fast links found, skipping slow fallbacks")
            return
        }
        
        // PHASE 3: Only process slow servers if no fast links were found
        Log.d("GDFlix", "No fast links, trying slow fallbacks...")
        
        anchors.forEach { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")
            val serverQuality = getAdjustedQuality(baseQuality, fileSize, text, fileName)
            
            when {
                text.contains("DRIVEBOT") -> {
                    try {
                        val id = link.substringAfter("id=").substringBefore("&")
                        val doId = link.substringAfter("do=").substringBefore("==")
                        // Only try first base URL to save time (reduced from 2 to 1)
                        val driveBotBaseUrl = "https://drivebot.sbs"
                        
                        val indexbotLink = "$driveBotBaseUrl/download?id=$id&do=$doId"
                        // Reduced timeout from 100L to 30L
                        val indexbotResponse = app.get(indexbotLink, timeout = 30L)

                        if (indexbotResponse.isSuccessful) {
                            val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                            val indexbotDoc = indexbotResponse.document

                            val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                            val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                            val requestBody = FormBody.Builder()
                                .add("token", token)
                                .build()

                            val postHeaders = mapOf("Referer" to indexbotLink)
                            val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                            val downloadLink = app.post(
                                "$driveBotBaseUrl/download?id=$postId",
                                requestBody = requestBody,
                                headers = postHeaders,
                                cookies = cookies,
                                timeout = 30L  // Reduced timeout
                            ).text.let {
                                Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                            }

                            if (downloadLink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                        this.referer = driveBotBaseUrl
                                        this.quality = serverQuality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                text.contains("Index Links") -> {
                    try {
                        // Limit to first 2 buttons only (was processing all)
                        app.get("$latestUrl$link").document
                            .select("a.btn.btn-outline-info").take(2).forEach { btn ->
                                val serverUrl = latestUrl + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").firstOrNull()?.let { sourceAnchor ->
                                        val source = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                this.quality = serverQuality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

                text.contains("GoFile") -> {
                    try {
                        app.get(link).document
                            .select(".row .row a").firstOrNull()?.let { gofileAnchor ->
                                val gofileLink = gofileAnchor.attr("href")
                                if (gofileLink.contains("gofile")) {
                                    Gofile().getUrl(gofileLink, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }
            }
        }
    }
}


class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.*"
    override val requiresReferer = false

    @Suppress("UNUSED_PARAMETER")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fetch latest domain from GitHub JSON (real-time API)
        val latestMainUrl = getLatestUrl(url, "gofile")
        // Derive API URL from main URL: gofile.io -> api.gofile.io
        val latestApiUrl = latestMainUrl.replace("://", "://api.")
        
        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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
                this.headers = VIDEO_HEADERS + mapOf(
                    "Cookie" to "accountToken=$token"
                )
            }
        )
    }

    private fun getQuality(str: String?): Int {
        return Regex("""(\d{3,4})[pP]""").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

