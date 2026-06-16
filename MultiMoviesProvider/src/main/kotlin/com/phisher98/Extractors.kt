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
import org.jsoup.nodes.Element
import java.net.URI

// ═══════════════════════════════════════════════════════════════════════════════════
// यूटिलिटी फ़ंक्शन्स
// ═══════════════════════════════════════════════════════════════════════════════════

/** URL से scheme://host निकालता है */
fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) { "" }
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

// m3u8/mp4 URL ढूँढने के लिए Regex पैटर्न्स
private val M3U8_REGEX = Regex("""(https?://[^\s"'\\]+\.m3u8[^\s"'\\]*)""")
private val MP4_REGEX = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""")
private val FILE_REGEX = Regex(""""?file"?\s*:\s*"(https?://[^"]+)"""")
private val SOURCE_REGEX = Regex(""""?sources?"?\s*:\s*\[\s*\{\s*"?(?:file|src)"?\s*:\s*"(https?://[^"]+)"""")

/**
 * टेक्स्ट से m3u8/mp4 URL निकालता है — कई पैटर्न ट्राई करता है:
 * 1. "file": "url" (JWPlayer स्टाइल)
 * 2. "sources": [{"file": "url"}] (VidStack स्टाइल)
 * 3. सीधा m3u8 URL regex
 * 4. mp4 fallback
 */
private fun extractM3u8FromText(text: String): String? {
    // प्राथमिकता 1: JWPlayer "file" पैटर्न — m3u8
    FILE_REGEX.find(text)?.groupValues?.get(1)?.let { fileUrl ->
        if (fileUrl.contains(".m3u8")) return fileUrl
    }

    // प्राथमिकता 2: "sources" ऐरे पैटर्न
    SOURCE_REGEX.find(text)?.groupValues?.get(1)?.let { sourceUrl ->
        if (sourceUrl.contains(".m3u8")) return sourceUrl
    }

    // प्राथमिकता 3: सीधा m3u8 URL
    M3U8_REGEX.find(text)?.groupValues?.get(1)?.let { m3u8Url ->
        if (!m3u8Url.contains("advertisement") && !m3u8Url.contains("tracker")) {
            return m3u8Url
        }
    }

    // प्राथमिकता 4: JWPlayer "file" पैटर्न — mp4 या कोई भी
    FILE_REGEX.find(text)?.groupValues?.get(1)?.let { fileUrl ->
        if (fileUrl.contains(".mp4") || fileUrl.contains("http")) return fileUrl
    }

    return null
}

/**
 * eval-packed JS को अनपैक करके m3u8/mp4 URL निकालता है
 * @return Pair(url, isM3u8)
 */
private fun extractFromPackedJs(html: String): Pair<String, Boolean>? {
    if (!html.contains("eval(function(p,a,c,k,e,d)")) return null

    try {
        val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
        val evalBlock = evalRegex.find(html)?.groupValues?.get(1) ?: return null
        val unpacked = JsUnpacker(evalBlock).unpack() ?: return null

        if (unpacked.isBlank()) return null

        // m3u8 ढूँढो
        extractM3u8FromText(unpacked)?.let { url ->
            return Pair(url, url.contains(".m3u8"))
        }

        // mp4 ढूँढो
        MP4_REGEX.find(unpacked)?.groupValues?.get(1)?.let { url ->
            return Pair(url, false)
        }
    } catch (e: Exception) {
        Log.e("PackedJs", "अनपैक विफल: ${e.message}")
    }
    return null
}

// ═══════════════════════════════════════════════════════════════════════════════════
// GDMIRROR — मुख्य एंट्री पॉइंट
// streams.iqsmartgames.com → pro.iqsmartgames.com → inner servers
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * GDMIRROR एक्सट्रैक्टर:
 * 1. streams.iqsmartgames.com/embed/{type}/{imdb}?key=... पर जाता है
 * 2. iframe से pro.iqsmartgames.com/evid/{id} निकालता है
 * 3. pro.iqsmartgames.com/svid/{token} पर रीडायरेक्ट होता है
 * 4. li.server-item[data-link] से सर्वर लिस्ट पार्स करता है
 * 5. प्रत्येक सर्वर को उसके एक्सट्रैक्टर (StreamHG, FileMoon, EarnVids) को भेजता है
 * 6. डाउनलोड लिंक भी निकालता है (a.dlvideoLinks)
 */
class GDMIRROR : ExtractorApi() {
    override val name = "GDMIRROR"
    override val mainUrl = "https://streams.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMIRROR"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            // ═══ Step 1: streams.iqsmartgames.com embed page फ़ेच करना ═══
            val embedResponse = app.get(url, headers = headers, allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
            val embedDoc = embedResponse.document
            val embedPageUrl = embedResponse.url
            Log.d(tag, "Embed page loaded: $embedPageUrl")

            // ═══ Check if it redirected to a known extractor directly ═══
            if (embedPageUrl.contains("multimoviesshg", true)) {
                Log.d(tag, "Redirected directly to StreamHG: $embedPageUrl")
                StreamHG().getUrl(embedPageUrl, referer, subtitleCallback, callback)
                return
            }
            if (embedPageUrl.contains("filemoon", true) || embedPageUrl.contains("bysetayico", true) || embedPageUrl.contains("kerapoxy", true)) {
                Log.d(tag, "Redirected directly to FileMoon: $embedPageUrl")
                FileMoon().getUrl(embedPageUrl, referer, subtitleCallback, callback)
                return
            }
            if (embedPageUrl.contains("smoothpre", true) || embedPageUrl.contains("earnvids", true)) {
                Log.d(tag, "Redirected directly to EarnVids: $embedPageUrl")
                EarnVids().getUrl(embedPageUrl, referer, subtitleCallback, callback)
                return
            }

            // iframe src निकालना (pro.iqsmartgames.com/evid/{id})
            val iframeSrc = embedDoc.selectFirst("iframe[src]")?.attr("src")?.trim()

            if (iframeSrc.isNullOrBlank()) {
                // अगर iframe नहीं मिला, तो शायद यह पहले से pro.iqsmartgames.com page है
                Log.d(tag, "कोई iframe नहीं, सीधे सर्वर आइटम्स ढूँढ रहे हैं")
                processServerPage(embedDoc, embedPageUrl, subtitleCallback, callback)
                return
            }

            Log.d(tag, "iframe src: $iframeSrc")

            // ═══ Step 2: pro.iqsmartgames.com पेज फ़ेच करना (रीडायरेक्ट फ़ॉलो) ═══
            val proResponse = app.get(iframeSrc, headers = headers, allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
            val proDoc = proResponse.document
            val proPageUrl = proResponse.url
            Log.d(tag, "Pro page loaded: $proPageUrl")

            // ═══ Check if it redirected to a known extractor directly ═══
            if (proPageUrl.contains("multimoviesshg", true)) {
                Log.d(tag, "Redirected directly to StreamHG: $proPageUrl")
                StreamHG().getUrl(proPageUrl, referer, subtitleCallback, callback)
                return
            }
            if (proPageUrl.contains("filemoon", true) || proPageUrl.contains("bysetayico", true) || proPageUrl.contains("kerapoxy", true)) {
                Log.d(tag, "Redirected directly to FileMoon: $proPageUrl")
                FileMoon().getUrl(proPageUrl, referer, subtitleCallback, callback)
                return
            }
            if (proPageUrl.contains("smoothpre", true) || proPageUrl.contains("earnvids", true)) {
                Log.d(tag, "Redirected directly to EarnVids: $proPageUrl")
                EarnVids().getUrl(proPageUrl, referer, subtitleCallback, callback)
                return
            }

            processServerPage(proDoc, proPageUrl, subtitleCallback, callback)

        } catch (e: Exception) {
            Log.e(tag, "GDMIRROR एक्सट्रैक्शन विफल: ${e.message}")
        }
    }

    private suspend fun processServerPage(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "GDMIRROR"

        // ═══ सर्वर लिस्ट पार्स करना ═══
        val serverItems = doc.select("li.server-item[data-link]")
        Log.d(tag, "${serverItems.size} सर्वर मिले")

        // ═══ डाउनलोड लिंक ═══
        val downloadLink = doc.selectFirst("a.dlvideoLinks[href]")?.attr("href")
        if (!downloadLink.isNullOrBlank()) {
            Log.d(tag, "डाउनलोड लिंक: $downloadLink")
            callback.invoke(
                newExtractorLink(
                    "$name [Download]",
                    "$name [Direct Download]",
                    downloadLink,
                    INFER_TYPE
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        // ═══ प्रत्येक सर्वर को डिस्पैच करना ═══
        for (item in serverItems) {
            val serverLink = item.attr("data-link").trim()
            val serverName = item.selectFirst(".server-name")?.text()?.trim() ?: ""
            val serverMeta = item.selectFirst(".server-meta")?.text()?.trim() ?: ""

            if (serverLink.isBlank()) continue
            Log.d(tag, "सर्वर: $serverName ($serverMeta) → $serverLink")

            try {
                when {
                    // SMWH → StreamHG (multimoviesshg.com)
                    serverLink.contains("multimoviesshg", true) ->
                        StreamHG().getUrl(serverLink, pageUrl, subtitleCallback, callback)

                    // FLMN → FileMoon (bysetayico.com, filemoon.sx आदि)
                    serverLink.contains("filemoon", true) ||
                    serverLink.contains("bysetayico", true) ||
                    serverLink.contains("kerapoxy", true) ->
                        FileMoon().getUrl(serverLink, pageUrl, subtitleCallback, callback)

                    // FLLS → EarnVids (smoothpre.com)
                    serverLink.contains("smoothpre", true) ||
                    serverLink.contains("earnvids", true) ->
                        EarnVids().getUrl(serverLink, pageUrl, subtitleCallback, callback)

                    // technocosmos HLS player — URL में m3u8 info hash होता है
                    serverLink.contains("technocosmos", true) ||
                    serverLink.contains("hlsplayer", true) -> {
                        Log.d(tag, "Technocosmos HLS player: $serverLink")
                        // URL pattern: plyr.technocosmos.surf/hlsplayer?url=https://domain/#hash
                        // iframe src को follow करके inner page से m3u8 निकालना
                        try {
                            val hlsResp = app.get(serverLink, headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ), allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
                            val hlsDoc = hlsResp.document
                            val hlsHtml = hlsResp.text

                            // iframe src check (inner player like server1.uns.bio)
                            val innerIframe = hlsDoc.selectFirst("iframe[src]")?.attr("src")?.trim()
                            if (!innerIframe.isNullOrBlank()) {
                                Log.d(tag, "Technocosmos inner iframe: $innerIframe")
                                val innerResp = app.get(innerIframe, headers = mapOf(
                                    "Referer" to serverLink,
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                                ), allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
                                val innerHtml = innerResp.text

                                // packed JS or raw m3u8
                                val packed = extractFromPackedJs(innerHtml)
                                if (packed != null) {
                                    Log.d(tag, "✅ m3u8 मिला (technocosmos packed): ${packed.first}")
                                    callback.invoke(
                                        newExtractorLink(
                                            name, "$name [$serverName]", packed.first,
                                            if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                                        ) {
                                            this.referer = innerIframe
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                } else {
                                    extractM3u8FromText(innerHtml)?.let { m3u8 ->
                                        Log.d(tag, "✅ m3u8 मिला (technocosmos raw): $m3u8")
                                        callback.invoke(
                                            newExtractorLink(
                                                name, "$name [$serverName]", m3u8,
                                                if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                                            ) {
                                                this.referer = innerIframe
                                                this.quality = Qualities.P720.value
                                            }
                                        )
                                    }
                                }
                            }

                            // raw HTML से भी try
                            extractM3u8FromText(hlsHtml)?.let { m3u8 ->
                                Log.d(tag, "✅ m3u8 मिला (technocosmos direct): $m3u8")
                                callback.invoke(
                                    newExtractorLink(
                                        name, "$name [$serverName]", m3u8,
                                        if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                                    ) {
                                        this.referer = serverLink
                                        this.quality = Qualities.P720.value
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Technocosmos विफल: ${e.message}")
                        }
                    }

                    // अन्य अज्ञात सर्वर — जेनेरिक m3u8 extraction
                    else -> {
                        Log.d(tag, "अज्ञात सर्वर, जेनेरिक एक्सट्रैक्शन: $serverLink")
                        try {
                            val resp = app.get(serverLink, headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ), allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
                            val html = resp.text
                            val currentUrl = resp.url

                            val packed = extractFromPackedJs(html)
                            if (packed != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        name, "$name [$serverName]", packed.first,
                                        if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                                    ) {
                                        this.referer = currentUrl
                                        this.quality = Qualities.P720.value
                                    }
                                )
                            } else {
                                extractM3u8FromText(html)?.let { m3u8 ->
                                    callback.invoke(
                                        newExtractorLink(
                                            name, "$name [$serverName]", m3u8,
                                            if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                                        ) {
                                            this.referer = currentUrl
                                            this.quality = Qualities.P720.value
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "जेनेरिक एक्सट्रैक्शन विफल ($serverName): ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "सर्वर '$serverName' प्रोसेसिंग विफल: ${e.message}")
            }
        }

        // ═══ फ़ॉलबैक: अगर server-item नहीं मिले तो iframe chain ═══
        if (serverItems.isEmpty()) {
            Log.d(tag, "कोई सर्वर आइटम नहीं, iframe फ़ॉलबैक")
            val fallbackIframe = doc.selectFirst("iframe#vidFrame[src], iframe#vidFreme[src], iframe[src]")
                ?.attr("src")?.trim()
            if (!fallbackIframe.isNullOrBlank()) {
                Log.d(tag, "Fallback iframe: $fallbackIframe")
                when {
                    fallbackIframe.contains("multimoviesshg", true) ->
                        StreamHG().getUrl(fallbackIframe, pageUrl, subtitleCallback, callback)
                    fallbackIframe.contains("filemoon", true) || fallbackIframe.contains("bysetayico", true) ->
                        FileMoon().getUrl(fallbackIframe, pageUrl, subtitleCallback, callback)
                    fallbackIframe.contains("smoothpre", true) ->
                        EarnVids().getUrl(fallbackIframe, pageUrl, subtitleCallback, callback)
                    else -> {
                        // Generic extraction on the iframe
                        try {
                            val resp = app.get(fallbackIframe, headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            ), allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
                            val html = resp.text

                            val packed = extractFromPackedJs(html)
                            if (packed != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        name, "$name [Fallback]", packed.first,
                                        if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                                    ) {
                                        this.referer = fallbackIframe
                                        this.quality = Qualities.P720.value
                                    }
                                )
                            } else {
                                extractM3u8FromText(html)?.let { m3u8 ->
                                    callback.invoke(
                                        newExtractorLink(
                                            name, "$name [Fallback]", m3u8,
                                            if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                                        ) {
                                            this.referer = fallbackIframe
                                            this.quality = Qualities.P720.value
                                        }
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Fallback iframe विफल: ${e.message}")
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// StreamHG — multimoviesshg.com (JWPlayer eval-packed JS → m3u8)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * StreamHG एक्सट्रैक्टर:
 * - multimoviesshg.com/e/{id} पेज को फ़ेच करता है
 * - eval(function(p,a,c,k,e,d)) पैक्ड JS को JsUnpacker से अनपैक करता है
 * - अनपैक किए गए JS से m3u8 URL निकालता है
 * - फ़ॉलबैक: रॉ HTML और iframe chain
 */
class StreamHG : ExtractorApi() {
    override val name = "StreamHG"
    override val mainUrl = "https://multimoviesshg.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "StreamHG"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            // डायनेमिक डोमेन अपडेट
            val latestDomain = MultiMoviesProvider.getMultimoviesshgUrl()
            val oldBase = getBaseUrl(url)
            val finalUrl = if (latestDomain.isNotBlank() && latestDomain != oldBase) {
                url.replace(oldBase, latestDomain)
            } else url
            Log.d(tag, "अंतिम URL: $finalUrl")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(finalUrl, headers = headers, interceptor = MultiMoviesProvider.cfKiller)
            val html = response.text

            // === विधि 1: eval-packed JS ===
            val packed = extractFromPackedJs(html)
            if (packed != null) {
                Log.d(tag, "✅ ${if (packed.second) "m3u8" else "mp4"} मिला (packed JS): ${packed.first}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [${if (packed.second) "HLS" else "MP4"}]",
                        packed.first,
                        if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = finalUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }

            // === विधि 2: रॉ HTML से m3u8 ===
            Log.d(tag, "विधि 2: रॉ HTML से m3u8 खोज")
            extractM3u8FromText(html)?.let { rawM3u8 ->
                Log.d(tag, "✅ m3u8 मिला (raw HTML): $rawM3u8")
                callback.invoke(
                    newExtractorLink(
                        name, "$name [HLS]", rawM3u8, ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // === विधि 3: iframe chain ===
            Log.d(tag, "विधि 3: iframe chain")
            val doc = response.document
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank() && innerIframe != url) {
                Log.d(tag, "inner iframe: $innerIframe")
                val iframeResp = app.get(innerIframe, headers = headers, interceptor = MultiMoviesProvider.cfKiller)
                val iframeHtml = iframeResp.text

                val iframePacked = extractFromPackedJs(iframeHtml)
                if (iframePacked != null) {
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", iframePacked.first,
                            if (iframePacked.second) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = innerIframe
                            this.quality = Qualities.P720.value
                        }
                    )
                    return
                }

                extractM3u8FromText(iframeHtml)?.let { m3u8 ->
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", m3u8, ExtractorLinkType.M3U8
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
            Log.e(tag, "StreamHG एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// FileMoon — bysetayico.com / filemoon.sx (eval-packed JS → m3u8)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * FileMoon एक्सट्रैक्टर:
 * - bysetayico.com/e/{id}, filemoon.sx/e/{id} आदि पेज फ़ेच करता है
 * - eval(function(p,a,c,k,e,d)) पैक्ड JS अनपैक → m3u8 URL
 * - फ़ॉलबैक: रॉ HTML, iframe chain
 */
class FileMoon : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "FileMoon"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            val response = app.get(url, headers = headers, allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
            val html = response.text
            val finalPageUrl = response.url

            // === विधि 1: eval-packed JS ===
            val packed = extractFromPackedJs(html)
            if (packed != null) {
                Log.d(tag, "✅ ${if (packed.second) "m3u8" else "mp4"} मिला (packed JS): ${packed.first}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [${if (packed.second) "HLS" else "MP4"}]",
                        packed.first,
                        if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }

            // === विधि 2: रॉ HTML से m3u8 ===
            extractM3u8FromText(html)?.let { rawM3u8 ->
                Log.d(tag, "✅ m3u8 मिला (raw HTML): $rawM3u8")
                callback.invoke(
                    newExtractorLink(
                        name, "$name [HLS]", rawM3u8, ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // === विधि 3: iframe chain ===
            val doc = response.document
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank() && innerIframe != url) {
                Log.d(tag, "inner iframe: $innerIframe")
                val iframeResp = app.get(innerIframe, headers = headers, interceptor = MultiMoviesProvider.cfKiller)
                val iframeHtml = iframeResp.text

                val iframePacked = extractFromPackedJs(iframeHtml)
                if (iframePacked != null) {
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", iframePacked.first,
                            if (iframePacked.second) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = innerIframe
                            this.quality = Qualities.P720.value
                        }
                    )
                    return
                }

                extractM3u8FromText(iframeHtml)?.let { m3u8 ->
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", m3u8, ExtractorLinkType.M3U8
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
            Log.e(tag, "FileMoon एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// EarnVids — smoothpre.com (eval-packed JS / source tags → m3u8)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * EarnVids एक्सट्रैक्टर:
 * - smoothpre.com/v/{id} पेज फ़ेच करता है
 * - eval-packed JS, <source> टैग, और रॉ HTML से m3u8 निकालता है
 * - फ़ॉलबैक: iframe chain
 */
class EarnVids : ExtractorApi() {
    override val name = "EarnVids"
    override val mainUrl = "https://smoothpre.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "EarnVids"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            val response = app.get(url, headers = headers, allowRedirects = true, interceptor = MultiMoviesProvider.cfKiller)
            val html = response.text
            val finalPageUrl = response.url

            // === विधि 1: eval-packed JS ===
            val packed = extractFromPackedJs(html)
            if (packed != null) {
                Log.d(tag, "✅ ${if (packed.second) "m3u8" else "mp4"} मिला (packed JS): ${packed.first}")
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [${if (packed.second) "HLS" else "MP4"}]",
                        packed.first,
                        if (packed.second) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                return
            }

            // === विधि 2: <source> टैग ===
            val doc = response.document
            val sourceSrc = doc.select("source[src*=m3u8]").attr("src").ifBlank {
                doc.select("video source[type*=m3u8]").attr("src")
            }
            if (sourceSrc.isNotBlank()) {
                Log.d(tag, "✅ m3u8 मिला (source tag): $sourceSrc")
                callback.invoke(
                    newExtractorLink(
                        name, "$name [HLS]", sourceSrc, ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // === विधि 3: रॉ HTML से m3u8 ===
            extractM3u8FromText(html)?.let { rawM3u8 ->
                Log.d(tag, "✅ m3u8 मिला (raw HTML): $rawM3u8")
                callback.invoke(
                    newExtractorLink(
                        name, "$name [HLS]", rawM3u8, ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalPageUrl
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }

            // === विधि 4: iframe chain ===
            val innerIframe = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
            if (!innerIframe.isNullOrBlank() && innerIframe != url) {
                Log.d(tag, "inner iframe: $innerIframe")
                val iframeResp = app.get(innerIframe, headers = headers, interceptor = MultiMoviesProvider.cfKiller)
                val iframeHtml = iframeResp.text

                val iframePacked = extractFromPackedJs(iframeHtml)
                if (iframePacked != null) {
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", iframePacked.first,
                            if (iframePacked.second) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = innerIframe
                            this.quality = Qualities.P720.value
                        }
                    )
                    return
                }

                extractM3u8FromText(iframeHtml)?.let { m3u8 ->
                    callback.invoke(
                        newExtractorLink(
                            name, "$name [HLS]", m3u8, ExtractorLinkType.M3U8
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
            Log.e(tag, "EarnVids एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}


