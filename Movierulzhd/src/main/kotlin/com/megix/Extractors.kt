package com.Phisher98

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
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val iframe = document.selectFirst("iframe")?.attr("src") ?: ""
        
        // यदि iframe खाली है, तो सीधे URL का उपयोग करें
        val targetUrl = if (iframe.isNotEmpty()) iframe else url
        
        val headers = mapOf(
            "Accept-Language" to "en-US,en;q=0.5",
            "sec-fetch-dest" to "iframe",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
        )
        
        val response = app.get(targetUrl, headers = headers)
        val scriptData = response.document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        
        val m3u8 = JsUnpacker(scriptData).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
        }
        
        if (!m3u8.isNullOrEmpty()) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    targetUrl,
                    Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
        } else {
            // फॉलबैक: यदि JsUnpacker से m3u8 नहीं मिला, तो सीधे HTML से खोजें
            val directM3u8 = Regex("file:\\\\s*['\\\"](.+?\\.m3u8)['\\\"]|source\\\\s*src=['\\\"](.+?\\.m3u8)['\\\"]|file:\\\\s*['\\\"](.+?\\.mp4)['\\\"]|source\\\\s*src=['\\\"](.+?\\.mp4)['\\\"]")
                .find(response.text)?.groupValues?.firstOrNull { it.isNotEmpty() && (it.contains(".m3u8") || it.contains(".mp4")) }
            
            if (!directM3u8.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        directM3u8,
                        targetUrl,
                        Qualities.P1080.value,
                        type = if (directM3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
            }
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
                        ExtractorLink(
                            this.name,
                            this.name,
                            link,
                            referer ?: "",
                            Qualities.Unknown.value,
                            type = INFER_TYPE
                        )
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
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Referer" to (referer ?: url)
        )
        
        val res = app.get(url, headers = headers)
        val document = res.document
        
        // पहले स्क्रिप्ट से sniff फंक्शन के माध्यम से IDs निकालने का प्रयास करें
        val scriptWithSniff = document.selectFirst("script:containsData(sniff\\()")
        if (scriptWithSniff != null) {
            val mappers = scriptWithSniff.data()?.substringAfter("sniff(")
                ?.substringBefore(");") ?: return
            val ids = mappers.split(",").map { it.replace("\"", "") }
            if (ids.size >= 3) {
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
                return
            }
        }
        
        // फॉलबैक: iframe से वीडियो स्रोत निकालने का प्रयास करें
        val iframe = document.selectFirst("iframe")?.attr("src")
        if (!iframe.isNullOrEmpty()) {
            val iframeRes = app.get(iframe, headers = headers)
            val iframeDoc = iframeRes.document
            
            // iframe में स्क्रिप्ट से m3u8 लिंक निकालने का प्रयास करें
            val scriptData = iframeDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            if (scriptData != null) {
                val m3u8 = JsUnpacker(scriptData).unpack()?.let { unPacked ->
                    Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
                }
                
                if (!m3u8.isNullOrEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            m3u8,
                            iframe,
                            Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                    return
                }
            }
            
            // सीधे HTML से m3u8 या mp4 लिंक निकालने का प्रयास करें
            val directLink = Regex("file:\\\\s*['\\\"](.+?\\.m3u8)['\\\"]|source\\\\s*src=['\\\"](.+?\\.m3u8)['\\\"]|file:\\\\s*['\\\"](.+?\\.mp4)['\\\"]|source\\\\s*src=['\\\"](.+?\\.mp4)['\\\"]")
                .find(iframeRes.text)?.groupValues?.firstOrNull { it.isNotEmpty() && (it.contains(".m3u8") || it.contains(".mp4")) }
            
            if (!directLink.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        directLink,
                        iframe,
                        Qualities.P1080.value,
                        type = if (directLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        headers = headers
                    )
                )
                return
            }
        }
    }
}
class Mocdn:Akamaicdn(){
   override val name = "Mocdn"
   override val mainUrl = "https://mocdn.art"
}
