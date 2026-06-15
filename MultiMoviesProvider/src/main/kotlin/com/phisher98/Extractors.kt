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
import okhttp3.FormBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

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
private val MPD_REGEX = Regex("""(https?://[^\s"'\\]+\.mpd[^\s"'\\]*)""")
private val MP4_REGEX = Regex("""(https?://[^\s"'\\]+\.mp4[^\s"'\\]*)""")
private val FILE_REGEX = Regex(""""?file"?\s*:\s*"(https?://[^"]+)"""")
private val SOURCE_REGEX = Regex(""""?sources?"?\s*:\s*\[\s*\{\s*"?(?:file|src)"?\s*:\s*"(https?://[^"]+)"""")

private fun cleanMediaUrl(url: String): String {
    return url
        .trim()
        .trim('"', '\'', ' ')
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
}

private fun absoluteUrl(url: String, base: String): String {
    val cleaned = cleanMediaUrl(url)
    return when {
        cleaned.startsWith("//") -> "https:$cleaned"
        cleaned.startsWith("/") -> "${getBaseUrl(base)}$cleaned"
        else -> cleaned
    }
}

private fun decodeQueryValue(value: String): String {
    return try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

private fun encodeQueryValue(value: String): String {
    return try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}

private fun getQueryParam(url: String, name: String): String? {
    return Regex("""[?&]${Regex.escape(name)}=([^&#]+)""")
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { decodeQueryValue(it) }
        ?.takeIf { it.isNotBlank() }
}

private fun decodeBase64Json(value: String): String? {
    return try {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.e("Base64Json", "decode failed: ${e.message}")
        null
    }
}

private fun extractSidFromUrl(url: String): String? {
    val cleaned = cleanMediaUrl(url)
    Regex("""/(?:evid|svid)/([^/?#]+)""", RegexOption.IGNORE_CASE)
        .find(cleaned)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    Regex("""/embed/(?!movie(?:[/?#]|$)|tv(?:[/?#]|$))([^/?#]+)""", RegexOption.IGNORE_CASE)
        .find(cleaned)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }

    val fragment = try {
        URI(cleaned).fragment
    } catch (_: Exception) {
        null
    }
    if (!fragment.isNullOrBlank()) return fragment

    if (cleaned.contains("/embed/movie/", true) || cleaned.contains("/embed/tv/", true)) return null

    return cleaned.substringBefore("?")
        .substringBefore("#")
        .substringAfterLast("/")
        .takeIf { it.isNotBlank() && !it.equals("movie", true) && !it.equals("tv", true) }
}

private fun emitDirectLink(
    sourceName: String,
    label: String,
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
) {
    val cleaned = cleanMediaUrl(url)
    val type = if (cleaned.contains(".m3u8", true)) ExtractorLinkType.M3U8 else INFER_TYPE
    callback.invoke(
        newExtractorLink(sourceName, label, cleaned, type) {
            this.referer = referer
            this.quality = Qualities.P720.value
        }
    )
}

private fun emitFirstMediaFromText(
    text: String,
    sourceName: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    extractMediaUrlsFromText(text).firstOrNull()?.let { url ->
        val tag = when {
            url.contains(".m3u8", true) -> "HLS"
            url.contains(".mpd", true) -> "MPD"
            url.contains(".mp4", true) -> "MP4"
            else -> "Video"
        }
        emitDirectLink(sourceName, "$sourceName [$tag]", url, referer, callback)
        return true
    }
    return false
}

private fun extractMediaUrlsFromText(text: String): List<String> {
    val cleanedText = text.replace("\\/", "/").replace("&amp;", "&")
    val urls = mutableListOf<String>()

    FILE_REGEX.findAll(cleanedText).forEach { urls.add(cleanMediaUrl(it.groupValues[1])) }
    SOURCE_REGEX.findAll(cleanedText).forEach { urls.add(cleanMediaUrl(it.groupValues[1])) }

    M3U8_REGEX.findAll(cleanedText).forEach { urls.add(cleanMediaUrl(it.groupValues[1])) }
    MPD_REGEX.findAll(cleanedText).forEach { urls.add(cleanMediaUrl(it.groupValues[1])) }
    MP4_REGEX.findAll(cleanedText).forEach { urls.add(cleanMediaUrl(it.groupValues[1])) }

    val hlsPlayerUrl = Regex("""hlsplayer\?url=([^"'\\\s]+)#([A-Za-z0-9_-]+)""")
        .find(cleanedText)
    if (hlsPlayerUrl != null) {
        val base = decodeQueryValue(hlsPlayerUrl.groupValues[1]).trimEnd('/')
        val id = hlsPlayerUrl.groupValues[2]
        urls.add("$base/$id")
    }

    return urls
        .map { cleanMediaUrl(it) }
        .filter { it.startsWith("http") }
        .filterNot { it.contains("advertisement", true) || it.contains("tracker", true) }
        .distinct()
}

private fun emitSourcesJson(
    text: String,
    sourceName: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    var emitted = false

    fun emitArray(array: JSONArray) {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val file = item.optString("file").ifBlank { item.optString("src") }
            if (file.isBlank()) continue
            val type = item.optString("type").ifBlank {
                when {
                    file.contains(".m3u8", true) -> "HLS"
                    file.contains(".mpd", true) -> "MPD"
                    else -> "Video"
                }
            }.uppercase()
            val label = item.optString("name").ifBlank { type }
            val linkReferer = item.optString("referer").ifBlank { referer }
            emitDirectLink(sourceName, "$sourceName [$label]", file, linkReferer, callback)
            emitted = true
        }
    }

    try {
        val json = JSONObject(text)
        json.optJSONArray("sources")?.let { emitArray(it) }
    } catch (_: Exception) {
        Regex(""""sources"\s*:\s*(\[[\s\S]*?])\s*[,}]""").find(text)?.groupValues?.getOrNull(1)?.let {
            try {
                emitArray(JSONArray(it))
            } catch (_: Exception) {}
        }
    }

    return emitted
}

private suspend fun dispatchKnownHost(
    url: String,
    referer: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    when {
        url.contains("streams.iqsmartgames.com", true) ->
            StreamsIqSmart().getUrl(url, referer, subtitleCallback, callback)
        url.contains("multimoviesshg", true) -> MultiMoviesSHG().getUrl(url, referer, subtitleCallback, callback)
        url.contains("smoothpre", true) -> SmoothPre().getUrl(url, referer, subtitleCallback, callback)
        url.contains("rpmhub", true) || url.contains("technocosmos", true) || url.contains("server1.uns.bio", true) ->
            RpmShare().getUrl(url, referer, subtitleCallback, callback)
        url.contains("sportseera", true) || url.contains("pages.dev", true) || url.contains("workers.dev", true) ->
            Sportseera().getUrl(url, referer, subtitleCallback, callback)
        url.contains("modiplay", true) || url.contains("rozgarlelo", true) ->
            Cineverse().getUrl(url, referer, subtitleCallback, callback)
        url.contains("screenscape", true) ->
            ScreenScape().getUrl(url, referer, subtitleCallback, callback)
        url.contains("peachify", true) ->
            PeachifyAPI().getUrl(url, referer, subtitleCallback, callback)
        url.contains("nxsha", true) ->
            NxshaApp().getUrl(url, referer, subtitleCallback, callback)
        url.contains("nhdapi", true) ->
            NhdAPI().getUrl(url, referer, subtitleCallback, callback)
        // plyr.technocosmos.surf/hlsplayer URLs -> RpmShare handles them
        url.contains("plyr.", true) && url.contains("hlsplayer", true) ->
            RpmShare().getUrl(url, referer, subtitleCallback, callback)
        else -> extractGenericM3u8(url, referer, "GDMirrorBot", callback)
    }
}

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
            if (url.contains("streams.iqsmartgames.com", true)) {
                StreamsIqSmart().getUrl(url, referer, subtitleCallback, callback)
                return
            }

            // डायनेमिक डोमेन — gdmirror key से नवीनतम URL लाना
            val latestUrl = getLatestUrl(url, "gdmirror")
            Log.d(tag, "नवीनतम GDMirror डोमेन: $latestUrl")

            // रीडायरेक्ट फ़ॉलो करके अंतिम पेज प्राप्त करना
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val finalUrl = response.url
            val doc = response.document
            val sid = doc.selectFirst("#gdmrfid")?.attr("value")?.takeIf { it.isNotBlank() }
                ?: extractSidFromUrl(url)
                ?: extractSidFromUrl(response.url)
            if (!sid.isNullOrBlank()) {
                Log.d(tag, "GDMirror helper sid: $sid")
                extractFromGdMirrorHelper(sid, response.url, referer, subtitleCallback, callback)
            }
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

                        // UPNSHR — plyr.technocosmos.surf/hlsplayer or server1.uns.bio
                        serverLink.contains("technocosmos", true) || serverLink.contains("server1.uns.bio", true) ||
                            serverLink.contains("hlsplayer", true) ->
                            RpmShare().getUrl(serverLink, finalUrl, subtitleCallback, callback)

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
                val iframeSrc = doc.selectFirst("iframe#vidFrame[src], iframe#vidFreme[src], iframe[src]")
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
private suspend fun extractFromGdMirrorHelper(
    sid: String,
    referer: String,
    sourceReferer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val tag = "GDMirrorHelper"
    try {
        val sourceHost = sourceReferer?.let {
            try {
                URI(it).host
            } catch (_: Exception) {
                null
            }
        } ?: "multimovies.makeup"
        val currentDomain = JSONArray()
            .put(sourceHost)
            .put("pro.iqsmartgames.com")
            .toString()
        val body = FormBody.Builder()
            .add("sid", sid)
            .add("UserFavSite", "")
            .add("currentDomain", currentDomain)
            .build()
        val helperText = app.post(
            "https://pro.iqsmartgames.com/embedhelper.php",
            requestBody = body,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to "https://pro.iqsmartgames.com",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        if (emitSourcesJson(helperText, "GDMirrorBot", referer, callback)) return

        val helperJson = JSONObject(helperText)
        val siteUrls = helperJson.optJSONObject("siteUrls") ?: JSONObject()
        val decoded = decodeBase64Json(helperJson.optString("mresult")) ?: return
        val mresult = JSONObject(decoded)
        val priority = listOf("smwh", "flls", "rpmshre", "upnshr", "strmp2", "gofs", "flmn", "stmrb", "strmtp")

        for (key in priority) {
            val id = mresult.optString(key).takeIf { it.isNotBlank() } ?: continue
            val baseUrl = siteUrls.optString(key).takeIf { it.isNotBlank() } ?: continue
            val sourceUrl = "${cleanMediaUrl(baseUrl)}$id"
            Log.d(tag, "helper source $key -> $sourceUrl")
            try {
                dispatchKnownHost(sourceUrl, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(tag, "helper source $key failed: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Log.e(tag, "helper extraction failed: ${e.message}")
    }
}

class StreamsIqSmart : ExtractorApi() {
    override val name = "StreamsIqSmart"
    override val mainUrl = "https://streams.iqsmartgames.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "StreamsIqSmart"
        try {
            val cleanedUrl = cleanMediaUrl(url)
            Log.d(tag, "processing: $cleanedUrl")

            val key = getQueryParam(cleanedUrl, "key") ?: run {
                Log.e(tag, "missing api key: $cleanedUrl")
                return
            }

            val apiUrl = buildApiUrl(cleanedUrl, key) ?: run {
                Log.e(tag, "unsupported streams embed url: $cleanedUrl")
                return
            }

            val apiText = app.get(
                apiUrl,
                referer = cleanedUrl,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
            Log.d(tag, "api response length: ${apiText.length}")

            if (emitSourcesJson(apiText, name, cleanedUrl, callback)) return

            val json = JSONObject(apiText)
            if (!json.optBoolean("success", false)) {
                Log.e(tag, "api failed: ${json.optString("message")}")
                return
            }

            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val fileSlug = item.optString("fileslug").takeIf { it.isNotBlank() } ?: continue
                val fileName = item.optString("filename")
                Log.d(tag, "fileslug: $fileSlug ($fileName)")
                extractFromGdMirrorHelper(fileSlug, cleanedUrl, referer, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(tag, "extraction failed: ${e.message}")
        }
    }

    private fun buildApiUrl(url: String, key: String): String? {
        Regex("""/embed/movie/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { imdbId ->
                return "$mainUrl/mymovieapi?imdbid=${encodeQueryValue(imdbId)}&key=${encodeQueryValue(key)}"
            }

        Regex("""/embed/tv/([^/]+)/([^/]+)/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.let { values ->
                val tmdbId = values.getOrNull(1).orEmpty()
                val season = values.getOrNull(2).orEmpty()
                val episode = values.getOrNull(3).orEmpty()
                if (tmdbId.isBlank() || season.isBlank() || episode.isBlank()) return null
                return "$mainUrl/myseriesapi?tmdbid=${encodeQueryValue(tmdbId)}&season=${encodeQueryValue(season)}&epname=${encodeQueryValue(episode)}&key=${encodeQueryValue(key)}"
            }

        return null
    }
}

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

            val normalizedUrl = normalizeRpmUrl(url)

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl)
            )

            val response = app.get(normalizedUrl, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url
            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

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
private fun normalizeRpmUrl(url: String): String {
    val cleaned = cleanMediaUrl(url)
    // hlsplayer?url=BASE_URL#FRAGMENT -> BASE_URL/FRAGMENT
    val hlsPlayer = Regex("""hlsplayer\?url=([^#]+)#([A-Za-z0-9_-]+)""").find(cleaned)
    if (hlsPlayer != null) {
        val base = decodeQueryValue(hlsPlayer.groupValues[1]).trimEnd('/')
        val id = hlsPlayer.groupValues[2]
        return "$base/$id"
    }

    // plyr.technocosmos.surf or similar proxy with hlsplayer pattern
    val technoProxy = Regex("""(?:plyr\.[^/]+)/hlsplayer\?url=([^#\s]+?)(?:#([A-Za-z0-9_-]+))?""").find(cleaned)
    if (technoProxy != null) {
        val base = decodeQueryValue(technoProxy.groupValues[1]).trimEnd('/')
        val id = technoProxy.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }
        return if (id != null) "$base/$id" else base
    }

    val fragment = try {
        URI(cleaned).fragment
    } catch (_: Exception) {
        null
    }
    if (!fragment.isNullOrBlank() && (cleaned.contains("rpmhub", true) || cleaned.contains("uns.bio", true))) {
        return "${getBaseUrl(cleaned)}/$fragment"
    }

    return cleaned
}

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
class Sportseera : ExtractorApi() {
    override val name = "Sportseera"
    override val mainUrl = "https://sportseera2.pages.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Sportseera"
        try {
            val cleanedUrl = cleanMediaUrl(url)
            val directMpd = Regex("""[?&]url=([^&]+)""").find(cleanedUrl)?.groupValues?.getOrNull(1)
            if (!directMpd.isNullOrBlank()) {
                val mpdUrl = decodeQueryValue(directMpd)
                emitDirectLink(name, "$name [MPD]", mpdUrl, cleanedUrl, callback)
                return
            }

            val id = Regex("""[?&]id=([^&#]+)""").find(cleanedUrl)?.groupValues?.getOrNull(1)
                ?: cleanedUrl.substringAfterLast("id=", "").takeIf { it.isNotBlank() }
            if (id.isNullOrBlank()) return

            val apiUrl = "https://universal.sportseera.workers.dev/?id=${encodeQueryValue(id)}"
            val response = app.get(
                apiUrl,
                headers = mapOf("Referer" to cleanedUrl)
            ).text
            val json = JSONObject(response)
            val keys = json.keys()
            while (keys.hasNext()) {
                val channel = json.optJSONObject(keys.next()) ?: continue
                val mpdUrl = channel.optString("url").takeIf { it.isNotBlank() } ?: continue
                emitDirectLink(name, "$name [MPD]", mpdUrl, cleanedUrl, callback)

                val keyMap = channel.optJSONObject("keys") ?: continue
                val keyIterator = keyMap.keys()
                if (keyIterator.hasNext()) {
                    val kid = keyIterator.next()
                    val key = keyMap.optString(kid)
                    if (key.isNotBlank()) {
                        val drmPage = "$mainUrl/Drm/P3?url=${encodeQueryValue(mpdUrl)}&key=$kid:$key"
                        emitDirectLink(name, "$name [DRM Page]", drmPage, cleanedUrl, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Sportseera extraction failed: ${e.message}")
        }
    }
}

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
    val directMatch = extractMediaUrlsFromText(text).firstOrNull {
        it.contains(".m3u8", true) || it.contains(".mp4", true)
    }
    if (directMatch != null) return directMatch
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
        if (emitSourcesJson(html, sourceName, currentUrl, callback)) return

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

// ═══════════════════════════════════════════════════════════════════════════════════
// Cineverse — rozgarlelo.modiplay.xyz (VidSrc-स्टाइल एम्बेड)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * Cineverse एक्सट्रैक्टर:
 * - rozgarlelo.modiplay.xyz/embed/imdb/movie?id={imdb} से m3u8 URL निकालता है
 * - VidSrc-स्टाइल embeds — packed JS, raw HTML, iframe chain
 */
class Cineverse : ExtractorApi() {
    override val name = "Cineverse"
    override val mainUrl = "https://rozgarlelo.modiplay.xyz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "Cineverse"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            // sources JSON चेक
            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

            // eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                Log.d(tag, "eval-packed JS मिला")
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            if (emitFirstMediaFromText(unpacked, name, finalPageUrl, callback)) return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            // रॉ HTML/JS से m3u8/mp4
            if (emitFirstMediaFromText(html, name, finalPageUrl, callback)) return

            // iframe chain
            val doc = response.document
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue
                Log.d(tag, "iframe मिला: $iframeSrc")
                try {
                    val iframeResp = app.get(absoluteUrl(iframeSrc, finalPageUrl), headers = headers, allowRedirects = true)
                    val iframeHtml = iframeResp.text
                    val iframeFinalUrl = iframeResp.url

                    if (emitSourcesJson(iframeHtml, name, iframeFinalUrl, callback)) return

                    if (iframeHtml.contains("eval(function(p,a,c,k,e,d)")) {
                        val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                        val evalBlock = evalRegex.find(iframeHtml)?.groupValues?.get(1)
                        if (evalBlock != null) {
                            val unpacked = JsUnpacker(evalBlock).unpack()
                            if (!unpacked.isNullOrBlank()) {
                                if (emitFirstMediaFromText(unpacked, name, iframeFinalUrl, callback)) return
                            }
                        }
                    }

                    if (emitFirstMediaFromText(iframeHtml, name, iframeFinalUrl, callback)) return
                } catch (e: Exception) {
                    Log.e(tag, "iframe प्रोसेसिंग विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "Cineverse एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// ScreenScape — screenscape.me (VidSrc-स्टाइल एम्बेड)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * ScreenScape एक्सट्रैक्टर:
 * - screenscape.me/embed?imdb={imdb}&type=movie&lan=hindi से m3u8 निकालता है
 * - iframe चेन और API एंडपॉइंट सपोर्ट
 */
class ScreenScape : ExtractorApi() {
    override val name = "ScreenScape"
    override val mainUrl = "https://screenscape.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "ScreenScape"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/131.0.0.0",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

            // eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            if (emitFirstMediaFromText(unpacked, name, finalPageUrl, callback)) return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            if (emitFirstMediaFromText(html, name, finalPageUrl, callback)) return

            // API endpoint discovery — /api/source/ या /api/stream/
            val imdbMatch = Regex("""imdb=([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
            if (imdbMatch != null) {
                val imdbId = imdbMatch.groupValues[1]
                val apiBase = getBaseUrl(finalPageUrl)
                val apiEndpoints = listOf(
                    "$apiBase/api/source/$imdbId",
                    "$apiBase/api/stream/$imdbId",
                    "$apiBase/ajax/embed/$imdbId"
                )
                for (endpoint in apiEndpoints) {
                    try {
                        val apiResp = app.get(endpoint, headers = headers)
                        val apiText = apiResp.text
                        if (emitSourcesJson(apiText, name, finalPageUrl, callback)) return
                        if (emitFirstMediaFromText(apiText, name, finalPageUrl, callback)) return
                    } catch (_: Exception) {}
                }
            }

            // iframe chain
            val doc = response.document
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue
                Log.d(tag, "iframe: $iframeSrc")
                try {
                    val iframeResp = app.get(absoluteUrl(iframeSrc, finalPageUrl), headers = headers, allowRedirects = true)
                    val iframeHtml = iframeResp.text
                    if (emitSourcesJson(iframeHtml, name, iframeResp.url, callback)) return
                    if (emitFirstMediaFromText(iframeHtml, name, iframeResp.url, callback)) return
                } catch (e: Exception) {
                    Log.e(tag, "iframe विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "ScreenScape एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// PeachifyAPI — peachify.top (API-आधारित एम्बेड)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * PeachifyAPI एक्सट्रैक्टर:
 * - peachify.top/embed/movie/{imdb}?dub=Hindi&sub=English से m3u8 निकालता है
 * - API-based जो sources JSON रिटर्न करता है
 */
class PeachifyAPI : ExtractorApi() {
    override val name = "PeachifyAPI"
    override val mainUrl = "https://peachify.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "PeachifyAPI"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/131.0.0.0",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

            // eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            if (emitFirstMediaFromText(unpacked, name, finalPageUrl, callback)) return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            if (emitFirstMediaFromText(html, name, finalPageUrl, callback)) return

            // URL से IMDB ID निकालकर API एंडपॉइंट ट्राई करना
            val imdbMatch = Regex("""/(?:movie|tv)/([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
            if (imdbMatch != null) {
                val imdbId = imdbMatch.groupValues[1]
                val apiBase = getBaseUrl(finalPageUrl)
                val apiEndpoints = listOf(
                    "$apiBase/api/source/$imdbId",
                    "$apiBase/api/stream/$imdbId"
                )
                for (endpoint in apiEndpoints) {
                    try {
                        val apiResp = app.get(endpoint, headers = headers)
                        val apiText = apiResp.text
                        if (emitSourcesJson(apiText, name, finalPageUrl, callback)) return
                        if (emitFirstMediaFromText(apiText, name, finalPageUrl, callback)) return
                    } catch (_: Exception) {}
                }
            }

            // iframe chain
            val doc = response.document
            for (iframe in doc.select("iframe[src]")) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue
                Log.d(tag, "iframe: $iframeSrc")
                try {
                    val iframeResp = app.get(absoluteUrl(iframeSrc, finalPageUrl), headers = headers, allowRedirects = true)
                    if (emitSourcesJson(iframeResp.text, name, iframeResp.url, callback)) return
                    if (emitFirstMediaFromText(iframeResp.text, name, iframeResp.url, callback)) return
                } catch (e: Exception) {
                    Log.e(tag, "iframe विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "PeachifyAPI एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// NxshaApp — web.nxsha.app (VidSrc-स्टाइल एम्बेड)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * NxshaApp एक्सट्रैक्टर:
 * - web.nxsha.app/embed/movie/{imdb} से m3u8 URL निकालता है
 * - VidSrc-style embed — packed JS, API, iframe chain
 */
class NxshaApp : ExtractorApi() {
    override val name = "NxshaApp"
    override val mainUrl = "https://web.nxsha.app"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "NxshaApp"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/131.0.0.0",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

            // eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            if (emitFirstMediaFromText(unpacked, name, finalPageUrl, callback)) return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            if (emitFirstMediaFromText(html, name, finalPageUrl, callback)) return

            // API endpoint — /api/source/{imdb}
            val imdbMatch = Regex("""/(?:embed/)?(?:movie|tv)/([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
            if (imdbMatch != null) {
                val imdbId = imdbMatch.groupValues[1]
                val apiBase = getBaseUrl(finalPageUrl)
                val apiEndpoints = listOf(
                    "$apiBase/api/source/$imdbId",
                    "$apiBase/api/stream/$imdbId",
                    "$apiBase/ajax/embed/movie/$imdbId"
                )
                for (endpoint in apiEndpoints) {
                    try {
                        val apiResp = app.get(endpoint, headers = headers)
                        val apiText = apiResp.text
                        if (emitSourcesJson(apiText, name, finalPageUrl, callback)) return
                        if (emitFirstMediaFromText(apiText, name, finalPageUrl, callback)) return
                    } catch (_: Exception) {}
                }
            }

            // iframe chain
            val doc = response.document
            for (iframe in doc.select("iframe[src]")) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue
                Log.d(tag, "iframe: $iframeSrc")
                try {
                    val iframeResp = app.get(absoluteUrl(iframeSrc, finalPageUrl), headers = headers, allowRedirects = true)
                    if (emitSourcesJson(iframeResp.text, name, iframeResp.url, callback)) return
                    if (emitFirstMediaFromText(iframeResp.text, name, iframeResp.url, callback)) return
                } catch (e: Exception) {
                    Log.e(tag, "iframe विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "NxshaApp एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════════
// NhdAPI — nhdapi.com (API-आधारित एम्बेड)
// ═══════════════════════════════════════════════════════════════════════════════════

/**
 * NhdAPI एक्सट्रैक्टर:
 * - nhdapi.com/embed/movie/{imdb}?autoplay से m3u8 URL निकालता है
 * - API-based embed — packed JS, sources JSON, iframe chain
 */
class NhdAPI : ExtractorApi() {
    override val name = "NhdAPI"
    override val mainUrl = "https://nhdapi.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "NhdAPI"
        try {
            Log.d(tag, "प्रोसेसिंग शुरू: $url")

            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/131.0.0.0",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )

            val response = app.get(url, headers = headers, allowRedirects = true)
            val html = response.text
            val finalPageUrl = response.url

            if (emitSourcesJson(html, name, finalPageUrl, callback)) return

            // eval-packed JS
            if (html.contains("eval(function(p,a,c,k,e,d)")) {
                try {
                    val evalRegex = Regex("""(eval\(function\(p,a,c,k,e,d\).*?\))\s*</script""", RegexOption.DOT_MATCHES_ALL)
                    val evalBlock = evalRegex.find(html)?.groupValues?.get(1)
                    if (evalBlock != null) {
                        val unpacked = JsUnpacker(evalBlock).unpack()
                        if (!unpacked.isNullOrBlank()) {
                            if (emitFirstMediaFromText(unpacked, name, finalPageUrl, callback)) return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "JsUnpacker विफल: ${e.message}")
                }
            }

            if (emitFirstMediaFromText(html, name, finalPageUrl, callback)) return

            // API endpoint
            val imdbMatch = Regex("""/(?:embed/)?(?:movie|tv)/([a-z0-9]+)""", RegexOption.IGNORE_CASE).find(url)
            if (imdbMatch != null) {
                val imdbId = imdbMatch.groupValues[1]
                val apiBase = getBaseUrl(finalPageUrl)
                val apiEndpoints = listOf(
                    "$apiBase/api/source/$imdbId",
                    "$apiBase/api/stream/$imdbId"
                )
                for (endpoint in apiEndpoints) {
                    try {
                        val apiResp = app.get(endpoint, headers = headers)
                        val apiText = apiResp.text
                        if (emitSourcesJson(apiText, name, finalPageUrl, callback)) return
                        if (emitFirstMediaFromText(apiText, name, finalPageUrl, callback)) return
                    } catch (_: Exception) {}
                }
            }

            // iframe chain
            val doc = response.document
            for (iframe in doc.select("iframe[src]")) {
                val iframeSrc = iframe.attr("src").trim()
                if (iframeSrc.isBlank() || iframeSrc == url) continue
                Log.d(tag, "iframe: $iframeSrc")
                try {
                    val iframeResp = app.get(absoluteUrl(iframeSrc, finalPageUrl), headers = headers, allowRedirects = true)
                    if (emitSourcesJson(iframeResp.text, name, iframeResp.url, callback)) return
                    if (emitFirstMediaFromText(iframeResp.text, name, iframeResp.url, callback)) return
                } catch (e: Exception) {
                    Log.e(tag, "iframe विफल: ${e.message}")
                }
            }

            Log.e(tag, "❌ कोई प्लेएबल लिंक नहीं मिला")

        } catch (e: Exception) {
            Log.e(tag, "NhdAPI एक्सट्रैक्शन विफल: ${e.message}")
        }
    }
}
