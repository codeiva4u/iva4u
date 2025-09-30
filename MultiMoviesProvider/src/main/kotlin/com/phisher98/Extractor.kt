package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

// ============== UTILITY FUNCTIONS ==============

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url.substringBefore("/", url)
    }
}

// ============== 1. VidHide Extractor ==============
// Domains: gdmirrorbot.nl, vidhide.com, filelions.com
class VidHidePro : ExtractorApi() {
    override var name = "VidHide"
    override var mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("VidHide", "Extracting from: $url")
            
            val response = app.get(url, timeout = 15L)
            val doc = response.document
            var linksFound = 0
            
            // Extract iframe sources
            doc.select("iframe#vidFrame, iframe.video-frame").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty() && src.startsWith("http")) {
                    Log.d("VidHide", "Found iframe: $src")
                    callback.invoke(
                        newExtractorLink(
                            "VidHide",
                            "VidHide Player",
                            src
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksFound++
                }
            }
            
            // Extract server links
            doc.select("li.server-item, a.server-link").forEach { serverItem ->
                val dataLink = serverItem.attr("data-link").ifEmpty { serverItem.attr("href") }
                val serverName = serverItem.selectFirst(".server-name, span")?.text() ?: "Server"
                
                if (dataLink.isNotEmpty() && dataLink.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            "VidHide-$serverName",
                            "VidHide $serverName",
                            dataLink
                        ) {
                            this.referer = url
                            this.quality = getIndexQuality(serverName)
                            this.type = if (dataLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        }
                    )
                    linksFound++
                }
            }
            
            // Extract download links
            doc.select("a.dlvideoLinks, a[href*='download']").forEach { downloadLink ->
                val href = downloadLink.attr("href")
                if (href.isNotEmpty() && href.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            "VidHide-Download",
                            "VidHide Direct Download",
                            href
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksFound++
                }
            }
            
            Log.i("VidHide", "Successfully extracted $linksFound sources")
        } catch (e: Exception) {
            Log.e("VidHide", "Extraction failed: ${e.message}")
        }
    }
}

// ============== 2. StreamWish Extractor ==============
// Domains: streamwish.com, streamwish.to, luluvdo.com, lulu.st
open class StreamWishExtractor : ExtractorApi() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting from: $url")
            
            val baseUrl = getBaseUrl(url)
            val embedUrl = if (url.contains("/e/")) url else {
                val videoId = url.substringAfterLast("/").substringBefore("?")
                "$baseUrl/e/$videoId"
            }
            
            Log.d(name, "Embed URL: $embedUrl")
            
            val response = app.get(embedUrl, referer = url)
            val doc = response.document
            
            // Method 1: Try to extract from sources
            val sourceRegex = Regex("""sources:\s*\[\{[^\]]+file:\s*["']([^"']+)["']""")
            val sourceMatch = sourceRegex.find(doc.html())
            if (sourceMatch != null) {
                val videoUrl = sourceMatch.groupValues[1]
                Log.d(name, "Found source: $videoUrl")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
                return
            }
            
            // Method 2: Try API endpoint
            val videoId = embedUrl.substringAfterLast("/e/").substringBefore("?")
            if (videoId.isNotEmpty()) {
                try {
                    val apiUrl = "$baseUrl/api/player/setup"
                    val apiResponse = app.post(
                        apiUrl,
                        data = mapOf("id" to videoId),
                        referer = embedUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).text
                    
                    val json = JSONObject(apiResponse)
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val source = sources.getJSONObject(i)
                            val file = source.optString("file")
                            if (file.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name ${source.optString("label", "")}" .trim(),
                                        file
                                    ) {
                                        this.referer = embedUrl
                                        this.quality = getIndexQuality(source.optString("label"))
                                        this.type = if (file.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    }
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(name, "API extraction failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Extraction failed: ${e.message}")
        }
    }
}

class StreamWishCom : StreamWishExtractor() {
    override var mainUrl = "https://streamwish.com"
}

class Luluvdo : StreamWishExtractor() {
    override var name = "Luluvdo"
    override var mainUrl = "https://luluvdo.com"
}

class LuluSt : StreamWishExtractor() {
    override var name = "LuluSt"
    override var mainUrl = "https://lulu.st"
}

// ============== 3. GDToT Extractor ==============
// Domains: gdtot.pro, gdtot.top, gdtot.cfd
open class GDToTExtractor : ExtractorApi() {
    override var name = "GDToT"
    override var mainUrl = "https://gdtot.pro"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDToT", "Extracting from: $url")
            
            val baseUrl = getBaseUrl(url)
            val response = app.get(url)
            val doc = response.document
            val cookies = response.cookies
            
            // Get file info
            val fileName = doc.selectFirst("h5.page-title, h5")?.text() ?: ""
            val fileSize = doc.selectFirst("div.file-size")?.text() ?: ""
            
