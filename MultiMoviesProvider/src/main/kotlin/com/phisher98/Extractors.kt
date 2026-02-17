package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════════════
// यूटिलिटी फ़ंक्शन्स — सभी एक्सट्रैक्टर्स में उपयोग होते हैं
// ═══════════════════════════════════════════════════════════════════════════════════

/** URL से scheme://host निकालता है */
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
}

// सेशन-लेवल कैश — urls.json पूरे सेशन में केवल एक बार फ़ेच होती है
private var cachedUrlsJson: JSONObject? = null

/**
 * GitHub से नवीनतम डोमेन रियल-टाइम में फ़ेच करता है।
 * कैशिंग के कारण कोई देरी नहीं होती — पहली बार फ़ेच, उसके बाद कैश से।
 */
suspend fun getLatestUrl(url: String, source: String): String {
    if (cachedUrlsJson == null) {
        try {
            cachedUrlsJson = JSONObject(
                app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
            )
            Log.d("DomainResolver", "GitHub से डोमेन्स सफलतापूर्वक फ़ेच हुए")
        } catch (e: Exception) {
            Log.d("DomainResolver", "GitHub से फ़ेच विफल: ${e.message}, फ़ॉलबैक उपयोग")
            return getBaseUrl(url)
        }
    }
    val link = cachedUrlsJson?.optString(source)
    if (link.isNullOrEmpty()) {
        Log.d("DomainResolver", "स्रोत '$source' के लिए कोई डोमेन नहीं मिला")
        return getBaseUrl(url)
    }
    return link
}

/** इमेज एट्रिब्यूट निकालने का एक्सटेंशन — data-src, data-lazy-src, srcset, src */
fun Element.getImageAttr(): String? {
    return when {
        this.hasAttr("data-src") -> this.attr("abs:data-src")
        this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
        this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
        else -> this.attr("abs:src")
    }
}

