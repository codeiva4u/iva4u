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
    override var mainUrl = "https://movierulz2025.bar"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // मुख्य iframe source निकालना
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val iframeSrc = doc.selectFirst("iframe")?.attr("src") ?: ""
        if (iframeSrc.isEmpty()) {
            // fallback: alternate iframe या script से ट्राय करें
            val altIframe = doc.select("iframe").getOrNull(1)?.attr("src") ?: ""
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
        val resDoc = app.get(iframeUrl, headers = headers).document
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
                        iframeUrl,
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
                            iframeUrl,
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
                iframeUrl,
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
    override val mainUrl = "https://akamaicdn.life"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("user-agent" to USER_AGENT)
        val res = app.get(url, referer = referer, headers = headers).document
        // JS sniff function से ids निकालना
        val mappers = res.selectFirst("script:containsData(sniff()")?.data()?.substringAfter("sniff(")?.substringBefore(");") ?: return
        val ids = mappers.split(",").map { it.replace("\"", "") }
        if (ids.size > 2) {
            val m3u8 = "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1"
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
