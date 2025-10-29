package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
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

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.dev"
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
    override val mainUrl = "https://hubcloud.ink"
}

class HubCloudArt : HubCloud() {
    override val mainUrl = "https://hubcloud.art"
}

class HubCloudDad : HubCloud() {
    override val mainUrl = "https://hubcloud.dad"
}

class HubCloudBz : HubCloud() {
    override val mainUrl = "https://hubcloud.bz"
}

open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            mainUrl
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) {
            Log.d(name, "Invalid URL: $url")
            return
        }
        
        try {
            Log.d(name, "Processing: $url")
            
            // Check if it's the API redirect URL (gamerxyt.com/hubcloud.php)
            if (url.contains("gamerxyt.com/hubcloud.php")) {
                val doc = app.get(url, timeout = 10L).document
                val header = doc.selectFirst("div.card-header")?.text() ?: "Video"
                val size = doc.selectFirst("i#size")?.text() ?: ""
                
                // Extract all server links
                val links = doc.select("a[href]").mapNotNull { link ->
                    val href = link.attr("href")
                    val text = link.text()
                    
                    when {
                        // PixelDrain direct API
                        href.contains("pixeldrain.dev/api/file") -> {
                            callback.invoke(
                                newExtractorLink("HubCloud[PixelDrain]", "HubCloud[PixelDrain] $header[$size]", href) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                            null
                        }
                        // FSL Server direct link
                        href.contains("fsl.anime4u.co") || text.contains("FSL", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink("HubCloud[FSL]", "HubCloud[FSL] $header[$size]", href) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                            null
                        }
                        // 10Gbps Server (pixel.hubcdn.fans)
                        href.contains("pixel.hubcdn.fans") || text.contains("10Gbps", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink("HubCloud[10Gbps]", "HubCloud[10Gbps] $header[$size]", href) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                            null
                        }
                        // ZipDisk CloudFlare Workers
                        href.contains("cloudserver-") && href.contains(".workers.dev") -> {
                            callback.invoke(
                                newExtractorLink("HubCloud[ZipDisk]", "HubCloud[ZipDisk] $header[$size]", href) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                            null
                        }
                        // Mega Server
                        href.contains("mega.co.nz") || text.contains("Mega", ignoreCase = true) -> {
                            callback.invoke(
                                newExtractorLink("HubCloud[Mega]", "HubCloud[Mega] $header[$size]", href) {
                                    this.quality = getIndexQuality(header)
                                }
                            )
                            null
                        }
                        else -> null
                    }
                }
                return
            }
            
            // Original HubCloud page processing
            val doc = app.get(url, timeout = 10L).document
            val header = doc.selectFirst("div.card-header")?.text() ?: "Video"
            val size = doc.selectFirst("i#size")?.text() ?: ""
            
            Log.d(name, "File: $header [$size]")
            
            // Find download button that redirects to gamerxyt.com
            val downloadBtn = doc.selectFirst("a#download[href*='gamerxyt.com']")
            if (downloadBtn != null) {
                val redirectUrl = downloadBtn.attr("href")
                Log.d(name, "Found redirect URL: $redirectUrl")
                // Recursively process the redirect URL
                getUrl(redirectUrl, url, subtitleCallback, callback)
                return
            }
            
            // Fallback: Find all download links directly
            val links = doc.select("a[href]").mapNotNull { link ->
                val href = link.attr("href")
                val text = link.text()
                
                when {
                    href.contains("pixeldrain", ignoreCase = true) -> href to "PixelDrain"
                    text.matches(Regex("(?i).*(server|download|gbps|fsl|zipdisk|instant|cloud).*")) && 
                    href.matches(Regex("https?://[\\w.-]+/.*")) &&
                    !href.contains("t.me") -> href to text
                    else -> null
                }
            }
            
            Log.d(name, "Found ${links.size} links")
            
            // Process links in parallel with amap
            links.amap { (linkUrl, linkName) ->
                try {
                    Log.d(name, "Extracting: $linkName")
                    loadExtractor(linkUrl, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Failed to extract $linkName: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(name, "Error: ${e.message}")
        }
    }
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

class GDFlixDev : GDFlix() {
    override var mainUrl = "https://gdflix.dev"
}

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new7.gdflix.net"
    override val requiresReferer = false


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        document.select("div.text-center a, div.text-center button").amap { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")

            when {
                // Instant DL - Fixed: Now using instant.busycdn.cfd domain
                text.contains("Instant DL", ignoreCase = true) || link.contains("instant.busycdn.cfd") -> {
                    try {
                        // This is a direct link from GDFlix page
                        callback.invoke(
                            newExtractorLink("GDFlix[Instant DL]", "GDFlix[Instant DL] $fileName[$fileSize]", link) {
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }
                
                // Cloud Download R2 / Login DL - Requires login, skip
                text.contains("Login To DL", ignoreCase = true) || text.contains("10GBPS") -> {
                    // Skip login-required servers
                    Log.d("GDFlix", "Skipping login-required server: $text")
                }
                
                // ZipDisk / Fast Cloud - Fixed: Dynamic API-based link generation
                text.contains("ZipDisk", ignoreCase = true) || text.contains("ZIPDISK") || text.contains("FAST CLOUD") -> {
                    try {
                        val zipUrl = if (link.startsWith("http")) link else "$mainUrl$link"
                        
                        // Visit the zfile page to get the actual download link
                        val zipDoc = app.get(zipUrl, timeout = 10L).document
                        
                        // Look for the download link or button
                        val downloadLink = zipDoc.selectFirst("a[href*='cloudserver']")?. attr("href")
                            ?: zipDoc.selectFirst("button#cloud")?.let { button ->
                                // This requires dynamic link generation, return the page URL
                                // as CloudStream can't execute JavaScript to generate the link
                                null
                            }
                        
                        if (downloadLink != null) {
                            callback.invoke(
                                newExtractorLink("GDFlix[ZipDisk]", "GDFlix[ZipDisk] $fileName[$fileSize]", downloadLink) {
                                    this.quality = getIndexQuality(fileName)
                                }
                            )
                        } else {
                            Log.d("GDFlix", "ZipDisk requires dynamic link generation, skipping")
                        }
                    } catch (e: Exception) {
                        Log.d("ZipDisk", e.toString())
                    }
                }
                
                text.contains("DIRECT DL") -> {
                    callback.invoke(
                        newExtractorLink("GDFlix[Direct]", "GDFlix[Direct] $fileName[$fileSize]", link) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("PixelDrain DL") -> {
                    val link = anchor.attr("href")
                    callback.invoke(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $fileName[$fileSize]",
                            link
                        ) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("Index Links") -> {
                    try {
                        val link = anchor.attr("href")
                        app.get("$mainUrl$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = mainUrl + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val source = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("GDFlix[Index]", "GDFlix[Index] $fileName[$fileSize]", source) {
                                                this.quality = getIndexQuality(fileName)
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
                        val driveLink = anchor.attr("href")
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

                                var downloadLink = app.post(
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
                                        this.quality = getIndexQuality(fileName)
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                text.contains("GoFile") -> {
                    try {
                        app.get(anchor.attr("href")).document
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
                    Log.d("Error", "No Server matched")
                }
            }
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
            "User-Agent" to USER_AGENT,
            "Origin" to mainUrl,
            "Referer" to mainUrl,
        )
        //val res = app.get(url)
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val genAccountRes = app.post("$mainApi/accounts", headers = headers).text
        val jsonResp = JSONObject(genAccountRes)
        val token = jsonResp.getJSONObject("data").getString("token") ?: return

        val globalRes = app.get("$mainUrl/dist/js/global.js", headers = headers).text
        val wt = Regex("""appdata\.wt\s*=\s*[\"']([^\"']+)[\"']""").find(globalRes)?.groupValues?.get(1) ?: return

        val response = app.get("$mainApi/contents/$id?wt=$wt",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
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

    private fun getQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}