// m3u8 URL ढूँढने के लिए प्रोफ़ेशनल Regex पैटर्न्स
private val M3U8_REGEX = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
private val MP4_REGEX = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""")
private val FILE_REGEX = Regex(""""?file"?\s*:\s*"(https?://[^"]+)"""")
private val SOURCE_REGEX = Regex(""""?sources?"?\s*:\s*\[\s*\{\s*"?(?:file|src)"?\s*:\s*"(https?://[^"]+)"""")

// ═══════════════════════════════════════════════════════════════════════════════════
// GDMirrorBot — मुख्य एंट्री पॉइंट (gdmirrorbot.nl → pro.iqsmartgames.com)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * GDMirrorBot एक्सट्रैक्टर:
 * 1. gdmirrorbot.nl/embed/{id} पर जाता है
 * 2. pro.iqsmartgames.com/svid/{token} पर रीडायरेक्ट होता है
 * 3. पेज में सर्वर लिस्ट (li.server-item[data-link]) पार्स करता है
 * 4. प्रत्येक सर्वर को उसके विशिष्ट एक्सट्रैक्टर को भेजता है
 */
class GDMirrorBot : ExtractorApi() {
    override val name = "GDMirrorBot"
    override val mainUrl = "https://gdmirrorbot.nl"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMirrorBot"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            // डायनेमिक डोमेन — gdmirror key से नवीनतम URL लाना
            val latestUrl = getLatestUrl(url, "gdmirror")
            Log.d(tag, "नवीनतम GDMirror डोमेन: $latestUrl")

            // रीडायरेक्ट फ़ॉलो करके अंतिम पेज प्राप्त करना
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document
            Log.d(tag, "रीडायरेक्ट के बाद URL: $finalUrl")

            // सर्वर लिस्ट से सभी data-link निकालना
            val serverItems = doc.select("li.server-item[data-link]")
            Log.d(tag, "${serverItems.size} सर्वर मिले")

            // डाउनलोड लिंक भी निकालना (ddn.iqsmartgames.com)
            val downloadLink = doc.selectFirst("a.dlvideoLinks[href]")?.attr("href")
            if (!downloadLink.isNullOrBlank()) {
                Log.d(tag, "डाउनलोड लिंक मिला: $downloadLink")
                callback.invoke(
                    newExtractorLink(
                        "$name [Download]",
                        "$name [Direct Download]",
                        downloadLink,
                        INFER_TYPE
                    ) {
                        this.referer = finalUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            // प्रत्येक सर्वर को उसके एक्सट्रैक्टर से प्रोसेस करना
            for (item in serverItems) {
                val serverLink = item.attr("data-link").trim()
                val serverName = item.selectFirst(".server-name")?.text()?.trim() ?: ""
                val serverMeta = item.selectFirst(".server-meta")?.text()?.trim() ?: ""

                if (serverLink.isBlank()) continue
                Log.d(tag, "सर्वर: $serverName ($serverMeta) → $serverLink")

                try {
                    when {
                        // SMWH — multimoviesshg.com (JWPlayer m3u8)
                        serverLink.contains("multimoviesshg", true) ->
                            MultiMoviesSHG().getUrl(serverLink, finalUrl, subtitleCallback, callback)

                        // RPMSHRE — rpmhub.site
                        serverLink.contains("rpmhub", true) ->
                            RpmShare().getUrl(serverLink, finalUrl, subtitleCallback, callback)

                        // FLLS — smoothpre.com (EarnVids)
                        serverLink.contains("smoothpre", true) ->
                            SmoothPre().getUrl(serverLink, finalUrl, subtitleCallback, callback)

                        // अन्य सर्वर — सामान्य m3u8/mp4 एक्सट्रैक्शन
                        else -> {
                            Log.d(tag, "अज्ञात सर्वर, जेनेरिक एक्सट्रैक्शन: $serverLink")
                            extractGenericM3u8(serverLink, finalUrl, "$name [$serverName]", callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "सर्वर '$serverName' प्रोसेसिंग विफल: ${e.message}")
                }
            }

            // फ़ॉलबैक: अगर कोई server-item नहीं मिला, तो iframe से सीधे एक्सट्रैक्ट करना
            if (serverItems.isEmpty()) {
                Log.d(tag, "कोई सर्वर आइटम नहीं, iframe फ़ॉलबैक प्रयास")
                val iframeSrc = doc.selectFirst("iframe#vidFrame[src], iframe#vidFreme[src]")
                    ?.attr("src")?.trim()
                if (!iframeSrc.isNullOrBlank()) {
                    Log.d(tag, "iframe स्रोत मिला: $iframeSrc")
                    when {
                        iframeSrc.contains("multimoviesshg", true) ->
                            MultiMoviesSHG().getUrl(iframeSrc, finalUrl, subtitleCallback, callback)
                        else ->
                            extractGenericM3u8(iframeSrc, finalUrl, name, callback)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "GDMirrorBot एक्सट्रैक्शन पूर्णतः विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// MultiMoviesSHG — JWPlayer m3u8 (eval-packed JS)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * MultiMoviesSHG एक्सट्रैक्टर:
 * - multimoviesshg.com/e/{id} पेज को फ़ेच करता है
 * - eval(function(p,a,c,k,e,d)) पैक्ड JavaScript को JsUnpacker से अनपैक करता है
 * - अनपैक किए गए JS से m3u8 URL निकालता है
 * - फ़ॉलबैक: रॉ HTML से भी m3u8 ढूँढता है
 */
class MultiMoviesSHG : ExtractorApi() {
    override val name = "MultiMoviesSHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "MultiMoviesSHG"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            // डायनेमिक डोमेन अपडेट
            val latestUrl = getLatestUrl(url, "multimoviesshg")
            val oldBase = getBaseUrl(url)
            val finalUrl = if (latestUrl.isNotBlank() && latestUrl != oldBase) {
                url.replace(oldBase, latestUrl)
            } else url
            Log.d(tag, "अंतिम URL: $finalUrl")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(finalUrl, headers = headers)
            val html = response.text

            // === विधि 1: eval-packed JS को JsUnpacker से अनपैक करना ===
            val packedPattern = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('([^']*)'""", RegexOption.DOT_MATCHES_ALL)
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                Log.d(tag, "विधि 1: eval-packed JS मिला, अनपैक कर रहे हैं...")
                try {
                    // पूरा eval ब्लॉक निकालना
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalMatch = evalRegex.find(html)
                    val evalBlock = evalMatch?.groupValues?.get(1)

                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        Log.d(tag, "अनपैक सफल, लंबाई: ${unpacked?.length ?: 0}")

                        if (!unpacked.isNullOrBlank()) {
                            // अनपैक किए गए JS से m3u8 URL निकालना
                            val m3u8Url = extractM3u8FromText(unpacked)
                            if (m3u8Url != null) {
                                Log.d(tag, "✅ m3u8 मिला (packed JS): $m3u8Url")
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name [HLS]",
                                        m3u8Url,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = finalUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }

                            // m3u8 नहीं मिला तो mp4 ढूँढना
                            val mp4Url = MP4_REGEX.find(unpacked)?.groupValues?.get(1)
                            if (mp4Url != null) {
                                Log.d(tag, "✅ MP4 मिला (packed JS): $mp4Url")
                                callback.invoke(
                                    newExtractorLink(
                                        name,
                                        "$name [MP4]",
                                        mp4Url,
                                        INFER_TYPE
                                    ) {
                                        this.referer = finalUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            // === विधि 2: रॉ HTML/JS से सीधे m3u8 ढूँढना ===
            Log.d(tag, "विधि 2: रॉ HTML से m3u8 खोज रहे हैं...")
            val rawM3u8 = extractM3u8FromText(html)
            if (rawM3u8 != null) {
                Log.d(tag, "✅ m3u8 मिला (raw HTML): $rawM3u8")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [HLS]",
                        rawM3u8,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // === विधि 3: इनर iframe चेन फ़ॉलो करना ===
            Log.d(tag, "विधि 3: इनर iframe चेन खोज रहे हैं...")
            val doc = response.document
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank() && innerIframe != url) {
                Log.d(tag, "इनर iframe मिला: $innerIframe")
                val iframeHtml = app.get(innerIframe, headers = headers).text
                val iframeM3u8 = extractM3u8FromText(iframeHtml)
                if (iframeM3u8 != null) {
                    Log.d(tag, "✅ m3u8 मिला (inner iframe): $iframeM3u8")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [HLS]",
                            iframeM3u8,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = innerIframe
                            this.quality = Qualities.P720.value
                        }
                    )
                    return
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "MultiMoviesSHG एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// RpmShare — rpmhub.site एम्बेड प्लेयर
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * RpmShare एक्सट्रैक्टर:
 * - rpmhub.site/#{id} से m3u8 URL निकालता है
 * - पैक्ड JS और रॉ HTML दोनों विधियों का उपयोग करता है
 */
class RpmShare : ExtractorApi() {
    override val name = "RpmShare"
    override val mainUrl = "https://rpmhub.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "RpmShare"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            // विधि 1: eval-packed JS से m3u8 निकालना
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                Log.d(tag, "eval-packed JS मिला")
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            val m3u8Url = extractM3u8FromText(unpacked)
                            if (m3u8Url != null) {
                                Log.d(tag, "✅ m3u8 मिला (packed): $m3u8Url")
                                callback.invoke(
                                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                                        this.referer = finalPageUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            // विधि 2: रॉ HTML/JS से m3u8 ढूँढना
            val m3u8Url = extractM3u8FromText(html)
            if (m3u8Url != null) {
                Log.d(tag, "✅ m3u8 मिला (raw): $m3u8Url")
                callback.invoke(
                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // विधि 3: iframe chain
            val doc = response.document
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank()) {
                Log.d(tag, "iframe फ़ॉलबैक: $innerIframe")
                extractGenericM3u8(innerIframe, finalPageUrl, name, callback)
            }

        } catch (e: Exception) {
            Log.e(tag, "RpmShare एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// SmoothPre — smoothpre.com (EarnVids प्लेयर)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * SmoothPre/EarnVids एक्सट्रैक्टर:
 * - smoothpre.com/v/{id} से m3u8 URL निकालता है
 * - पैक्ड JS, source टैग, और API-स्टाइल जवाबों का समर्थन करता है
 */
class SmoothPre : ExtractorApi() {
    override val name = "SmoothPre"
    override val mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "SmoothPre"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            // विधि 1: eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                Log.d(tag, "eval-packed JS मिला")
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            val m3u8Url = extractM3u8FromText(unpacked)
                            if (m3u8Url != null) {
                                Log.d(tag, "✅ m3u8 मिला (packed): $m3u8Url")
                                callback.invoke(
                                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                                        this.referer = finalPageUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            // विधि 2: HTML source टैग और raw JS में m3u8
            val doc = response.document

            // <source> टैग से सीधे m3u8 निकालना
            val sourceSrc = doc.select("source[src*=m3u8]").attr("src").ifBlank {
                doc.select("video source[type*=m3u8]").attr("src")
            }
            if (sourceSrc.isNotBlank()) {
                Log.d(tag, "✅ m3u8 मिला (source tag): $sourceSrc")
                callback.invoke(
                    newExtractorLink(name, "$name [HLS]", sourceSrc, ExtractorLinkType.M3U8) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // रॉ HTML/JS से m3u8
            val m3u8Url = extractM3u8FromText(html)
            if (m3u8Url != null) {
                Log.d(tag, "✅ m3u8 मिला (raw): $m3u8Url")
                callback.invoke(
                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // विधि 3: iframe chain
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank()) {
                Log.d(tag, "iframe फ़ॉलबैक: $innerIframe")
                extractGenericM3u8(innerIframe, finalPageUrl, name, callback)
            }

        } catch (e: Exception) {
            Log.e(tag, "SmoothPre एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// TechInMind — stream.techinmind.space (TV शो API)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * TechInMind एक्सट्रैक्टर:
 * - stream.techinmind.space/embed/tv/{tmdb}/{season}/{episode}?key=...
 * - VidSrc-स्टाइल API जो m3u8 स्ट्रीम प्रदान करती है
 * - TV शो एपिसोड के लिए विशिष्ट
 */
class TechInMind : ExtractorApi() {
    override val name = "TechInMind"
    override val mainUrl = "https://stream.techinmind.space"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "TechInMind"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            // डायनेमिक डोमेन अपडेट
            val latestUrl = getLatestUrl(url, "techinmind")
            val oldBase = getBaseUrl(url)
            val finalUrl = if (latestUrl.isNotBlank() && latestUrl != oldBase) {
                url.replace(oldBase, latestUrl)
            } else url
            Log.d(tag, "अंतिम URL: $finalUrl")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(finalUrl, headers = headers, allowRedirects = true)
            val html = response.text
            val currentUrl = response.url

            // विधि 1: eval-packed JS से m3u8
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                Log.d(tag, "eval-packed JS मिला")
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            val m3u8Url = extractM3u8FromText(unpacked)
                            if (m3u8Url != null) {
                                Log.d(tag, "✅ m3u8 मिला (packed): $m3u8Url")
                                callback.invoke(
                                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                                        this.referer = currentUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e("GDMirror", "API call failed: ${e.message}")
            }

            // विधि 2: JSON API रिस्पॉन्स — कुछ VidSrc एम्बेड JSON लौटाते हैं
            try {
                // URL से TMDB ID, सीज़न, एपिसोड निकालना
                val apiPattern = Regex("""/embed/(?:tv|movie)/(\d+)(?:/(\d+)/(\d+))?""")
                val apiMatch = apiPattern.find(finalUrl)
                if (apiMatch != null) {
                    val tmdbId = apiMatch.groupValues[1]
                    val season = apiMatch.groupValues.getOrNull(2) ?: ""
                    val episode = apiMatch.groupValues.getOrNull(3) ?: ""

                    Log.d(tag, "API पैटर्न: TMDB=$tmdbId, S=$season, E=$episode")

                    // /api/sources एंडपॉइंट ट्राई करना
                    val apiBase = getBaseUrl(currentUrl)
                    val apiEndpoints = listOf(
                        "$apiBase/api/source/$tmdbId${if (season.isNotEmpty()) "/$season/$episode" else ""}",
                        "$apiBase/ajax/embed/tv/$tmdbId${if (season.isNotEmpty()) "/$season/$episode" else ""}"
                    )

                    for (endpoint in apiEndpoints) {
                        try {
                            val apiResp = app.get(endpoint, headers = headers)
                            val apiText = apiResp.text
                            val apiM3u8 = extractM3u8FromText(apiText)
                            if (apiM3u8 != null) {
                                Log.d(tag, "✅ m3u8 मिला (API): $apiM3u8")
                                callback.invoke(
                                    newExtractorLink(name, "$name [API]", apiM3u8, ExtractorLinkType.M3U8) {
                                        this.referer = currentUrl
                                        this.quality = Qualities.P1080.value
                                    }
                                )
                                return
                            }
                        } catch (_: Exception) {
                            // अगला endpoint ट्राई करना
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "API एक्सट्रैक्शन विफल: ${e.message}")
            }

            // विधि 3: रॉ HTML/JS से m3u8
            val m3u8Url = extractM3u8FromText(html)
            if (m3u8Url != null) {
                Log.d(tag, "✅ m3u8 मिला (raw): $m3u8Url")
                callback.invoke(
                    newExtractorLink(name, "$name [HLS]", m3u8Url, ExtractorLinkType.M3U8) {
                        this.referer = currentUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // विधि 4: इनर iframes से m3u8
            val doc = response.document
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue

                Log.d(tag, "इनर iframe: $iframeSrc")
                try {
                    val iframeResp = app.get(iframeSrc, headers = headers)
                    val iframeHtml = iframeResp.text

                    // iframe में packed JS चेक
                    if (iframeHtml.contains("eval(function(p,a,c,k,e,d)")) {
                        val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                        val evalBlock = evalRegex.find(iframeHtml)?.groupValues?.get(1)
                        if (evalBlock != null) {
                            val unpacked = JsUnpacker(evalBlock).unpack()
                            val iM3u8 = extractM3u8FromText(unpacked ?: "")
                            if (iM3u8 != null) {
                                Log.d(tag, "✅ m3u8 मिला (iframe packed): $iM3u8")
                                callback.invoke(
                                    newExtractorLink(name, "$name [HLS]", iM3u8, ExtractorLinkType.M3U8) {
                                        this.referer = iframeSrc
                                        this.quality = Qualities.P720.value
                                    }
                                )
                                return
                            }
                        }
                    }

                    val iframeM3u8 = extractM3u8FromText(iframeHtml)
                    if (iframeM3u8 != null) {
                        Log.d(tag, "✅ m3u8 मिला (iframe raw): $iframeM3u8")
                        callback.invoke(
                            newExtractorLink(name, "$name [HLS]", iframeM3u8, ExtractorLinkType.M3U8) {
                                this.referer = iframeSrc
                                this.quality = Qualities.P720.value
                            }
                        )
                        return
                    }
                } catch (e: Exception) {
                    Log.e(tag, "iframe प्रोसेसिंग विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "TechInMind एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// साझा हेल्पर फ़ंक्शन्स — सभी एक्सट्रैक्टर्स द्वारा उपयोग किए जाते हैं
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * टेक्स्ट से m3u8 URL निकालता है — कई पैटर्न ट्राई करता है:
 * 1. "file": "url" (JWPlayer स्टाइल)
 * 2. "sources": [{"file": "url"}] (VidStack स्टाइल)
 * 3. सीधा m3u8 URL regex
 */
private fun extractM3u8FromText(text: String): String? {
    // प्राथमिकता 1: JWPlayer "file" पैटर्न
    FILE_REGEX.find(text)?.groupValues?.get(1)?.let { fileUrl ->
        if (fileUrl.contains(".m3u8")) return fileUrl
    }

    // प्राथमिकता 2: "sources" ऐरे पैटर्न
    SOURCE_REGEX.find(text)?.groupValues?.get(1)?.let { sourceUrl ->
        if (sourceUrl.contains(".m3u8")) return sourceUrl
    }

    // प्राथमिकता 3: सीधा m3u8 URL
    M3U8_REGEX.find(text)?.groupValues?.get(1)?.let { m3u8Url ->
        // एड/ट्रैकर URLs फ़िल्टर करना
        if (!m3u8Url.contains("advertisement") && !m3u8Url.contains("tracker")) {
            return m3u8Url
        }
    }

    // प्राथमिकता 4: JWPlayer "file" पैटर्न बिना m3u8 फ़िल्टर (mp4 भी स्वीकार)
    FILE_REGEX.find(text)?.groupValues?.get(1)?.let { fileUrl ->
        if (fileUrl.contains(".mp4") || fileUrl.contains("http")) return fileUrl
    }

    return null
}

/**
 * जेनेरिक m3u8 एक्सट्रैक्शन — किसी भी URL से m3u8 निकालने का प्रयास करता है।
 * पेज फ़ेच → packed JS → raw HTML → iframe chain
 */
private suspend fun extractGenericM3u8(
    url: String,
    referer: String,
    sourceName: String,
    callback: (ExtractorLink) -> Unit
) {
    val tag = "GenericM3u8"
    try {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Referer" to referer
        )

        val response = app.get(url, headers = headers, allowRedirects = true)
        val html = response.text
        val currentUrl = response.url

        // packed JS अनपैक
        if (html.contains("eval(function(p,a,c,k,e,d)")) {
            try {
                val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                if (evalBlock != null) {
                    val unpacked = JsUnpacker(evalBlock).unpack()
                    if (!unpacked.isNullOrBlank()) {
                        val m3u8 = extractM3u8FromText(unpacked)
                        if (m3u8 != null) {
                            callback.invoke(
                                newExtractorLink(sourceName, "$sourceName [HLS]", m3u8, ExtractorLinkType.M3U8) {
                                    this.referer = currentUrl
                                    this.quality = Qualities.P720.value
                                }
                            )
                            return
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // रॉ HTML से m3u8
        val m3u8 = extractM3u8FromText(html)
        if (m3u8 != null) {
            callback.invoke(
                newExtractorLink(sourceName, "$sourceName [HLS]", m3u8, ExtractorLinkType.M3U8) {
                    this.referer = currentUrl
                    this.quality = Qualities.P720.value
                }
            )
        }
    } catch (e: Exception) {
        Log.e(tag, "$sourceName के लिए जेनेरिक एक्सट्रैक्शन विफल: ${e.message}")
    }
}
