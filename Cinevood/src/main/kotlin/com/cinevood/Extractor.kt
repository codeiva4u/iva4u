package com.cinevood

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink


class Hubcloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        
        // Validate URL
        val realUrl = url.takeIf {
            try { java.net.URI(it).toURL(); true } catch (_: Exception) { false }
        } ?: return

        // Extract download button href
        val href = try {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val rawHref = app.get(realUrl).document.select("#download").attr("href")
                if (rawHref.startsWith("http", ignoreCase = true)) {
                    rawHref
                } else {
                    mainUrl.trimEnd('/') + "/" + rawHref.trimStart('/')
                }
            }
        } catch (_: Exception) {
            ""
        }

        if (href.isBlank()) return

        val document = app.get(href).document
        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()
        
        val quality = getIndexQuality(header)
        val labelExtras = if (size.isNotEmpty()) "[$size]" else ""

        // Extract all download links
        document.select("div.card-body h2 a.btn, a.btn-success, a.btn-danger").forEach { element ->
            val link = element.attr("href")
            val text = element.text()

            when {
                text.contains("FSL Server", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name [FSL Server]",
                            "$name [FSL Server] $labelExtras",
                            link,
                        ) { 
                            this.referer = href
                            this.quality = quality 
                        }
                    )
                }

                text.contains("FSLv2", ignoreCase = true) -> {
                    callback.invoke(
                        newExtractorLink(
                            "$name [FSLv2]",
                            "$name [FSLv2] $labelExtras",
                            link,
                        ) { 
                            this.referer = href
                            this.quality = quality 
                        }
                    )
                }

                text.contains("10Gbps", ignoreCase = true) -> {
                    // Follow redirects to get final link
                    var currentLink = link
                    var redirectCount = 0
                    val maxRedirects = 3

                    while (redirectCount < maxRedirects) {
                        val response = app.get(currentLink, allowRedirects = false)
                        val redirectUrl = response.headers["location"]

                        if (redirectUrl == null) break

                        if ("link=" in redirectUrl) {
                            val finalLink = redirectUrl.substringAfter("link=")
                            callback.invoke(
                                newExtractorLink(
                                    "$name [10Gbps]",
                                    "$name [10Gbps] $labelExtras",
                                    finalLink,
                                ) { 
                                    this.referer = href
                                    this.quality = quality 
                                }
                            )
                            return@forEach
                        }

                        currentLink = redirectUrl
                        redirectCount++
                    }
                }

                text.contains("pixel", ignoreCase = true) || link.contains("pixeldrain", ignoreCase = true) -> {
                    val baseUrlLink = getBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"

                    callback.invoke(
                        newExtractorLink(
                            "Pixeldrain",
                            "Pixeldrain $labelExtras",
                            finalURL,
                        ) { 
                            this.referer = href
                            this.quality = quality 
                        }
                    )
                }
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: com.lagradost.cloudstream3.utils.Qualities.Unknown.value
    }

    private fun getBaseUrl(url: String): String {
        return try {
            java.net.URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Exception) {
            ""
        }
    }
}


class Filepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.cloud"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Filepress URLs: https://new3.filepress.cloud/file/{id}
        // Watch page: https://new3.filepress.cloud/video/{id}
        
        val fileId = url.substringAfterLast("/file/").substringBefore("?")
        val baseUrl = url.substringBefore("/file/")
        
        // Try watch page first
        val watchUrl = "$baseUrl/video/$fileId"
        
        try {
            val watchDoc = app.get(watchUrl).document
            
            // Look for iframe sources (StreamWish, DoodStream are usually embedded in iframes)
            watchDoc.select("iframe[src]").forEach { iframe ->
                val iframeSrc = iframe.attr("src")
                if (iframeSrc.isNotBlank()) {
                    // Use CloudStream3's built-in extractors
                    loadExtractor(iframeSrc, referer, subtitleCallback, callback)
                }
            }
            
            // Also check for direct video sources
            watchDoc.select("source[src], video source").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    loadExtractor(src, referer, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {
            // Fallback: Try file page
            try {
                val document = app.get(url).document
                
                // Look for StreamWish and DoodStream buttons/links
                document.select("a, button").forEach { element ->
                    val text = element.text()
                    val href = element.attr("href")
                    val onclick = element.attr("onclick")
                    
                    // Extract URL from onclick or href
                    val linkToExtract = when {
                        href.isNotBlank() && !href.startsWith("#") -> href
                        onclick.contains("window.open") -> {
                            Regex("window\\.open\\s*\\(['\"]([^'\"]+)['\"]").find(onclick)?.groupValues?.get(1)
                        }
                        onclick.contains("location.href") -> {
                            Regex("location\\.href\\s*=\\s*['\"]([^'\"]+)['\"]").find(onclick)?.groupValues?.get(1)
                        }
                        else -> null
                    }
                    
                    if (linkToExtract != null) {
                        when {
                            text.contains("StreamWish", ignoreCase = true) || 
                            linkToExtract.contains("streamwish", ignoreCase = true) ||
                            linkToExtract.contains("swhoi", ignoreCase = true) -> {
                                loadExtractor(linkToExtract, referer, subtitleCallback, callback)
                            }
                            
                            text.contains("DoodStream", ignoreCase = true) || 
                            linkToExtract.contains("doodstream", ignoreCase = true) ||
                            linkToExtract.contains("dood", ignoreCase = true) -> {
                                loadExtractor(linkToExtract, referer, subtitleCallback, callback)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Silently fail
            }
        }
    }
}

