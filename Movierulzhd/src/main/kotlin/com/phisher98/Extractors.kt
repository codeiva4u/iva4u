package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
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
    override var mainUrl = "https://1movierulzhd.lol"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // मुख्य iframe source निकालना
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val iframeSrc = doc.selectFirst(".metaframe")?.attr("src") ?: ""
        if (iframeSrc.isEmpty()) {
            // fallback: alternate iframe या script से ट्राय करें
            val altIframe = doc.select("iframe").firstOrNull()?.attr("src") ?: ""
            if (altIframe.isNotEmpty()) {
                extractFilemoon(altIframe, url, callback)
                return
            }
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    "",
                    url,
                    Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
            return
        }
        extractFilemoon(iframeSrc, url, callback)
    }

    private suspend fun extractFilemoon(iframeUrl: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val headers = mapOf(
            "Accept-Language" to "en-US,en;q=0.5",
            "sec-fetch-dest" to "iframe",
            "Referer" to referer.orEmpty(),
            "User-Agent" to USER_AGENT
        )
        
        // नए डोमेन के लिए URL फिक्स करें
        val fixedUrl = if (!iframeUrl.startsWith("http")) {
            if (iframeUrl.startsWith("/")) "https://movierulz.upn.one$iframeUrl" else "https://movierulz.upn.one/$iframeUrl"
        } else iframeUrl
        
        val resDoc = app.get(fixedUrl, headers = headers).document
        
        // पोस्टर इमेज से वीडियो ID निकालना
        val posterUrl = resDoc.select("#player-button-container").firstOrNull()?.attr("style")?.let {
            Regex("background-image: url\\(\"(.*?)\"\\)").find(it)?.groupValues?.getOrNull(1)
        }
        
        if (!posterUrl.isNullOrEmpty()) {
            // पोस्टर URL से वीडियो ID निकालें
            val videoId = posterUrl.split("/").let { parts ->
                if (parts.size >= 3) parts[parts.size - 3] else null
            }
            
            if (!videoId.isNullOrEmpty()) {
                // वीडियो ID से m3u8 URL बनाएं
                val m3u8Url = "https://movierulz.upn.one/m3u8/$videoId/master.m3u8"
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        m3u8Url,
                        fixedUrl,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            }
        }
        
        // JS packed script ढूंढना
        val packedScript = resDoc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
        if (packedScript.isNotEmpty()) {
            val unpacked = JsUnpacker(packedScript).unpack()
            val m3u8 = unpacked?.let {
                Regex("sources:\\[\\{file:\"(.*?)\"").find(it)?.groupValues?.getOrNull(1)
            }
            if (!m3u8.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        m3u8.toString(),
                        fixedUrl,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            }
        }
        
        // fallback: alternate script या sources
        val scripts = resDoc.select("script").mapNotNull { it.data() }
        for (script in scripts) {
            if (script.contains("sources:[{file:")) {
                val m3u8 = Regex("sources:\\[\\{file:\"(.*?)\"").find(script)?.groupValues?.getOrNull(1)
                if (!m3u8.isNullOrEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            m3u8.toString(),
                            fixedUrl,
                            Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                    return
                }
            }
        }
        
        // अगर कुछ नहीं मिला तो empty callback
        callback.invoke(
            ExtractorLink(
                this.name,
                this.name,
                "",
                fixedUrl,
                Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = headers
            )
        )
    }
}

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url, referer = mainUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        // packed JS script
        val packedScript = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
        if (packedScript.isNotEmpty()) {
            JsUnpacker(packedScript).unpack()?.let { unPacked ->
                Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.getOrNull(1)?.let { link ->
                    return listOf(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = link,
                            INFER_TYPE
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                }
            }
        }
        // fallback: alternate script
        val scripts = response.select("script").mapNotNull { it.data() }
        for (script in scripts) {
            if (script.contains("sources:[{file:")) {
                val link = Regex("sources:\\[\\{file:\"(.*?)\"").find(script)?.groupValues?.getOrNull(1)
                if (!link.isNullOrEmpty()) {
                    return listOf(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = link,
                            INFER_TYPE
                        ) {
                            this.referer = referer ?: ""
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf("User-Agent" to USER_AGENT)
                        }
                    )
                }
            }
        }
        return null
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    override val mainUrl = "https://movierulz.upn.one"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("user-agent" to USER_AGENT)
        val res = app.get(url, referer = referer, headers = headers).document
        
        // पोस्टर इमेज से वीडियो ID निकालना
        val posterUrl = res.select("#player-button-container").firstOrNull()?.attr("style")?.let {
            Regex("background-image: url\\(\"(.*?)\"\\)").find(it)?.groupValues?.getOrNull(1)
        }
        
        if (!posterUrl.isNullOrEmpty()) {
            // पोस्टर URL से वीडियो ID निकालें
            val videoId = posterUrl.split("/").let { parts ->
                if (parts.size >= 3) parts[parts.size - 3] else null
            }
            
            if (!videoId.isNullOrEmpty()) {
                // वीडियो ID से m3u8 URL बनाएं
                val m3u8Url = "$mainUrl/m3u8/$videoId/master.m3u8"
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.P1080.value
                        this.headers = headers
                    }
                )
                return
            }
        }
        
        // पुराना तरीका: JS sniff function से ids निकालना
        val mappers = res.selectFirst("script:containsData(sniff)")?.data()?.let {
            val sniffPattern = Regex("sniff\\(([^)]+)\\)")
            sniffPattern.find(it)?.groupValues?.getOrNull(1)
        } ?: return
        
        val ids = mappers.split(",").map { it.replace("\"", "").trim() }
        if (ids.size > 2) {
            val m3u8 = "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.m3u8"
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    m3u8,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                    this.headers = headers
                }
            )
        } else {
            // fallback: alternate script या ids
            // JS packed script ढूंढना
            val packedScript = res.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().orEmpty()
            if (packedScript.isNotEmpty()) {
                val unpacked = JsUnpacker(packedScript).unpack()
                val m3u8 = unpacked?.let {
                    Regex("sources:\\[\\{file:\"(.*?)\"").find(it)?.groupValues?.getOrNull(1)
                }
                if (!m3u8.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            m3u8.toString(),
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                            this.headers = headers
                        }
                    )
                    return
                }
            }
            
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    "",
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }
}
