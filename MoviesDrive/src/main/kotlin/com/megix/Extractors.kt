package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

// ===== HubCloud Base Class =====
open class HubCloudBase : ExtractorApi() {
    override val name: String = "HubCloud"
    override val mainUrl: String = "https://hubcloud.fit"
    override val requiresReferer = false

    fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }
    
    protected suspend fun followRedirectChain(
        startUrl: String,
        maxRedirects: Int = 10,
        serverName: String
    ): String {
        var currentUrl = startUrl
        var downloadUrl = ""
        var redirectCount = 0
        val newBaseUrl = "https://hubcloud.fit"
        
        while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
            try {
                val response = app.get(currentUrl, allowRedirects = false)
                val location = response.headers["location"] ?: response.headers["Location"] ?: ""
                
                if (location.isNotEmpty()) {
                    // Check if this is final download URL
                    if (location.contains(".mkv", ignoreCase = true) || 
                        location.contains(".mp4", ignoreCase = true) ||
                        location.contains(".avi", ignoreCase = true) ||
                        !location.contains("hubcloud", ignoreCase = true)) {
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
                        // Continue following redirects
                        currentUrl = if (location.startsWith("http")) location else "$newBaseUrl$location"
                        redirectCount++
                        Log.d("$name[$serverName]", "Redirect $redirectCount: $currentUrl")
                    }
                } else {
                    // No more redirects
                    downloadUrl = currentUrl
                    break
                }
            } catch (e: Exception) {
                Log.e("$name[$serverName]", "Redirect error: ${e.message}")
                break
            }
        }
        
        if (downloadUrl.isNotEmpty()) {
            Log.d("$name[$serverName]", "Final URL after $redirectCount redirects: $downloadUrl")
        } else {
            Log.e("$name[$serverName]", "Failed to extract URL after $redirectCount redirects")
        }
        
        return downloadUrl
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Override in child classes
    }
}

// ===== HubCloud Server 1: PixelServer:2 =====
class HubCloudPixelServer : HubCloudBase() {
    override val name = "HubCloud-PixelServer:2"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting PixelServer:2 from: $url")
            val newBaseUrl = "https://hubcloud.fit"
            val newUrl = url.replace(Regex("hubcloud\\.(ink|art|dad|bz)"), "hubcloud.fit")
            val doc = app.get(newUrl).document
            
