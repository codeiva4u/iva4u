package com.megix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
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
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.one"
    override val requiresReferer = false

    private val baseUrlRegex = Regex("""https?://[^/]+""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("HubCloud", "Starting extraction for: $url")
            
            // Validate URL
            if (!url.matches(Regex("""https?://(?:hubcloud\.(?:one|fit|ink|art|dad|bz))/drive/\w+"""))) {
                Log.e("HubCloud", "Invalid HubCloud URL: $url")
                return
            }

            // Get initial page
            val doc = app.get(url).document
            
            // Find download link button
            val downloadBtn = doc.selectFirst("a#download[href]")
            if (downloadBtn == null) {
                Log.e("HubCloud", "Download button not found")
                return
            }

            val downloadLink = downloadBtn.attr("href")
            Log.d("HubCloud", "Download link: $downloadLink")

            // Get download page with all servers
            val downloadDoc = app.get(downloadLink, referer = url).document
            val fileInfo = downloadDoc.selectFirst("div.card-header")?.text() ?: ""
            val fileSize = downloadDoc.selectFirst("i#size")?.text() ?: ""

            // Extract all download servers
            downloadDoc.select("a.btn[href]").forEach { btn ->
                val btnUrl = btn.attr("href")
                val btnText = btn.text()

                if (btnUrl.isBlank() || btnUrl.startsWith("#")) return@forEach

                when {
                    // FSL Server (Direct download)
                    btnText.contains("FSL", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [FSL]",
                                "$name [FSL] $fileInfo [$fileSize]",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileInfo)
                            }
                        )
                    }

                    // PixelDrain Server
                    btnUrl.contains("pixeldrain", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [PixelDrain]",
                                "$name [PixelDrain] $fileInfo [$fileSize]",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileInfo)
                            }
                        )
                    }

                    // 10Gbps Server (pixel.hubcdn.fans direct link)
                    btnUrl.contains("pixel.hubcdn.fans", ignoreCase = true) || 
                    btnUrl.contains("hubcdn", ignoreCase = true) ||
                    btnText.contains("10Gbps", ignoreCase = true) || 
                    btnText.contains("10GBPS", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [10Gbps]",
                                "$name [10Gbps] $fileInfo [$fileSize]",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileInfo)
                            }
                        )
                    }

                    // Mega Server (bt7.api.mega.co.nz)
                    btnUrl.contains("mega.co.nz", ignoreCase = true) || 
                    btnUrl.contains("mega.nz", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [Mega]",
                                "$name [Mega] $fileInfo [$fileSize]",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileInfo)
                            }
                        )
                    }
                }
            }

            Log.d("HubCloud", "Extraction completed")
        } catch (e: Exception) {
            Log.e("HubCloud", "Extraction error: ${e.message}")
            e.printStackTrace()
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

open class GDFlix : ExtractorApi() {
    override val name: String = "GDFlix"
    override var mainUrl: String = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("GDFlix", "Starting extraction for: $url")
            
            // Validate GDFlix URL
            if (!url.matches(Regex("""https?://(?:gdflix\.dev|new7\.gdflix\.net)/file/\w+"""))) {
                Log.e("GDFlix", "Invalid GDFlix URL: $url")
                return
            }

            // Get main page
            val doc = app.get(url, referer = referer).document
            val fileName = doc.selectFirst("div.card-header")?.text() ?: ""

            // Extract all download buttons
            doc.select("a[href]").forEach { element ->
                val btnUrl = element.attr("abs:href")
                val btnText = element.text()

                if (btnUrl.isBlank() || btnUrl.startsWith("#") || btnUrl.contains("login")) {
                    return@forEach
                }

                when {
                    // Instant Download (10GBPS busycdn)
                    btnUrl.contains("instant.busycdn", ignoreCase = true) || 
                    btnUrl.contains("busycdn", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [Instant]",
                                "$name [Instant] $fileName",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileName)
                            }
                        )
                    }

                    // PixelDrain Server
                    btnUrl.contains("pixeldrain", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "$name [PixelDrain]",
                                "$name [PixelDrain] $fileName",
                                btnUrl
                            ) {
                                quality = getIndexQuality(fileName)
                            }
                        )
                    }

                    // Fast Cloud / ZipDisk (new7.gdflix.net/zfile)
                    btnUrl.contains("/zfile/", ignoreCase = true) -> {
                        try {
                            val zipDoc = app.get(btnUrl, referer = url).document
                            
                            // Try multiple selectors for download link
                            val zipDownloadLink = zipDoc.selectFirst(
                                "a[href*='cloudserver'], a[href*='.workers.dev'], a.btn[href*='download']"
                            )?.attr("abs:href")
                            
                            if (!zipDownloadLink.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        "$name [ZipDisk]",
                                        "$name [ZipDisk] $fileName",
                                        zipDownloadLink
                                    ) {
                                        quality = getIndexQuality(fileName)
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Error extracting ZipDisk link: ${e.message}")
                        }
                    }

                    // GoFile Mirror (goflix.sbs)
                    btnUrl.contains("goflix.sbs", ignoreCase = true) -> {
                        try {
                            // Follow goflix redirect to get actual gofile link
                            val gofilePage = app.get(btnUrl, referer = url, allowRedirects = true)
                            val gofileDoc = gofilePage.document
                            
                            val gofileLink = gofileDoc.selectFirst("a[href*='gofile.io']")?.attr("abs:href") 
                                ?: gofilePage.url
                            
                            callback.invoke(
                                newExtractorLink(
                                    "$name [GoFile]",
                                    "$name [GoFile] $fileName",
                                    gofileLink
                                ) {
                                    quality = getIndexQuality(fileName)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e("GDFlix", "Error extracting GoFile link: ${e.message}")
                        }
                    }

                    // Telegram File - Skip
                    btnUrl.contains("filesgram", ignoreCase = true) || btnUrl.contains("telegram", ignoreCase = true) -> {
                        Log.d("GDFlix", "Skipping Telegram link")
                    }
                }
            }

            Log.d("GDFlix", "Extraction completed")
        } catch (e: Exception) {
            Log.e("GDFlix", "Extraction error: ${e.message}")
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