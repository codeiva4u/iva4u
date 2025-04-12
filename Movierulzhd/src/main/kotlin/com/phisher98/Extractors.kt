package com.phisher98

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker
import java.net.URI

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
    // mainUrl is often dynamic, avoid hardcoding if possible or update frequently
    override var mainUrl = "https://filemoon.sx" // Example, might need adjustment
    override val requiresReferer = true

    // Updated Filemoon extractor logic (common pattern)
    private val packedRegex = Regex("""eval\(function\(p,a,c,k,e,d\).*?""")
    private val sourceRegex = Regex("""sources:\[\{file:"(.*?)"""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Sometimes the initial URL is the direct Filemoon link
            val directUrl = if (URI(url).host.contains("filemoon")) url else {
                // Otherwise, try to extract iframe src
                app.get(url, referer = referer).document.selectFirst("iframe[src*=filemoon]")?.attr("abs:src")
            }

            if (directUrl == null) {
                Log.e(name, "Could not find Filemoon iframe/link for URL: $url")
                return
            }

            val filemoonPage = app.get(directUrl, referer = referer ?: url).document
            val packed = packedRegex.find(filemoonPage.html())?.value
            val unpacked = JsUnpacker(packed).unpack()

            if (unpacked == null) {
                Log.e(name, "Failed to unpack JS for URL: $directUrl")
                return
            }

            val m3u8 = sourceRegex.find(unpacked)?.groupValues?.getOrNull(1)
            if (m3u8 != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        m3u8,
                        directUrl, // Use filemoon URL as referer for the stream
                        Qualities.Unknown.value, // Quality detection from m3u8 is preferred
                        type = ExtractorLinkType.M3U8,
                    )
                )
            } else {
                Log.e(name, "Could not find m3u8 source in unpacked JS for URL: $directUrl")
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting Filemoon link for $url: ${e.message}")
        }
    }
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url,referer=mainUrl).document
            val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
            JsUnpacker(extractedpack).unpack()?.let { unPacked ->
                Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
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
            return null
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    // Base URL might change
    override val mainUrl = "https://akamaicdn.life" // Example, might need adjustment
    override val requiresReferer = true

    // Regex to find the m3u8 URL directly if possible
    private val m3u8Regex = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
    // Regex for the sniff pattern as fallback
    private val sniffRegex = Regex("""sniff\("([^"]+)",\s*"([^"]+)"\)""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("user-agent" to USER_AGENT) // Use standard USER_AGENT
            val res = app.get(url, referer = referer, headers = headers).text // Get text directly

            // Try finding m3u8 directly first
            var m3u8Link = m3u8Regex.find(res)?.groupValues?.getOrNull(1)

            if (m3u8Link == null) {
                // Fallback to sniff pattern
                val sniffMatch = sniffRegex.find(res)
                if (sniffMatch != null) {
                    val id1 = sniffMatch.groupValues.getOrNull(1)
                    val id2 = sniffMatch.groupValues.getOrNull(2)
                    if (id1 != null && id2 != null) {
                        // Construct the URL based on common patterns, might need adjustment
                        val host = URI(url).host // Use the host from the input URL
                        m3u8Link = "https://$host/m3u8/$id1/$id2/master.txt?s=1&cache=1" // Construct potential m3u8 URL
                        Log.d(name, "Constructed m3u8 from sniff: $m3u8Link")
                    }
                }
            }

            if (m3u8Link != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        m3u8Link,
                        url, // Use original URL as referer
                        Qualities.Unknown.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
            } else {
                Log.e(name, "Could not find m3u8 link or sniff pattern in response for URL: $url")
            }
        } catch (e: Exception) {
            Log.e(name, "Error extracting Akamaicdn link for $url: ${e.message}")
        }
    }
}
class Mocdn:Akamaicdn(){
   override val name = "Mocdn"
   override val mainUrl = "https://mocdn.art"
}