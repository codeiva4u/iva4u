package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.FormBody
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mId = Regex("/(?:u|file)/([\\w-]+)").find(url)?.groupValues?.getOrNull(1)
        val finalUrl = if (mId.isNullOrEmpty()) url else "$mainUrl/api/file/$mId?download"

        callback.invoke(
            newExtractorLink(this.name, this.name, finalUrl) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class HubCloudInk : HubCloud() {
    override val mainUrl: String = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl: String = "https://hubcloud.art"
}

class HubCloudDad : HubCloud() {
    override val mainUrl: String = "https://hubcloud.dad"
}

class HubCloudBz : HubCloud() {
    override val mainUrl: String = "https://hubcloud.bz"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.fit"
    override val requiresReferer = false

    fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Starting extraction for URL: $url")
            val newBaseUrl = "https://hubcloud.fit"
            val newUrl = url.replace(mainUrl, newBaseUrl)
            val doc = app.get(newUrl).document
            var link = if(newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag) ?. groupValues ?. get(1) ?: ""
            }
            else {
                doc.selectFirst("div.vd > center > a") ?. attr("href") ?: ""
            }

            if(!link.startsWith("https://")) {
                link = newBaseUrl + link
            }

            Log.d("HubCloud", "Fetching download page: $link")
            val document = app.get(link).document
            val div = document.selectFirst("div.card-body")
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            // Extract all download links from the page
            div?.select("a.btn")?.amap {
                val link = it.attr("href")
                val text = it.text()
                
                Log.d("HubCloud", "Processing button: $text with link: $link")
                
                // Skip ZipDisk buttons
                if (text.contains("ZipDisk", ignoreCase = true) || text.contains("Zip Disk", ignoreCase = true)) {
                    Log.d("HubCloud", "Skipping ZipDisk button: $text")
                    return@amap
                }

                when {
                    // PixelServer:2 detection
                    text.contains("PixelServer", ignoreCase = true) || text.contains("PixelDrain", ignoreCase = true) -> {
                        try {
                            val pixelId = Regex("/u/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                                ?: Regex("/file/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                            val downloadUrl = if (pixelId != null) {
                                if (link.contains("pixeldrain.dev")) {
                                    "https://pixeldrain.dev/api/file/$pixelId?download"
                                } else {
                                    "https://pixeldrain.com/api/file/$pixelId?download"
                                }
                            } else link
                            
                            Log.d("HubCloud", "PixelServer extracted: $downloadUrl")
                            callback.invoke(
                                newExtractorLink(
                                    "$name[PixelServer:2]",
                                    "$name[PixelServer:2] $header[$size]",
                                    downloadUrl,
                                ) {
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("HubCloud", "PixelServer error: ${e.message}")
                        }
                    }
                    
                    // Server:10Gbps detection - Fixed with proper redirect chain following
                    text.contains("10Gbps", ignoreCase = true) || text.contains("10GBPS", ignoreCase = true) -> {
                        try {
                            Log.d("HubCloud", "Processing 10Gbps server link: $link")
                            var currentUrl = link
                            var downloadUrl = ""
                            var redirectCount = 0
                            val maxRedirects = 10
                            
                            // Follow redirect chain until we get final download URL
                            while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
                                val response = app.get(currentUrl, allowRedirects = false)
                                val location = response.headers["location"] ?: response.headers["Location"] ?: ""
                                
                                if (location.isNotEmpty()) {
                                    // Check if this is the final download URL (contains file extension or is direct link)
                                    if (location.contains(".mkv", ignoreCase = true) || 
                                        location.contains(".mp4", ignoreCase = true) ||
                                        location.contains(".avi", ignoreCase = true) ||
                                        !location.contains("hubcloud", ignoreCase = true)) {
                                        downloadUrl = if (location.contains("link=")) {
                                            URLDecoder.decode(location.substringAfter("link=").substringBefore("&"), "UTF-8")
                                        } else {
                                            location
                                        }
                                        break
                                    } else {
                                        // Continue following redirects
                                        currentUrl = if (location.startsWith("http")) location else "$newBaseUrl$location"
                                        redirectCount++
                                    }
                                } else {
                                    // No more redirects, use current URL
                                    downloadUrl = currentUrl
                                    break
                                }
                            }
                            
                            if (downloadUrl.isNotEmpty()) {
                                Log.d("HubCloud", "10Gbps final download URL: $downloadUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[Server:10Gbps]",
                                        "$name[Server:10Gbps] $header[$size]",
                                        downloadUrl,
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            } else {
                                Log.e("HubCloud", "Failed to extract 10Gbps download URL after $redirectCount redirects")
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "10Gbps server error: ${e.message}")
                        }
                    }
                    
                    // FSL Server detection with redirect handling
                    text.contains("FSL", ignoreCase = true) && !text.contains("FSLv2", ignoreCase = true) -> {
                        try {
                            Log.d("HubCloud", "FSL Server link: $link")
                            var currentUrl = link
                            var downloadUrl = ""
                            var redirectCount = 0
                            val maxRedirects = 5
                            
                            // Follow redirects to get final download URL
                            while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
                                val response = app.get(currentUrl, allowRedirects = false)
                                val location = response.headers["location"] ?: response.headers["Location"] ?: ""
                                
                                if (location.isNotEmpty()) {
                                    // Check if this is final download URL
                                    if (location.contains(".mkv", ignoreCase = true) || 
                                        location.contains(".mp4", ignoreCase = true) ||
                                        location.contains(".avi", ignoreCase = true) ||
                                        (!location.contains("hubcloud", ignoreCase = true) && 
                                         !location.contains("fsl", ignoreCase = true))) {
                                        downloadUrl = if (location.contains("link=") || location.contains("url=")) {
                                            try {
                                                val param = if (location.contains("link=")) "link=" else "url="
                                                URLDecoder.decode(location.substringAfter(param).substringBefore("&"), "UTF-8")
                                            } catch (e: Exception) {
                                                location
                                            }
                                        } else {
                                            location
                                        }
                                        break
                                    } else {
                                        currentUrl = if (location.startsWith("http")) location else "$newBaseUrl$location"
                                        redirectCount++
                                    }
                                } else {
                                    downloadUrl = currentUrl
                                    break
                                }
                            }
                            
                            if (downloadUrl.isNotEmpty()) {
                                Log.d("HubCloud", "FSL Server final URL: $downloadUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[FSL:Server]",
                                        "$name[FSL:Server] $header[$size]",
                                        downloadUrl,
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            } else {
                                Log.e("HubCloud", "Failed to extract FSL Server URL after $redirectCount redirects")
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "FSL Server error: ${e.message}")
                        }
                    }
                    
                    // FSLv2 Server detection  
                    text.contains("FSLv2", ignoreCase = true) -> {
                        try {
                            Log.d("HubCloud", "FSLv2 Server link: $link")
                            callback.invoke(
                                newExtractorLink(
                                    "$name[FSLv2 Server]",
                                    "$name[FSLv2 Server] $header[$size]",
                                    link,
                                ) {
                                    this.quality = quality
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("HubCloud", "FSLv2 Server error: ${e.message}")
                        }
                    }
                    
                    // Mega Server detection with redirect handling
                    text.contains("Mega", ignoreCase = true) -> {
                        try {
                            Log.d("HubCloud", "Mega Server link: $link")
                            var currentUrl = link
                            var downloadUrl = ""
                            var redirectCount = 0
                            val maxRedirects = 5
                            
                            // Follow redirects for Mega server
                            while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
                                val response = app.get(currentUrl, allowRedirects = false)
                                val location = response.headers["location"] ?: response.headers["Location"] ?: ""
                                
                                if (location.isNotEmpty()) {
                                    // Check if final download URL
                                    if (location.contains(".mkv", ignoreCase = true) || 
                                        location.contains(".mp4", ignoreCase = true) ||
                                        location.contains(".avi", ignoreCase = true) ||
                                        (!location.contains("hubcloud", ignoreCase = true) && 
                                         !location.contains("mega", ignoreCase = true))) {
                                        downloadUrl = if (location.contains("link=") || location.contains("url=")) {
                                            try {
                                                val param = if (location.contains("link=")) "link=" else "url="
                                                URLDecoder.decode(location.substringAfter(param).substringBefore("&"), "UTF-8")
                                            } catch (e: Exception) {
                                                location
                                            }
                                        } else {
                                            location
                                        }
                                        break
                                    } else {
                                        currentUrl = if (location.startsWith("http")) location else "$newBaseUrl$location"
                                        redirectCount++
                                    }
                                } else {
                                    downloadUrl = currentUrl
                                    break
                                }
                            }
                            
                            if (downloadUrl.isNotEmpty()) {
                                Log.d("HubCloud", "Mega Server final URL: $downloadUrl")
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[Mega:Server]",
                                        "$name[Mega:Server] $header[$size]",
                                        downloadUrl,
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            } else {
                                Log.e("HubCloud", "Failed to extract Mega Server URL after $redirectCount redirects")
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "Mega Server error: ${e.message}")
                        }
                    }
                    
                    // BuzzServer detection
                    text.contains("BuzzServer", ignoreCase = true) -> {
                        try {
                            val dlink = app.get("$link/download", referer = link, allowRedirects = false).headers["hx-redirect"] ?: ""
                            val baseUrl = getBaseUrl(link)
                            if(dlink.isNotEmpty()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name[BuzzServer]",
                                        "$name[BuzzServer] $header[$size]",
                                        baseUrl + dlink,
                                    ) {
                                        this.quality = quality
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("HubCloud", "BuzzServer error: ${e.message}")
                        }
                    }
                    
                    // Direct download links
                    link.contains(".mkv", ignoreCase = true) || link.contains(".mp4", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                link,
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Fatal extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

class fastdlserver2 : fastdlserver() {
    override var mainUrl = "https://fastdlserver.life"
}

open class fastdlserver : ExtractorApi() {
    override val name: String = "fastdlserver"
    override var mainUrl = "https://fastdlserver.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) {
            loadExtractor(location, "", subtitleCallback, callback)
        }
    }
}

class GDLink : GDFlix() {
    override var mainUrl = "https://gdlink.dev"
}

class GDFlix3 : GDFlix() {
    override var mainUrl = "https://new4.gdflix.dad"
}

class GDFlix2 : GDFlix() {
    override var mainUrl = "https://new.gdflix.dad"
}

class GDFlix7 : GDFlix() {
    override var mainUrl = "https://gdflix.dad"
}

class GDFlixNet : GDFlix() {
    override var mainUrl = "https://new.gdflix.net"
}

class GDFlixDev : GDFlix() {
    override var mainUrl = "https://gdflix.dev"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new5.gdflix.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDFlix", "Starting extraction for URL: $url")
            val newUrl = url
            val document = app.get(newUrl).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val quality = getIndexQuality(fileName)

            Log.d("GDFlix", "File: $fileName, Size: $fileSize")

            document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                text.contains("DIRECT DL") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = quality
                        }
                    )
                }

                text.contains("CLOUD DOWNLOAD [R2]") -> {
                    val link = URLDecoder.decode(link.substringAfter("url="), StandardCharsets.UTF_8.toString())
                    callback.invoke(
                        newExtractorLink("GDFlix[Cloud]", "GDFlix[Cloud] $fileName[$fileSize]", link) {
                            this.quality = quality
                        }
                    )
                }

                // PixelDrain DL 20MB/s detection
                text.contains("PixelDrain", ignoreCase = true) -> {
                    try {
                        Log.d("GDFlix", "Processing PixelDrain link: $link")
                        val pixelId = Regex("/u/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                            ?: Regex("/file/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                        val downloadUrl = if (pixelId != null) {
                            if (link.contains("pixeldrain.dev")) {
                                "https://pixeldrain.dev/api/file/$pixelId?download"
                            } else {
                                "https://pixeldrain.com/api/file/$pixelId?download"
                            }
                        } else link
                        
                        Log.d("GDFlix", "PixelDrain DL 20MB/s extracted: $downloadUrl")
                        callback.invoke(
                            newExtractorLink(
                                "GDFlix[PixelDrain DL 20MB/s]",
                                "GDFlix[PixelDrain DL 20MB/s] $fileName[$fileSize]",
                                downloadUrl
                            ) {
                                this.quality = quality
                            }
                        )
                    } catch (e: Exception) {
                        Log.e("GDFlix", "PixelDrain error: ${e.message}")
                    }
                }

                text.contains("Index Links") -> {
                    try {
                        app.get("$mainUrl$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = mainUrl + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val source = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                this.quality = quality
                                            }
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

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
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                // Instant DL 10GBPS detection - Fixed with proper redirect handling
                text.contains("Instant DL", ignoreCase = true) || text.contains("10GBPS", ignoreCase = true) -> {
                    try {
                        Log.d("GDFlix", "Processing Instant DL 10GBPS link: $link")
                        var currentUrl = link
                        var downloadUrl = ""
                        var redirectCount = 0
                        val maxRedirects = 10
                        
                        // Follow redirect chain to get final download URL
                        while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
                            val response = app.get(currentUrl, allowRedirects = false)
                            val location = response.headers["location"] ?: response.headers["Location"] ?: ""
                            
                            if (location.isNotEmpty()) {
                                // Decode URL parameter if present
                                val decodedLocation = if (location.contains("url=") || location.contains("link=")) {
                                    try {
                                        val param = if (location.contains("url=")) "url=" else "link="
                                        URLDecoder.decode(location.substringAfter(param).substringBefore("&"), "UTF-8")
                                    } catch (e: Exception) {
                                        location
                                    }
                                } else {
                                    location
                                }
                                
                                // Check if this is final download URL
                                if (decodedLocation.contains(".mkv", ignoreCase = true) || 
                                    decodedLocation.contains(".mp4", ignoreCase = true) ||
                                    decodedLocation.contains(".avi", ignoreCase = true) ||
                                    (!decodedLocation.contains("gdflix", ignoreCase = true) && 
                                     !decodedLocation.contains("gdlink", ignoreCase = true))) {
                                    downloadUrl = decodedLocation
                                    break
                                } else {
                                    // Continue following redirects
                                    currentUrl = if (decodedLocation.startsWith("http")) {
                                        decodedLocation
                                    } else {
                                        "$mainUrl$decodedLocation"
                                    }
                                    redirectCount++
                                }
                            } else {
                                // No redirect, this might be the final URL
                                downloadUrl = currentUrl
                                break
                            }
                        }
                        
                        if (downloadUrl.isNotEmpty()) {
                            Log.d("GDFlix", "Instant DL 10GBPS final URL (after $redirectCount redirects): $downloadUrl")
                            callback.invoke(
                                newExtractorLink(
                                    "GDFlix[Instant DL 10GBPS]", 
                                    "GDFlix[Instant DL 10GBPS] $fileName[$fileSize]", 
                                    downloadUrl
                                ) {
                                    this.quality = quality
                                }
                            )
                        } else {
                            Log.e("GDFlix", "Failed to extract Instant DL URL after $redirectCount redirects")
                        }
                    } catch (e: Exception) {
                        Log.e("GDFlix", "Instant DL error: ${e.message}")
                        e.printStackTrace()
                    }
                }
                text.contains("GoFile") -> {
                    try {
                        app.get(link).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Gofile().getUrl(link, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }

                else -> {
                    Log.d("GDFlix", "No Server matched for: $text")
                }
            }
        }
        } catch (e: Exception) {
            Log.e("GDFlix", "Fatal extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "Origin" to mainUrl,
            "Referer" to mainUrl,
        )
        val id = url.substringAfter("d/").substringBefore("/")
        val genAccountRes = app.post("$mainApi/accounts", headers = headers).text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token") ?: return

        val globalRes = app.get("$mainUrl/dist/js/global.js", headers = headers).text
        val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?wt=$wt",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
                "Origin" to mainUrl,
                "Referer" to mainUrl,
                "Authorization" to "Bearer $token",
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

        if(link != null) {
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
        }
    }

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}