package com.hdhub4u

import android.annotation.SuppressLint
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.net.URL

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.art"
}

class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.dad"
}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("h3 a,h5 a,div.entry-content p a").mapNotNull {
            val lower = it.absUrl("href").ifBlank { it.attr("href") }
            
            // Filter out invalid/problematic domains
            if (lower.contains("viralkhabarbull", ignoreCase = true) || 
                lower.contains("?id=") || 
                !lower.startsWith("http")) {
                Log.d("Hblinks", "Skipping invalid/redirect URL: $lower")
                return@mapNotNull
            }
            
            val href = lower.lowercase()
            when {
                "hubdrive" in lower -> Hubdrive().getUrl(href, name, subtitleCallback, callback)
                "hubcloud" in lower -> HubCloud().getUrl(href, name, subtitleCallback, callback)
                "hubcdn" in lower -> HUBCDN().getUrl(href, name, subtitleCallback, callback)
                "pixeldrain" in lower || "pixeldra" in lower -> loadSourceNameExtractor(name, lower, "", subtitleCallback, callback)
                else -> {}
            }
        }
    }
}

class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.cloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val docString = app.get(url).document.toString()
            val encoded = Regex("r=([A-Za-z0-9+/=]+)").find(docString)?.groups?.get(1)?.value
            
            if (!encoded.isNullOrEmpty()) {
                val decoded = base64Decode(encoded)
                val m3u8 = decoded.substringAfterLast("link=")
                
                if (m3u8.isNotEmpty() && (m3u8.startsWith("http") || m3u8.contains(".m3u8"))) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = m3u8,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } else {
                    Log.e("Hubcdnn", "Invalid m3u8 URL: $m3u8")
                }
            } else {
                Log.e("Hubcdnn", "Encoded URL not found in response")
            }
        } catch (e: Exception) {
            Log.e("Hubcdnn", "Error extracting: ${e.message}")
        }
    }
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    @SuppressLint("SuspiciousIndentation")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            
            // Try multiple selectors for download button
            val href = document.select("a.btn:contains(HubCloud), a.btn:contains(Server), a.btn[href*='hubcloud']").attr("href")
                .ifEmpty { document.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href") }
                .ifEmpty { document.select("a.btn-primary[href]").attr("href") }
            
            if (href.isEmpty()) {
                Log.e("Hubdrive", "No download link found on page: $url")
                return
            }
            
            Log.d("Hubdrive", "Found link: $href")
            
            if (href.contains("hubcloud", ignoreCase = true)) {
                HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
            } else {
                loadExtractor(href, "HubDrive", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("Hubdrive", "Error extracting from $url: ${e.message}")
        }
    }
}


class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        source: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val realUrl = url.takeIf {
            try { URL(it); true } catch (e: Exception) { Log.e("HubCloud", "Invalid URL: ${e.message}"); false }
        } ?: return
        
        Log.d("HubCloud", "Processing URL: $realUrl")

        val baseUrl=getBaseUrl(realUrl)

        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val rawHref = app.get(realUrl).document.select("#download").attr("href")
                if (rawHref.startsWith("http", ignoreCase = true)) {
                    rawHref
                } else {
                    baseUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
                }
            }
        } catch (e: Exception) {
            Log.e("HubCloud", "Failed to extract href: ${e.message}")
            ""
        }

        if (href.isBlank()) {
            Log.w("HubCloud", "No valid href found")
            return
        }

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        val headerDetails = cleanTitle(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }
        val quality = getIndexQuality(header)

        document.select("div.card-body h2 a.btn").amap { element ->
            val link = element.attr("href")
            val text = element.text()

            when {
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$source [FSL Server]",
                            "$source [FSL Server] $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("Download File", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$source",
                            "$source $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("BuzzServer", ignoreCase = true) -> {
                    val buzzResp = app.get("$link/download", referer = link, allowRedirects = false)
                    val dlink = buzzResp.headers["hx-redirect"].orEmpty()
                    if (dlink.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "$source [BuzzServer]",
                                "$source [BuzzServer] $labelExtras",
                                dlink,
                            ) { this.quality = quality }
                        )
                    } else {
                        Log.w("HubCloud", "BuzzServer: No redirect")
                    }
                }

                text.contains("pixeldra", ignoreCase = true) || text.contains("pixel", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("S3 Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$source S3 Server",
                            "$source S3 Server $labelExtras",
                            link,
                        ) { this.quality = quality }
                    )
                }

                text.contains("10Gbps", ignoreCase = true) -> {
                    var currentLink = link
                    var redirectUrl: String? = null

                    while (true) {
                        val response = app.get(currentLink, allowRedirects = false)
                        redirectUrl = response.headers["location"]
                        if (redirectUrl == null) {
                            Log.e("HubCloud", "10Gbps: No redirect")
                            return@amap
                        }
                        if ("link=" in redirectUrl) break
                        currentLink = redirectUrl
                    }
                    val finalLink = redirectUrl?.substringAfter("link=") ?: return@amap
                        callback.invoke(
                            newExtractorLink(
                                "10Gbps [Download]",
                                "10Gbps [Download] $labelExtras",
                                finalLink,
                            ) { this.quality = quality }
                        )
                }
                else -> {
                    loadExtractor(link, "", subtitleCallback, callback)
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P2160.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            ""
        }
    }
}

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.fans"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url).document
            val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

            if (scriptText.isNullOrEmpty()) {
                Log.e("HUBCDN", "Script with reurl not found")
                return
            }

            val encodedUrl = Regex("reurl\\\\s*=\\\\s*\\\"([^\\\"]+)\\\"")
                .find(scriptText)
                ?.groupValues?.get(1)
                ?.substringAfter("?r=")

            if (encodedUrl.isNullOrEmpty()) {
                Log.e("HUBCDN", "Encoded URL not found in script")
                return
            }

            val decodedUrl = base64Decode(encodedUrl).substringAfterLast("link=")

            if (decodedUrl.isNotEmpty() && decodedUrl.startsWith("http")) {
                callback(
                    newExtractorLink(
                        this.name,
                        this.name,
                        decodedUrl,
                        INFER_TYPE,
                    ) {
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                Log.e("HUBCDN", "Invalid decoded URL: $decodedUrl")
            }
        } catch (e: Exception) {
            Log.e("HUBCDN", "Error extracting: ${e.message}")
        }
    }
}


