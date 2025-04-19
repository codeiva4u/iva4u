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

// FilemoonV2 क्लास को अपडेट किया गया
class FilemoonV2 : ExtractorApi() {
    override var name = "Filemoon"
    // mainUrl वह बेस URL होना चाहिए जो प्लेयर पेज होस्ट करता है।
    // HTML शीर्षक "movierulzHD" के आधार पर, साइट movierulz से संबंधित है।
    // प्रदान किया गया HTML "movierulz2025.bar" से है, इसलिए इस mainUrl को बनाए रखते हैं।
    override var mainUrl = "https://movierulz2025.bar"
    override val requiresReferer = true // हाँ, वीडियो होस्ट Referer की जाँच कर सकता है

    override suspend fun getUrl(
        url: String, // यह उस पेज का URL है जिसमें प्लेयर है, जैसे https://movierulz2025.bar/watch/...
        referer: String?, // यह पिछला पेज हो सकता है, लेकिन प्लेयर Referer 'url' होना चाहिए
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // वीडियो प्लेयर वाले पेज को फ़ेच करें
        val document = app.get(url).document

        // प्रयास 1: HTML में सीधे m3u8 लिंक वाले <source> टैग को खोजें
        val sourceTagSrc = document.selectFirst("video source[src*=\".m3u8\"]")?.attr("src")

        if (sourceTagSrc != null) {
            // M3U8 फ़ाइल के लिए पूर्ण URL बनाएं
            val m3u8Link = if (sourceTagSrc.startsWith("/")) {
                // यदि src रिलेटिव है, तो mainUrl को पहले जोड़ें
                mainUrl + sourceTagSrc
            } else {
                // यदि src पहले से ही पूर्ण है
                sourceTagSrc
            }

            // निकाले गए लिंक का कॉलबैक करें
            callback.invoke(
                ExtractorLink(
                    this.name, // एक्सट्रैक्टर का नाम
                    this.name, // स्रोत का नाम
                    m3u8Link, // निकाला गया M3u8 URL
                    url, // Referer वह पेज होना चाहिए जहां प्लेयर एम्बेडेड है
                    Qualities.P1080.value, // सामान्य अभ्यास के आधार पर 1080p मान रहे हैं, INFER_QUALITY हो सकता है
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to url) // Referer हेडर स्पष्ट रूप से सेट करें
                )
            )
            // लिंक मिल गया, अब यहीं रुक सकते हैं
            return
        }

        // प्रयास 2: यदि source टैग नहीं मिला तो स्क्रिप्ट अनपैकिंग पर वापस जाएं
        // यह उन मामलों को हैंडल करता है जहां लिंक एक स्क्रिप्ट में ऑब्सफ़सकेटेड है
        val scriptData = document.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()

        if (scriptData != null) {
            val unpacked = JsUnpacker(scriptData).unpack()
            val m3u8Link = unpacked?.let { unPacked ->
                // अनपैक्ड स्क्रिप्ट के भीतर फ़ाइल URL खोजने के लिए Regex
                Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
            }

            if (m3u8Link != null) {
                callback.invoke(
                    ExtractorLink(
                        this.name, // एक्सट्रैक्टर का नाम
                        this.name, // स्रोत का नाम
                        m3u8Link, // निकाला गया M3u8 URL
                        url, // Referer वह पेज होना चाहिए जहां प्लेयर एम्बेडेड है
                        Qualities.P1080.value, // 1080p मान रहे हैं
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf("Referer" to url) // Referer हेडर स्पष्ट रूप से सेट करें
                    )
                )
                // लिंक मिल गया, अब यहीं रुक सकते हैं
                return
            }
        }

        // यदि किसी भी विधि से लिंक नहीं मिलता है, तो फ़ंक्शन कॉलबैक को कॉल किए बिना समाप्त हो जाता है,
        // जो इंगित करता है कि इस एक्सट्रैक्टर द्वारा कोई लिंक नहीं मिला।
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
class Mocdn:Akamaicdn(){
   override val name = "Mocdn"
   override val mainUrl = "https://mocdn.art"
}