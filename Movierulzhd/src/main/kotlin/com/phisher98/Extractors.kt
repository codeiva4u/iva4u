package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.net.URI // Added import for URI
import com.lagradost.api.Log // Added import for Log

class FMHD : Filesim() {
    override val name = "FMHD"
    override var mainUrl = "https://fmhd.bar/"
    override val requiresReferer = true
}

class Playonion : Filesim() {
    override val mainUrl = "https://playonion.sbs"
}


class Luluvdo : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

class Lulust : StreamWishExtractor() {
    override val mainUrl = "https://lulu.st"
}

class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url).document
            val href = document.selectFirst("iframe")?.attr("src")
            if (!href.isNullOrEmpty()) {
                val iframeDoc = app.get(
                    href,
                    headers = mapOf(
                        "Accept-Language" to "en-US,en;q=0.5",
                        "sec-fetch-dest" to "iframe",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
                    )
                ).document

                val script = iframeDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
                if (script != null) {
                    val unpacked = JsUnpacker(script).unpack()
                    if (unpacked != null) {
                        val m3u8Url = Regex("(?:file|src):\\\\s*['\"](https?:\\\\/\\\\/[^'\"]+\\\\.m3u8)")
                            .find(unpacked)?.groupValues?.firstOrNull { it.isNotEmpty() && it.endsWith(".m3u8") }
                        if (!m3u8Url.isNullOrEmpty()) {
                            callback.invoke(
                                ExtractorLink(
                                    name,
                                    name,
                                    m3u8Url,
                                    href,
                                    Qualities.P1080.value,
                                    type = ExtractorLinkType.M3U8,
                                    headers = mapOf(
                                        "Referer" to href,
                                        "Origin" to getBaseUrl(href)
                                    )
                                )
                            )
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FilemoonV2", "Error extracting video from $url: ${e.message}\n${e.stackTraceToString()}")
            // Removed retry logic for simplicity, can be added back if needed
            // if(retryCount < 3) {
            //     delay(1000L * (retryCount + 1))
            //     getUrl(url, referer, subtitleCallback, callback, retryCount + 1)
            // }
        }
    }

    // Moved getBaseUrl outside the class to be accessible by RpmplayMeExtractor
    // private fun getBaseUrl(url: String): String {
    //     return URI(url).let {
    //         "${it.scheme}://${it.host}"
    //     }
    // }
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url,referer=mainUrl).document
            val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
            JsUnpacker(extractedpack).unpack()?.let { unPacked ->
                Regex("sources:\\\\[\\\\{s*|file:rc)'\"(htp?/[^'\"]+.m3u8'\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                    return listOf(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = link,
                            INFER_TYPE
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FMX", "Error processing $url: ${e.message}\n${e.stackTraceToString()}")
        }
        return null
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    override val mainUrl = "https://akamaicdn.life"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers= mapOf("user-agent" to "okhttp/4.12.0")
        val res = app.get(url, referer = referer, headers = headers).document
        val mappers = res.selectFirst("script:containsData(sniff\\\\()")?.data()?.substringAfter("sniff(")
            ?.substringBefore(");") ?: return
        val ids = mappers.split(",").map { it.replace("\"", "") }
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
                headers = headers
            )
        )
    }
}
class Mocdn:Akamaicdn(){
    override val name = "Mocdn"
    override val mainUrl = "https://mocdn.art"
}

// Helper function moved outside classes
private fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

// New Extractor for Vidstack player with relative M3U8 source
open class RpmplayMeExtractor : ExtractorApi() {
    override val name = "RpmplayMe" // Generic name, adjust if needed
    // Assuming the domain pattern holds, otherwise make this more generic
    override val mainUrl = "https://rpmplay.me" // Base domain, might need adjustment
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, // This will be the iframe src URL
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val document = app.get(url, referer = referer).document
            val sourceTag = document.selectFirst("media-player source[src]")

            if (sourceTag == null) {
                Log.e(name, "Could not find source tag in media-player for URL: $url")
                return
            }

            val relativeM3u8Path = sourceTag.attr("src")
            if (!relativeM3u8Path.endsWith(".m3u8")) {
                 Log.e(name, "Source src attribute does not end with .m3u8: $relativeM3u8Path")
                 return
            }

            // Construct the full URL using the origin of the input URL
            val baseUrl = getBaseUrl(url)
            val fullM3u8Url = "$baseUrl$relativeM3u8Path" // Assumes relative path starts with /

            Log.d(name, "Found M3U8 URL: $fullM3u8Url")

            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    fullM3u8Url,
                    url, // Use the iframe URL as the referer for the M3U8 request
                    Qualities.Unknown.value, // Quality is unknown from this tag
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to url) // Add referer header
                )
            )

        } catch (e: Exception) {
            Log.e(name, "Error extracting video from $url: ${e.message}\n${e.stackTraceToString()}")
        }
    }
}