            Log.d("GDToT", "File: $fileName ($fileSize)")
            
            // Try to find download button/link
            val downloadBtn = doc.selectFirst("a.btn-success, button.btn-success, #download_btn")
            val downloadLink = downloadBtn?.attr("href") ?: downloadBtn?.attr("onclick")?.let {
                Regex("""['"](https?://[^'"]+)['"]""").find(it)?.groupValues?.get(1)
            }
            
            if (downloadLink != null && downloadLink.startsWith("http")) {
                Log.d("GDToT", "Found download link: $downloadLink")
                
                // Follow the link to get actual file
                val finalResponse = app.get(
                    downloadLink,
                    referer = url,
                    cookies = cookies,
                    allowRedirects = true
                )
                
                callback.invoke(
                    newExtractorLink(
                        "GDToT",
                        "GDToT [$fileSize]".trim(),
                        finalResponse.url
                    ) {
                        this.referer = url
                        this.quality = getIndexQuality(fileName)
                        this.headers = mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                    }
                )
            } else {
                // Try to extract direct Google Drive link
                val driveLink = doc.select("a[href*='drive.google.com']").attr("href")
                if (driveLink.isNotEmpty()) {
                    Log.d("GDToT", "Found Google Drive link")
                    callback.invoke(
                        newExtractorLink(
                            "GDToT-Drive",
                            "GDToT Drive [$fileSize]".trim(),
                            driveLink
                        ) {
                            this.referer = url
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GDToT", "Extraction failed: ${e.message}")
        }
    }
}

class GDToTPro : GDToTExtractor() {
    override var mainUrl = "https://gdtot.pro"
}

class GDToTTop : GDToTExtractor() {
    override var mainUrl = "https://gdtot.top"
}

// ============== 4. FilePress Extractor ==============
// Domains: filepress.store, filepress.click
open class FilePressExtractor : ExtractorApi() {
    override var name = "FilePress"
    override var mainUrl = "https://filepress.store"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("FilePress", "Extracting from: $url")
            
            val baseUrl = getBaseUrl(url)
            val response = app.get(url)
            val doc = response.document
            
            // Extract file info
            val fileName = doc.selectFirst("h1, .file-name")?.text() ?: ""
            val fileSize = doc.selectFirst(".file-size")?.text() ?: ""
            
            Log.d("FilePress", "File: $fileName ($fileSize)")
            
            // Try to find file ID and token from script
            val scriptContent = doc.select("script").joinToString("\n") { it.html() }
            val fileIdMatch = Regex("""file[_-]?id["']?\s*[:=]\s*["']([^"']+)["']""").find(scriptContent)
            val tokenMatch = Regex("""token["']?\s*[:=]\s*["']([^"']+)["']""").find(scriptContent)
            
            if (fileIdMatch != null) {
                val fileId = fileIdMatch.groupValues[1]
                val token = tokenMatch?.groupValues?.get(1) ?: ""
                
                Log.d("FilePress", "Found file ID: $fileId")
                
                // Call API to get download link
                try {
                    val apiUrl = "$baseUrl/api/file/direct_download"
                    val apiResponse = app.post(
                        apiUrl,
                        data = mapOf("id" to fileId, "token" to token),
                        referer = url,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        )
                    ).text
                    
                    val json = JSONObject(apiResponse)
                    val downloadUrl = json.optString("url")
                    
                    if (downloadUrl.isNotEmpty() && downloadUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                "FilePress",
                                "FilePress [$fileSize]".trim(),
                                downloadUrl
                            ) {
                                this.referer = url
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    }
                } catch (e: Exception) {
                    Log.e("FilePress", "API call failed: ${e.message}")
                }
            }
            
            // Fallback: Try to find direct download link
            val directLink = doc.selectFirst("a.btn-download, a[href*='download']")?.attr("href")
            if (directLink != null && directLink.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        "FilePress",
                        "FilePress [$fileSize]".trim(),
                        directLink
                    ) {
                        this.referer = url
                        this.quality = getIndexQuality(fileName)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("FilePress", "Extraction failed: ${e.message}")
        }
    }
}

class FilePressStore : FilePressExtractor() {
    override var mainUrl = "https://filepress.store"
}

class FilePressClick : FilePressExtractor() {
    override var mainUrl = "https://filepress.click"
}

