package com.phisher98

import android.util.Log
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
    // mainUrl वह बेस URL होना चाहिए जो प्लेयर पेज होस्ट करता है।
    // प्रदान किया गया HTML "1movierulzhd.lol" से है, इसलिए इसे mainUrl के रूप में उपयोग करते हैं।
    override var mainUrl = "https://1movierulzhd.lol"
    override val requiresReferer = true // हाँ, वीडियो होस्ट Referer की जाँच कर सकता है

    override suspend fun getUrl(
        url: String, // यह उस पेज का URL है जिसमें प्लेयर एम्बेडेड है, जैसे https://1movierulzhd.lol/movies/jaat/
        referer: String?, // यह पिछला पेज हो सकता है, लेकिन iframe अनुरोध के लिए Referer 'url' होना चाहिए
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("FilemoonV2", "getUrl called for: $url")

        // 1. वीडियो प्लेयर वाले मुख्य पेज को फ़ेच करें
        val mainPageDocument = app.get(url, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36",
            "Referer" to mainUrl // मुख्य पेज अनुरोध के लिए साइट का बेस URL Referer के रूप में उपयोग करें
        )).document

        // 2. मुख्य पेज HTML में iframe का src ढूंढें
        // सेलेक्टर को और विशिष्ट बनाया गया है ताकि सही iframe मिल सके
        val iframeSrc = mainPageDocument.selectFirst("div#dooplay_player_response iframe")?.attr("src")

        if (iframeSrc.isNullOrEmpty()) {
            Log.e("FilemoonV2", "No iframe found on page: $url")
            // कोई iframe नहीं मिला, इस विधि से आगे नहीं बढ़ सकते
            return
        }

        Log.d("FilemoonV2", "Found iframe src: $iframeSrc")

        // 3. iframe सामग्री (प्लेयर पेज) को फ़ेच करें
        // iframe अनुरोध के लिए Referer के रूप में मुख्य पेज URL का उपयोग करें
        val playerPageDocument = app.get(iframeSrc, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.127 Safari/537.36",
            "Referer" to url, // प्लेयर पेज अनुरोध के लिए Referer मुख्य पेज URL है
            "Accept-Language" to "en-US,en;q=0.5",
            "sec-fetch-dest" to "iframe" // यह हेडर कभी-कभी आवश्यक होता है
        )).document

        // अब, playerPageDocument के भीतर वीडियो लिंक खोजें

        // प्रयास A: प्लेयर पेज HTML में सीधे <video><source> टैग ढूंढें
        // यह उस संरचना से मेल खाता है जो आपने पहले प्रदान की थी
        val sourceTagSrc = playerPageDocument.selectFirst("video source[src*=\".m3u8\"]")?.attr("src")

        if (sourceTagSrc != null) {
            Log.d("FilemoonV2", "Found source tag src in player page: $sourceTagSrc")
            // Relative URL को iframeSrc (प्लेयर पेज URL) के सापेक्ष हल करें
            val m3u8Link = if (sourceTagSrc.startsWith("/")) {
                // iframeSrc से बेस URL निकालें (यह सरल तरीका है, जटिल URLs के लिए परिशोधन की आवश्यकता हो सकती है)
                val baseUrl = iframeSrc.substringBeforeLast("/")
                baseUrl + sourceTagSrc
            } else {
                // यदि src पहले से ही पूर्ण URL है
                sourceTagSrc
            }

            Log.d("FilemoonV2", "Resolved M3U8 link (from source tag): $m3u8Link")

            // निकाले गए लिंक का कॉलबैक करें
            callback.invoke(
                ExtractorLink(
                    this.name, // एक्सट्रैक्टर का नाम
                    this.name, // स्रोत का नाम
                    m3u8Link, // निकाला गया M3u8 URL
                    iframeSrc, // अंतिम M3U8 लिंक के लिए Referer प्लेयर पेज URL है
                    Qualities.P1080.value, // सामान्य अभ्यास के आधार पर 1080p मान रहे हैं, INFER_QUALITY हो सकता है
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf("Referer" to iframeSrc) // अंतिम लिंक के लिए Referer हेडर स्पष्ट रूप से सेट करें
                )
            )
            return // सोर्स टैग के माध्यम से लिंक मिल गया
        }

        Log.d("FilemoonV2", "Source tag not found in player page. Trying script unpack...")

        // प्रयास B: यदि सोर्स टैग नहीं मिला तो स्क्रिप्ट अनपैकिंग पर वापस जाएं
        // यह उन मामलों को हैंडल करता है जहां लिंक एक स्क्रिप्ट में ऑब्सफ़सकेटेड है
        val scriptData = playerPageDocument.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()

        if (scriptData != null) {
            Log.d("FilemoonV2", "Found script data for unpacking in player page.")
            val unpacked = JsUnpacker(scriptData).unpack()
            val m3u8Link = unpacked?.let { unPacked ->
                // Unpacked स्क्रिप्ट के भीतर फ़ाइल URL खोजने के लिए Regex
                Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)
            }

            if (m3u8Link != null) {
                Log.d("FilemoonV2", "Unpacked and found M3U8 link: $m3u8Link")
                callback.invoke(
                    ExtractorLink(
                        this.name, // एक्सट्रैक्टर का नाम
                        this.name, // स्रोत का नाम
                        m3u8Link, // निकाला गया M3u8 URL
                        iframeSrc, // अंतिम M3U8 लिंक के लिए Referer प्लेयर पेज URL है
                        Qualities.P1080.value, // 1080p मान रहे हैं
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf("Referer" to iframeSrc) // अंतिम लिंक के लिए Referer हेडर स्पष्ट रूप से सेट करें
                    )
                )
                return // स्क्रिप्ट अनपैक के माध्यम से लिंक मिल गया
            } else {
                Log.e("FilemoonV2", "Script unpacked but M3U8 link not found within.")
            }
        } else {
            Log.e("FilemoonV2", "Script data for unpacking not found in player page.")
        }

        // यदि किसी भी विधि से लिंक नहीं मिलता है
        Log.e("FilemoonV2", "No video link found in player page: $iframeSrc")
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