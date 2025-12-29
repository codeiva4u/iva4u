package com.megix

import android.os.Build
import androidx.annotation.RequiresApi
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Gofile
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

suspend fun getLatestUrl(url: String, source: String): String {
    val link = JSONObject(
        app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json").text
    ).optString(source)
    if(link.isNullOrEmpty()) {
        return getBaseUrl(url)
    }
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
        try {
            val latestUrl = getLatestUrl(url, "hubcloud")
            val baseUrl = getBaseUrl(url)
            var newUrl = url.replace(baseUrl, latestUrl)
            
            // Handle different URL patterns
            if (!newUrl.startsWith("http")) {
                newUrl = latestUrl + newUrl
            }
            
            val doc = app.get(newUrl).document
            val title = doc.selectFirst("title")?.text() ?: ""
            val quality = getIndexQuality(title)
            
            // New pattern: Look for download buttons directly
            val downloadButtons = doc.select("a.btn")
            
            downloadButtons.amap { button ->
                val buttonText = button.text()
                val buttonLink = button.attr("href")
                
                when {
                    buttonText.contains("Generate Direct Download", ignoreCase = true) -> {
                        // gamerxyt.com intermediate page
                        try {
                            val gamerDoc = app.get(buttonLink).document
                            val directLinks = gamerDoc.select("a.btn")
                            directLinks.amap { directBtn ->
                                val directLink = directBtn.attr("href")
                                val directText = directBtn.text()
                                
                                when {
                                    // FSL Server - firecdn.buzz
                                    directText.contains("FSL Server", ignoreCase = true) -> {
                                        callback.invoke(
                                            newExtractorLink(
                                                "$name[FSL Server]",
                                                "$name[FSL Server] $title",
                                                directLink
                                            ) {
                                                this.quality = quality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                                    // 10Gbps Server - hubcdn.fans
                                    directText.contains("10Gbps", ignoreCase = true) || 
                                    directText.contains("Server :", ignoreCase = true) -> {
                                        callback.invoke(
                                            newExtractorLink(
                                                "$name[10Gbps]",
                                                "$name[10Gbps] $title",
                                                directLink
                                            ) {
                                                this.quality = quality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                                    // PixelServer - pixeldrain
                                    directText.contains("PixelServer", ignoreCase = true) || 
                                    directLink.contains("pixeldrain") -> {
                                        val pixelLink = if (directLink.contains("pixeldrain.dev")) {
                                            directLink.replace("/u/", "/api/file/") + "?download"
                                        } else {
                                            directLink
                                        }
                                        callback.invoke(
                                            newExtractorLink(
                                                "Pixeldrain",
                                                "Pixeldrain $title",
                                                pixelLink
                                            ) {
                                                this.quality = quality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                                    // Telegram - skip
                                    directText.contains("Telegram", ignoreCase = true) -> {
                                        // Skip telegram links
                                    }
                                    // Generic download buttons
                                    directLink.isNotEmpty() && !directLink.contains("javascript") && 
                                    !directLink.contains("telegram") &&
                                    (directLink.contains(".mkv") || directLink.contains(".mp4") || 
                                     directText.contains("Download", ignoreCase = true)) -> {
                                        callback.invoke(
                                            newExtractorLink(
                                                "$name[Direct]",
                                                "$name[Direct] $title",
                                                directLink
                                            ) {
                                                this.quality = quality
                                                this.headers = VIDEO_HEADERS
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("HubCloud", "Generate Direct failed: $e")
                        }
                    }
                    buttonText.contains("Download From Telegram", ignoreCase = true) -> {
                        // Skip telegram links
                    }
                    buttonText.contains("[FSL Server]", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[FSL Server]",
                                "$name[FSL Server] $title",
                                buttonLink,
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    buttonText.contains("[FSLv2 Server]", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[FSLv2 Server]",
                                "$name[FSLv2 Server] $title",
                                buttonLink,
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    buttonText.contains("[Mega Server]", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name[Mega Server]",
                                "$name[Mega Server] $title",
                                buttonLink,
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    buttonText.contains("Download File", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $title",
                                buttonLink,
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    buttonLink.contains("pixeldra") -> {
                        val baseUrlLink = getBaseUrl(buttonLink)
                        val finalURL = if (buttonLink.contains("download", true)) buttonLink
                        else "$baseUrlLink/api/file/${buttonLink.substringAfterLast("/")}?download"
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "Pixeldrain $title",
                                finalURL,
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    else -> {
                        if (!buttonLink.contains(".zip") && (buttonLink.contains(".mkv") || buttonLink.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name $title",
                                    buttonLink,
                                ) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    }
                }
            }
            
            // Legacy pattern: script tag with url variable (for older pages)
            if (newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                val link = Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
                if (link.isNotEmpty()) {
                    val fullLink = if (link.startsWith("https://")) link else latestUrl + link
                    val document = app.get(fullLink).document
                    val div = document.selectFirst("div.card-body")
                    val header = document.select("div.card-header").text()
                    val size = document.select("i#size").text()
                    val linkQuality = getIndexQuality(header)

                    div?.select("h2 a.btn")?.amap {
                        val btnLink = it.attr("href")
                        val text = it.text()
                        processHubCloudButton(text, btnLink, header, size, linkQuality, callback)
                    }
                }
            } else {
                val link = doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
                if (link.isNotEmpty()) {
                    val fullLink = if (link.startsWith("https://")) link else latestUrl + link
                    val document = app.get(fullLink).document
                    val div = document.selectFirst("div.card-body")
                    val header = document.select("div.card-header").text()
                    val size = document.select("i#size").text()
                    val linkQuality = getIndexQuality(header)

                    div?.select("h2 a.btn")?.amap {
                        val btnLink = it.attr("href")
                        val text = it.text()
                        processHubCloudButton(text, btnLink, header, size, linkQuality, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("HubCloud", "Error: $e")
        }
    }
    
    private suspend fun processHubCloudButton(
        text: String, 
        link: String, 
        header: String, 
        size: String, 
        quality: Int, 
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            text.contains("[FSL Server]") -> {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSL Server]",
                        "$name[FSL Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            text.contains("[FSLv2 Server]") -> {
                callback.invoke(
                    newExtractorLink(
                        "$name[FSLv2 Server]",
                        "$name[FSLv2 Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            text.contains("[Mega Server]") -> {
                callback.invoke(
                    newExtractorLink(
                        "$name[Mega Server]",
                        "$name[Mega Server] $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            text.contains("Download File") -> {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name $header[$size]",
                        link,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            link.contains("pixeldra") -> {
                val baseUrlLink = getBaseUrl(link)
                val finalURL = if (link.contains("download", true)) link
                else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                callback.invoke(
                    newExtractorLink(
                        "Pixeldrain",
                        "Pixeldrain $header[$size]",
                        finalURL,
                    ) {
                        this.quality = quality
                        this.headers = VIDEO_HEADERS
                    }
                )
            }
            else -> {
                if (!link.contains(".zip") && (link.contains(".mkv") || link.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header[$size]",
                            link,
                        ) {
                            this.quality = quality
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
    override var mainUrl = "https://new9.gdflix.*"
}

// New: GDFlix Dev domain support
class GDFlixDev : GDFlix() {
    override var mainUrl = "https://gdflix.dev"
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
        try {
            val latestUrl = getLatestUrl(url, "gdflix")
            val baseUrl = getBaseUrl(url)
            var newUrl = url.replace(baseUrl, latestUrl)
            
            // Handle gdflix.dev URLs
            if (url.contains("gdflix.dev")) {
                newUrl = url
            }
            
            val document = app.get(newUrl).document
            
            // Get file info from page title or meta
            val pageTitle = document.selectFirst("title")?.text() ?: ""
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ").ifEmpty { 
                    pageTitle.substringAfter("| ").substringBefore(" -") 
                }
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val quality = getIndexQuality(fileName)

            // New pattern: Look for all download buttons
            document.select("a.btn").amap { anchor ->
                val text = anchor.text()
                val link = anchor.attr("href")
                val fullLink = if (link.startsWith("/")) getBaseUrl(newUrl) + link else link
                
                when {
                    // New: Instant DL with busycdn
                    text.contains("Instant DL", ignoreCase = true) && link.contains("busycdn") -> {
                        callback.invoke(
                            newExtractorLink("GDFlix[Instant]", "GDFlix[Instant] $fileName[$fileSize]", link) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    
                    // New: CLOUD DOWNLOAD [R2] - Direct R2 URLs
                    text.contains("CLOUD DOWNLOAD", ignoreCase = true) && link.contains("r2.dev") -> {
                        callback.invoke(
                            newExtractorLink("GDFlix[R2 Cloud]", "GDFlix[R2 Cloud] $fileName[$fileSize]", link) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    
                    // New: PixelDrain DL with pixeldrain.dev
                    text.contains("PixelDrain", ignoreCase = true) || link.contains("pixeldrain") -> {
                        val pixelLink = if (link.contains("pixeldrain.dev")) {
                            link.replace("/u/", "/api/file/") + "?download"
                        } else {
                            val baseUrlLink = getBaseUrl(link)
                            if (link.contains("download", true)) link
                            else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                        }
                        callback.invoke(
                            newExtractorLink(
                                "Pixeldrain",
                                "GDFlix[Pixeldrain] $fileName[$fileSize]",
                                pixelLink
                            ) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }
                    
                    // New: FAST CLOUD / ZIPDISK - internal path
                    text.contains("FAST CLOUD", ignoreCase = true) || text.contains("ZIPDISK", ignoreCase = true) -> {
                        try {
                            val zfileDoc = app.get(fullLink).document
                            val directLink = zfileDoc.selectFirst("a.btn:contains(Download)")?.attr("href")
                            if (!directLink.isNullOrEmpty()) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[FastCloud]", "GDFlix[FastCloud] $fileName[$fileSize]", directLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.d("GDFlix", "FastCloud error: $e")
                        }
                    }
                    
                    // New: GoFile with goflix.sbs
                    text.contains("GoFile", ignoreCase = true) -> {
                        try {
                            if (link.contains("goflix.sbs")) {
                                val goflixDoc = app.get(link).document
                                goflixDoc.select("a").amap { goAnchor ->
                                    val goLink = goAnchor.attr("href")
                                    if (goLink.contains("gofile")) {
                                        Gofile().getUrl(goLink, "", subtitleCallback, callback)
                                    }
                                }
                            } else {
                                app.get(link).document
                                    .select(".row .row a").amap { gofileAnchor ->
                                        val goLink = gofileAnchor.attr("href")
                                        if (goLink.contains("gofile")) {
                                            Gofile().getUrl(goLink, "", subtitleCallback, callback)
                                        }
                                    }
                            }
                        } catch (e: Exception) {
                            Log.d("Gofile", e.toString())
                        }
                    }
                    
                    // Legacy: DIRECT DL button
                    text.contains("DIRECT DL", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }

                    // Legacy: CLOUD DOWNLOAD [R2] with URL param
                    text.contains("CLOUD DOWNLOAD [R2]") && link.contains("url=") -> {
                        val decodedLink = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                        callback.invoke(
                            newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", decodedLink) {
                                this.quality = quality
                                this.headers = VIDEO_HEADERS
                            }
                        )
                    }

                    // Legacy: Index Links
                    text.contains("Index Links") -> {
                        try {
                            app.get("$latestUrl$link").document
                                .select("a.btn.btn-outline-info").amap { btn ->
                                    val serverUrl = latestUrl + btn.attr("href")
                                    app.get(serverUrl).document
                                        .select("div.mb-4 > a").amap { sourceAnchor ->
                                            val source = sourceAnchor.attr("href")
                                            callback.invoke(
                                                newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                    this.quality = quality
                                                    this.headers = VIDEO_HEADERS
                                                }
                                            )
                                        }
                                }
                        } catch (e: Exception) {
                            Log.d("Index Links", e.toString())
                        }
                    }

                    // Legacy: DRIVEBOT
                    text.contains("DRIVEBOT") -> {
                        try {
                            val driveLink = link
                            val id = driveLink.substringAfter("id=").substringBefore("&")
                            val doId = driveLink.substringAfter("do=").substringBefore("==")
                            val baseUrls = listOf("https://drivebot.sbs", "https://indexbot.site")

                            baseUrls.amap { baseUrl ->
                                val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                                val indexbotResponse = app.get(indexbotLink, timeout = 100L)

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

                                    val headers = mapOf("Referer" to indexbotLink)
                                    val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                    val downloadLink = app.post(
                                        "$baseUrl/download?id=$postId",
                                        requestBody = requestBody,
                                        headers = headers,
                                        cookies = cookies,
                                        timeout = 100L
                                    ).text.let {
                                        Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                    }

                                    callback.invoke(
                                        newExtractorLink("GDFlix[DriveBot]", "GDFlix[DriveBot] $fileName[$fileSize]", downloadLink) {
                                            this.referer = baseUrl
                                            this.quality = quality
                                            this.headers = VIDEO_HEADERS
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("DriveBot", e.toString())
                        }
                    }

                    // Legacy: Instant DL redirect
                    text.contains("Instant DL") && !link.contains("busycdn") -> {
                        try {
                            val instantLink = link
                            val redirectLink = app.get(instantLink, allowRedirects = false)
                                .headers["location"]?.substringAfter("url=").orEmpty()

                            if (redirectLink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink("GDFlix[Instant Download]", "GDFlix[Instant Download] $fileName[$fileSize]", redirectLink) {
                                        this.quality = quality
                                        this.headers = VIDEO_HEADERS
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.d("Instant DL", e.toString())
                        }
                    }

                    else -> {
                        // Direct video file links
                        if (!link.contains(".zip") && (link.endsWith(".mkv") || link.endsWith(".mp4"))) {
                            callback.invoke(
                                newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    }
                }
            }
            
            // Also check div.text-center for legacy pages
            document.select("div.text-center a").amap { anchor ->
                val text = anchor.select("a").text()
                val link = anchor.attr("href")
                
                // Only process if not already handled by a.btn above
                if (text.isNotEmpty() && link.isNotEmpty() && !link.startsWith("#")) {
                    when {
                        text.contains("DIRECT DL") && !link.contains("busycdn") && !link.contains("r2.dev") -> {
                            callback.invoke(
                                newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                                    this.quality = quality
                                    this.headers = VIDEO_HEADERS
                                }
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("GDFlix", "Error: $e")
        }
    }
}