// ============== 5. HubCloud Extractor ==============
// Domains: hubcloud.ink, hubcloud.art, hubcloud.dad, hubcloud.bz, hubcloud.one
open class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Extracting from: $url")
            
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Log.e("HubCloud", "Invalid URL")
                return
            }
            
            val baseUrl = getBaseUrl(url)
            val response = app.get(url)
            val doc = response.document
            
            // Get download link
            val downloadLink = doc.selectFirst("div.vd > center > a, #download, a.btn-download")?.attr("href") ?: ""
            val fullDownloadLink = if (downloadLink.startsWith("/")) {
                "$baseUrl$downloadLink"
            } else {
                downloadLink
            }
            
            if (fullDownloadLink.isEmpty()) {
                Log.w("HubCloud", "No download link found")
                return
            }
            
            Log.d("HubCloud", "Download page: $fullDownloadLink")
            
            val downloadPage = app.get(fullDownloadLink)
            val downloadDoc = downloadPage.document
            
            val header = downloadDoc.select("div.card-header").text()
            val size = downloadDoc.select("i#size").text()
            
            Log.d("HubCloud", "File: $header [$size]")
            
            // Extract all server links
            downloadDoc.select("div.card-body h2 a.btn").amap { element ->
                val link = element.attr("href")
                val text = element.text()
                
                when {
                    text.contains("FSL Server", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud-FSL",
                                "HubCloud FSL Server [$size]",
                                link
                            ) {
                                this.referer = url
                                this.quality = getIndexQuality(header)
                            }
                        )
                    }
                    
                    text.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud",
                                "HubCloud [$size]",
                                link
                            ) {
                                this.referer = url
                                this.quality = getIndexQuality(header)
                            }
                        )
                    }
                    
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val buzzLink = app.get(
                                "$link/download",
                                referer = link,
                                allowRedirects = false
                            ).headers["hx-redirect"] ?: ""
                            
                            if (buzzLink.isNotEmpty()) {
                                val fullBuzzLink = if (buzzLink.startsWith("/")) {
                                    getBaseUrl(link) + buzzLink
                                } else {
                                    buzzLink
                                }
                                callback.invoke(
                                    newExtractorLink(
                                        "HubCloud-Buzz",
                                        "HubCloud BuzzServer [$size]",
                                        fullBuzzLink
                                    ) {
                                        this.referer = url
                                        this.quality = getIndexQuality(header)
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "BuzzServer extraction failed: ${e.message}")
                        }
                    }
                    
                    link.contains("pixeldra", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain [$size]",
                                link
                            ) {
                                this.referer = url
                                this.quality = getIndexQuality(header)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Extraction failed: ${e.message}")
        }
    }
}

class HubCloudInk : HubCloudExtractor() {
    override val mainUrl = "https://hubcloud.ink"
}

class HubCloudArt : HubCloudExtractor() {
    override val mainUrl = "https://hubcloud.art"
}

class HubCloudDad : HubCloudExtractor() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudBz : HubCloudExtractor() {
    override val mainUrl = "https://hubcloud.bz"
}

// ============== 6. GoFile Extractor ==============
// Domain: gofile.io
class GoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GoFile", "Extracting from: $url")
            
            // Extract content ID from URL
            val contentId = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1)
            if (contentId == null) {
                Log.e("GoFile", "Could not extract content ID")
                return
            }
            
            Log.d("GoFile", "Content ID: $contentId")
            
            // Create account to get token
            val accountResponse = app.post("$mainApi/accounts").text
            val accountJson = JSONObject(accountResponse)
            val token = accountJson.getJSONObject("data").getString("token")
            
            Log.d("GoFile", "Got token")
            
            // Get website token (wt)
            val globalJs = app.get("$mainUrl/dist/js/global.js").text
            val wt = Regex("""appdata\\.wt\\s*=\\s*["']([^"']+)["']""").find(globalJs)?.groupValues?.get(1)
            
            if (wt == null) {
                Log.e("GoFile", "Could not get wt token")
                return
            }
            
            // Get content
            val contentUrl = "$mainApi/contents/$contentId?wt=$wt"
            val contentResponse = app.get(
                contentUrl,
                headers = mapOf("Authorization" to "Bearer $token")
            ).text
            
            val contentJson = JSONObject(contentResponse)
            val data = contentJson.getJSONObject("data")
            val children = data.getJSONObject("children")
            
            // Process all video files
            val keys = children.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val fileObj = children.getJSONObject(fileId)
                val fileType = fileObj.optString("type", "")
                
                if (fileType == "file") {
                    val fileName = fileObj.getString("name")
                    val link = fileObj.getString("link")
                    val fileSize = fileObj.optLong("size", 0)
                    
                    val sizeFormatted = if (fileSize < 1024L * 1024 * 1024) {
                        "%.2f MB".format(fileSize / 1024.0 / 1024)
                    } else {
                        "%.2f GB".format(fileSize / 1024.0 / 1024 / 1024)
                    }
                    
                    Log.d("GoFile", "Found file: $fileName ($sizeFormatted)")
                    
                    callback.invoke(
                        newExtractorLink(
                            "GoFile",
                            "GoFile [$sizeFormatted]",
                            link
                        ) {
                            this.referer = url
                            this.quality = getIndexQuality(fileName)
                            this.headers = mapOf("Cookie" to "accountToken=$token")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("GoFile", "Extraction failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
