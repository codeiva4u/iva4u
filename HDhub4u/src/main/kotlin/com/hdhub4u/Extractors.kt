package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.art"
}

class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.pro"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Get all links from the page
            val document = app.get(url, referer = referer).document
            val links = document.select("h3 a, div.entry-content p a").map { it.attr("href") }
            
            // Filter out non-video links and load valid extractors
            links.filter { 
                it.isNotEmpty() && (
                    it.contains("stream") || 
                    it.contains("drive") || 
                    it.contains("cdn") || 
                    it.contains("cloud") ||
                    it.contains("hubdrive") ||
                    it.contains(".mp4") ||
                    it.contains(".m3u8")
                )
            }.forEach { href ->
                loadExtractor(href, "HDHUB4U", subtitleCallback, callback)
            }
            
            // Check for direct download links with labels
            document.select("div.entry-content a, div.dlbutton a").forEach { link ->
                val href = link.attr("href")
                val quality = when {
                    link.text().contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                    link.text().contains("720p", ignoreCase = true) -> Qualities.P720.value
                    link.text().contains("480p", ignoreCase = true) -> Qualities.P480.value
                    link.text().contains("360p", ignoreCase = true) -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }
                
                if (href.isNotEmpty() && (href.contains(".mp4") || href.endsWith(".mkv"))) {
                    callback.invoke(
                        ExtractorLink(
                            "HDhub4u Direct",
                            "HDhub4u Direct ${link.text()}",
                            href,
                            url,
                            quality,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("HDhub4u", "Error in Hblinks extractor: ${e.message}")
        }
    }
}

class Hubcdn : ExtractorApi() {
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
            val content = app.get(url).text
            
            // Try different patterns to find the encoded URL
            val encoded = Regex("r=([A-Za-z0-9+/=]+)").find(content)?.groups?.get(1)?.value
                ?: Regex("source: ?[\"']([A-Za-z0-9+/=]+)[\"']").find(content)?.groups?.get(1)?.value
                
            if (!encoded.isNullOrEmpty()) {
                val decodedUrl = try {
                    base64Decode(encoded)
                } catch (e: Exception) {
                    encoded // Use as is if decode fails
                }
                
                val finalUrl = if (decodedUrl.contains("link=")) {
                    decodedUrl.substringAfterLast("link=")
                } else {
                    decodedUrl
                }
                
                if (finalUrl.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = finalUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            } else {
                // Check for packed JavaScript
                val packedJs = content.substringAfter("eval(function(p,a,c,k,e,d)", "")
                    .substringBefore("</script>", "")
                
                if (packedJs.isNotEmpty()) {
                    val unpacked = JsUnpacker("eval(function(p,a,c,k,e,d)" + packedJs).unpack()
                    val m3u8Url = Regex("file:[\"']([^\"']+\\.m3u8[^\"']*)").find(unpacked ?: "")?.groupValues?.get(1)
                    
                    if (!m3u8Url.isNullOrEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                this.name,
                                this.name,
                                m3u8Url,
                                url,
                                Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDhub4u", "Error in Hubcdn extractor: ${e.message}")
        }
    }
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.fit"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            
            // Try different button selectors for download links
            val downloadSelectors = listOf(
                ".btn.btn-primary.btn-user.btn-success1.m-1",
                ".btn-success",
                ".btn.download-link",
                "a.btn:contains(Download)",
                "a:contains(Download File)"
            )
            
            for (selector in downloadSelectors) {
                val href = document.select(selector).attr("href")
                if (href.isNotEmpty()) {
                    loadExtractor(href, "HubDrive", subtitleCallback, callback)
                    return
                }
            }
            
            // If no buttons found, try to find iframes
            val iframeSrc = document.select("iframe").attr("src")
            if (iframeSrc.isNotEmpty()) {
                loadExtractor(iframeSrc, "HubDrive", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e("HDhub4u", "Error in Hubdrive extractor: ${e.message}")
        }
    }
}

// New extractor for HDhub4u updated website
class HDhub4uExtractor : ExtractorApi() {
    override val name = "HDhub4u"
    override val mainUrl = "https://hdhub4u.graphics"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document
            
            // Look for multiple approaches to find video links
            
            // 1. Check for iframes
            document.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.isNotEmpty()) {
                    loadExtractor(src, name, subtitleCallback, callback)
                }
            }
            
            // 2. Check for download links with quality labels
            document.select("a[href*=drive], a[href*=cloud], a[href*=stream], a.download, a:contains(Download)").forEach { link ->
                val href = link.attr("href")
                if (href.isNotEmpty()) {
                    val text = link.text()
                    val quality = when {
                        text.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                        text.contains("720p", ignoreCase = true) -> Qualities.P720.value
                        text.contains("480p", ignoreCase = true) -> Qualities.P480.value
                        text.contains("360p", ignoreCase = true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    if (href.contains("http")) {
                        loadExtractor(href, "$name - ${link.text()}", subtitleCallback, callback)
                        
                        // For direct video links
                        if (href.endsWith(".mp4") || href.endsWith(".mkv") || href.endsWith(".m3u8")) {
                            callback.invoke(
                                ExtractorLink(
                                    "$name Direct",
                                    "$name Direct - ${link.text()}",
                                    href,
                                    url,
                                    quality,
                                    type = if (href.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    }
                }
            }
            
            // 3. Check for embedded players in scripts
            document.select("script").forEach { script ->
                val data = script.data()
                
                // Look for JSON data in script
                if (data.contains("player") || data.contains("file")) {
                    val m3u8Url = Regex("file[\": ]*['\"]([^'\"]+\\.m3u8[^'\"]*)").find(data)?.groupValues?.get(1)
                    if (!m3u8Url.isNullOrEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name Player",
                                m3u8Url,
                                url,
                                Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
                
                // Check for packed JS
                if (data.contains("eval(function(p,a,c,k,e,d)")) {
                    val unpacked = JsUnpacker(data).unpack()
                    val m3u8Url = Regex("file[\": ]*['\"]([^'\"]+\\.m3u8[^'\"]*)").find(unpacked ?: "")?.groupValues?.get(1)
                    if (!m3u8Url.isNullOrEmpty()) {
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name Script",
                                m3u8Url,
                                url,
                                Qualities.P1080.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDhub4u", "Error in HDhub4uExtractor: ${e.message}")
        }
    }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.ink"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        source: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realUrl = replaceHubclouddomain(url)
        val href = if (realUrl.contains("hubcloud.php")) {
            realUrl
        } else {
            val regex = "var url = '([^']*)'".toRegex()
            val regexdata = app.get(realUrl).document.selectFirst("script:containsData(url)")?.toString() ?: ""
            regex.find(regexdata)?.groupValues?.get(1).orEmpty()
        }
        if (href.isEmpty()) {
            Log.d("Error", "Not Found")
            return
        }

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text()
        val header = document.selectFirst("div.card-header")?.text()

        document.select("div.card-body a.btn").forEach { linkElement ->
            val link = linkElement.attr("href")
            val quality = getIndexQuality(header)

            when {
                link.contains("www-google-com") -> Log.d("Error:", "Not Found")

                link.contains("technorozen.workers.dev") -> {
                    callback(
                        newExtractorLink(
                            "$source 10GB Server",
                            "$source 10GB Server $size",
                            url = getGBurl(link)
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains("pixeldra.in") || link.contains("pixeldrain") -> {
                    callback(
                        newExtractorLink(
                            "$source Pixeldrain",
                            "$source Pixeldrain $size",
                            url = link
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains("buzzheavier") -> {
                    callback(
                        newExtractorLink(
                            "$source Buzzheavier",
                            "$source Buzzheavier $size",
                            url = "$link/download"
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains(".dev") -> {
                    callback(
                        newExtractorLink(
                            "$source Hub-Cloud",
                            "$source Hub-Cloud $size",
                            url = link
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains("fastdl.lol") -> {
                    callback(
                        newExtractorLink(
                            "$source [FSL] Hub-Cloud",
                            "$source [FSL] Hub-Cloud $size",
                            url = link
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains("hubcdn.xyz") -> {
                    callback(
                        newExtractorLink(
                            "$source [File] Hub-Cloud",
                            "$source [File] Hub-Cloud $size",
                            url = link
                        ) {
                            this.quality = quality
                        }
                    )
                }

                link.contains("gofile.io") -> {
                    loadCustomExtractor(source.orEmpty(), link, "Pixeldrain", subtitleCallback, callback)
                }

                else -> Log.d("Error:", "No Server Match Found")
            }
        }
    }

    private fun getIndexQuality(str: String?) =
        Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.P2160.value

    private suspend fun getGBurl(url: String): String =
        app.get(url).document.selectFirst("#vd")?.attr("href").orEmpty()
}