            var link = if(newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if(!link.startsWith("https://")) {
                link = newBaseUrl + link
            }

            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            document.select("div.card-body a.btn").forEach {
                val btnLink = it.attr("href")
                val text = it.text()
                
                // Skip ZipDisk
                if (text.contains("ZipDisk", ignoreCase = true) || text.contains("Zip Disk", ignoreCase = true)) {
                    Log.d(name, "Skipping ZipDisk button")
                    return@forEach
                }
                
                if (text.contains("PixelServer", ignoreCase = true) || text.contains("PixelDrain", ignoreCase = true)) {
                    val pixelId = Regex("/u/([a-zA-Z0-9]+)").find(btnLink)?.groupValues?.get(1)
                        ?: Regex("/file/([a-zA-Z0-9]+)").find(btnLink)?.groupValues?.get(1)
                    val downloadUrl = if (pixelId != null) {
                        if (btnLink.contains("pixeldrain.dev")) {
                            "https://pixeldrain.dev/api/file/$pixelId?download"
                        } else {
                            "https://pixeldrain.com/api/file/$pixelId?download"
                        }
                    } else btnLink
                    
                    Log.d(name, "PixelServer URL: $downloadUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $header[$size]",
                            downloadUrl,
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== HubCloud Server 2: Server:10Gbps =====
class HubCloudServer10Gbps : HubCloudBase() {
    override val name = "HubCloud-Server:10Gbps"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting Server:10Gbps from: $url")
            val newBaseUrl = "https://hubcloud.fit"
            val newUrl = url.replace(Regex("hubcloud\\.(ink|art|dad|bz)"), "hubcloud.fit")
            val doc = app.get(newUrl).document
            
            var link = if(newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if(!link.startsWith("https://")) {
                link = newBaseUrl + link
            }

            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            document.select("div.card-body a.btn").forEach {
                val btnLink = it.attr("href")
                val text = it.text()
                
                // Skip ZipDisk
                if (text.contains("ZipDisk", ignoreCase = true) || text.contains("Zip Disk", ignoreCase = true)) {
                    Log.d(name, "Skipping ZipDisk button")
                    return@forEach
                }
                
                if (text.contains("10Gbps", ignoreCase = true) || text.contains("10GBPS", ignoreCase = true)) {
                    val downloadUrl = followRedirectChain(btnLink, 10, "10Gbps")
                    
                    if (downloadUrl.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                downloadUrl,
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== HubCloud Server 3: FSL:Server =====
class HubCloudFSLServer : HubCloudBase() {
    override val name = "HubCloud-FSL:Server"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting FSL:Server from: $url")
            val newBaseUrl = "https://hubcloud.fit"
            val newUrl = url.replace(Regex("hubcloud\\.(ink|art|dad|bz)"), "hubcloud.fit")
            val doc = app.get(newUrl).document
            
            var link = if(newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if(!link.startsWith("https://")) {
                link = newBaseUrl + link
            }

            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            document.select("div.card-body a.btn").forEach {
                val btnLink = it.attr("href")
                val text = it.text()
                
                // Skip ZipDisk
                if (text.contains("ZipDisk", ignoreCase = true) || text.contains("Zip Disk", ignoreCase = true)) {
                    Log.d(name, "Skipping ZipDisk button")
                    return@forEach
                }
                
                if (text.contains("FSL", ignoreCase = true) && !text.contains("FSLv2", ignoreCase = true)) {
                    val downloadUrl = followRedirectChain(btnLink, 5, "FSL")
                    
                    if (downloadUrl.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                downloadUrl,
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== HubCloud Server 4: Mega:Server =====
class HubCloudMegaServer : HubCloudBase() {
    override val name = "HubCloud-Mega:Server"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting Mega:Server from: $url")
            val newBaseUrl = "https://hubcloud.fit"
            val newUrl = url.replace(Regex("hubcloud\\.(ink|art|dad|bz)"), "hubcloud.fit")
            val doc = app.get(newUrl).document
            
            var link = if(newUrl.contains("drive")) {
                val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            } else {
                doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
            }

            if(!link.startsWith("https://")) {
                link = newBaseUrl + link
            }

            val document = app.get(link).document
            val header = document.select("div.card-header").text()
            val size = document.select("i#size").text()
            val quality = getIndexQuality(header)

            document.select("div.card-body a.btn").forEach {
                val btnLink = it.attr("href")
                val text = it.text()
                
                // Skip ZipDisk
                if (text.contains("ZipDisk", ignoreCase = true) || text.contains("Zip Disk", ignoreCase = true)) {
                    Log.d(name, "Skipping ZipDisk button")
                    return@forEach
                }
                
                if (text.contains("Mega", ignoreCase = true)) {
                    val downloadUrl = followRedirectChain(btnLink, 5, "Mega")
                    
                    if (downloadUrl.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $header[$size]",
                                downloadUrl,
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== GDFlix Base Class =====
open class GDFlixBase : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new5.gdflix.net"
    override val requiresReferer = false
    
    protected suspend fun followGDFlixRedirects(
        startUrl: String,
        maxRedirects: Int = 10,
        serverName: String
    ): String {
        var currentUrl = startUrl
        var downloadUrl = ""
        var redirectCount = 0
        
        while (redirectCount < maxRedirects && downloadUrl.isEmpty()) {
            try {
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
                    
                    // Check if final download URL
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
                        Log.d("$name[$serverName]", "Redirect $redirectCount: $currentUrl")
                    }
                } else {
                    // No redirect, this might be final URL
                    downloadUrl = currentUrl
                    break
                }
            } catch (e: Exception) {
                Log.e("$name[$serverName]", "Redirect error: ${e.message}")
                break
            }
        }
        
        if (downloadUrl.isNotEmpty()) {
            Log.d("$name[$serverName]", "Final URL after $redirectCount redirects: $downloadUrl")
        } else {
            Log.e("$name[$serverName]", "Failed to extract URL after $redirectCount redirects")
        }
        
        return downloadUrl
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Override in child classes
    }
}

// ===== GDFlix Server 1: Instant DL 10GBPS =====
class GDFlixInstantDL10GBPS : GDFlixBase() {
    override val name = "GDFlix-Instant_DL_10GBPS"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting Instant DL 10GBPS from: $url")
            val document = app.get(url).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val quality = getIndexQuality(fileName)

            document.select("div.text-center a").forEach { anchor ->
                val text = anchor.text()
                val link = anchor.attr("href")
                
                if (text.contains("Instant DL", ignoreCase = true) || text.contains("10GBPS", ignoreCase = true)) {
                    val downloadUrl = followGDFlixRedirects(link, 10, "Instant_DL")
                    
                    if (downloadUrl.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name $fileName[$fileSize]",
                                downloadUrl
                            ) {
                                this.quality = quality
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== GDFlix Server 2: PixelDrain DL 20MB/s =====
class GDFlixPixelDrainDL20MBs : GDFlixBase() {
    override val name = "GDFlix-PixelDrain_DL_20MBs"
    
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Extracting PixelDrain DL 20MB/s from: $url")
            val document = app.get(url).document
            val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
                .substringAfter("Name : ")
            val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
                .substringAfter("Size : ")
            val quality = getIndexQuality(fileName)

            document.select("div.text-center a").forEach { anchor ->
                val text = anchor.text()
                val link = anchor.attr("href")
                
                if (text.contains("PixelDrain", ignoreCase = true)) {
                    val pixelId = Regex("/u/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                        ?: Regex("/file/([a-zA-Z0-9]+)").find(link)?.groupValues?.get(1)
                    val downloadUrl = if (pixelId != null) {
                        if (link.contains("pixeldrain.dev")) {
                            "https://pixeldrain.dev/api/file/$pixelId?download"
                        } else {
                            "https://pixeldrain.com/api/file/$pixelId?download"
                        }
                    } else link
                    
                    Log.d(name, "PixelDrain URL: $downloadUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name $fileName[$fileSize]",
                            downloadUrl
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ===== Legacy Support Classes (for backward compatibility) =====
class HubCloudInk : HubCloudBase() {
    override val mainUrl: String = "https://hubcloud.ink"
}

class HubCloudArt : HubCloudBase() {
    override val mainUrl: String = "https://hubcloud.art"
}

class HubCloudDad : HubCloudBase() {
    override val mainUrl: String = "https://hubcloud.dad"
}

class HubCloudBz : HubCloudBase() {
    override val mainUrl: String = "https://hubcloud.bz"
}

class GDLink : GDFlixBase() {
    override var mainUrl = "https://gdlink.dev"
}

class GDFlix3 : GDFlixBase() {
    override var mainUrl = "https://new4.gdflix.dad"
}

class GDFlix2 : GDFlixBase() {
    override var mainUrl = "https://new.gdflix.dad"
}

class GDFlix7 : GDFlixBase() {
    override var mainUrl = "https://gdflix.dad"
}

class GDFlixNet : GDFlixBase() {
    override var mainUrl = "https://new.gdflix.net"
}

class GDFlixDev : GDFlixBase() {
    override var mainUrl = "https://gdflix.dev"
}

// ===== PixelDrain Standalone =====
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

// ===== Gofile =====
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
                    this.quality = getIndexQuality(fileName)
                    this.headers = mapOf(
                        "Cookie" to "accountToken=$token"
                    )
                }
            )
        }
    }
}

// ===== FastDLServer =====
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
