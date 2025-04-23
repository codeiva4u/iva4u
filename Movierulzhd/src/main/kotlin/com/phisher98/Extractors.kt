package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker

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
    override var mainUrl = "https://movierulz2025.bar"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = app.get(url).document.selectFirst("iframe")?.attr("src") ?: ""
        val res = app.get(
            href, 
            headers = mapOf(
                "Accept-Language" to "en-US,en;q=0.5",
                "sec-fetch-dest" to "iframe"
            )
        ).document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        
        val m3u8 = JsUnpacker(res).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
                ?: Regex("file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                m3u8 ?: "",
                url,
                Qualities.P1080.value,
                type = ExtractorLinkType.M3U8,
            )
        )
    }
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl).document
        val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            val fileRegex = Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
                ?: Regex("file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
            
            fileRegex?.let { link ->
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
    override val mainUrl = "https://akamaicdn.life"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("user-agent" to "okhttp/4.12.0")
        val res = app.get(url, referer = referer, headers = headers).document
        val mappers = res.selectFirst("script:containsData(sniff\\()")?.data()?.substringAfter("sniff(")
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

class Mocdn : Akamaicdn() {
   override val name = "Mocdn"
   override val mainUrl = "https://mocdn.art"
}

// New extractor for the updated Movierulzhd site
class MovieRulzHDEmbed : ExtractorApi() {
    override var name = "MovieRulzHDEmbed"
    override var mainUrl = "https://1movierulzhd.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        
        // Try to find the iframe first
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        
        if (!iframeSrc.isNullOrEmpty()) {
            val iframeDoc = app.get(iframeSrc, referer = url).document
            
            // Look for the packed script in the iframe
            val packedScript = iframeDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            
            if (!packedScript.isNullOrEmpty()) {
                val unpacked = JsUnpacker(packedScript).unpack()
                
                // Try different patterns to find the m3u8 URL
                val m3u8Url = listOf(
                    Regex("sources:\\s*\\[\\{file:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)").find(unpacked ?: ""),
                    Regex("file:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)").find(unpacked ?: ""),
                    Regex("src:\\s*\"(https?://[^\"]+\\.m3u8[^\"]*)").find(unpacked ?: "")
                ).firstNotNullOfOrNull { it?.groupValues?.get(1) }
                
                if (!m3u8Url.isNullOrEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            m3u8Url,
                            iframeSrc,
                            Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    return
                }
            }
            
            // Try looking for direct video elements
            val videoSrc = iframeDoc.selectFirst("video source")?.attr("src")
            if (!videoSrc.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        videoSrc,
                        iframeSrc,
                        Qualities.P1080.value,
                        type = if (videoSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
                return
            }
        }
        
        // Try to find embedded players or JSON data with video links
        document.select("script").forEach { script ->
            val scriptData = script.data()
            if (scriptData.contains("player") && scriptData.contains("file")) {
                val m3u8Url = Regex("file['\"]?\\s*:\\s*['\"]?([^'\"]+\\.m3u8[^'\"]*)").find(scriptData)?.groupValues?.get(1)
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
                    return
                }
            }
        }
    }
}