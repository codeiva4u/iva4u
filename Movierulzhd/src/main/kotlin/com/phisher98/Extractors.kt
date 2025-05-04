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
        // मुख्य iframe source निकालना - अपडेटेड सेलेक्टर्स
        val doc = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        
        // नए सेलेक्टर्स के साथ iframe खोजें
        val iframeSrc = doc.selectFirst(".metaframe, .player-embed iframe, .playbox iframe, div.video-content iframe")?.attr("src") ?: ""
        
        if (iframeSrc.isEmpty()) {
            // वेबसाइट पर iframe के लिए अतिरिक्त सेलेक्टर्स चेक करें
            val altIframe = doc.select("iframe").firstOrNull()?.attr("src") ?: ""
            if (altIframe.isNotEmpty()) {
                extractFilemoon(altIframe, url, callback)
                return
            }
            
            // स्क्रिप्ट से iframe URL निकालने का प्रयास करें
            val scripts = doc.select("script").mapNotNull { it.data() }
            val iframeRegex = Regex("iframe src=['\"]([^'\"]+)['\"]")
            val scriptIframe = scripts.mapNotNull { script ->
                iframeRegex.find(script)?.groupValues?.getOrNull(1)
            }.firstOrNull()
            
            if (!scriptIframe.isNullOrEmpty()) {
                extractFilemoon(scriptIframe, url, callback)
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
        
        // नए डोमेन के लिए URL फिक्स करें - अपडेटेड डोमेन के साथ
        val fixedUrl = if (!iframeUrl.startsWith("http")) {
            // चेक करें कि कौन सा डोमेन काम कर रहा है
            val domains = listOf(
                "movierulz.upn.one", "movierulz.upn.lol", "movierulz.upn.pw",
                "filemoon.sx", "filemoon.to", "filemoon.in", "filemoon.wf",
                "filemoon.nl", "filemoon.art", "filemoon.top"
            )
            val workingDomain = domains.firstOrNull { domain ->
                try {
                    val testUrl = "https://$domain"
                    val response = app.get(testUrl, headers = headers)
                    response.code == 200
                } catch (e: Exception) {
                    false
                }
            } ?: "movierulz.upn.one"
            
            if (iframeUrl.startsWith("/")) "https://$workingDomain$iframeUrl" else "https://$workingDomain/$iframeUrl"
        } else iframeUrl
        
        // नए URL पैटर्न के लिए चेक करें - UUID फॉर्मेट
        val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val uuidMatch = uuidPattern.find(fixedUrl)
        
        if (uuidMatch != null) {
            val domain = fixedUrl.split("/").let { parts ->
                if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
            }
            
            // UUID से वीडियो ID निकालें
            val uuid = uuidMatch.value
            
            try {
                // वेबसाइट से HTML प्राप्त करें
                val response = app.get(fixedUrl, headers = headers)
                val html = response.text
                
                // m3u8 URL पैटर्न खोजें
                val m3u8Pattern = Regex("\"/hls/[^\"]+/master\\.m3u8\"")
                val m3u8Match = m3u8Pattern.find(html)
                
                if (m3u8Match != null) {
                    // m3u8 URL निकालें और quotes हटाएं
                    val m3u8Path = m3u8Match.value.replace("\"", "")
                    val m3u8Url = "$domain$m3u8Path"
                    
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
                
                // अगर पैटर्न नहीं मिला तो फॉलबैक URL का उपयोग करें
                val fallbackUrl = "$domain/hls/$uuid/master.m3u8"
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        fallbackUrl,
                        fixedUrl,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            } catch (e: Exception) {
                // एक्सेप्शन के मामले में फॉलबैक URL का उपयोग करें
                val fallbackUrl = "$domain/hls/$uuid/master.m3u8"
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        fallbackUrl,
                        fixedUrl,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            }
        }
        
        val resDoc = app.get(fixedUrl, headers = headers).document
        
        // 1. नया पैटर्न: API से वीडियो URL निकालना
        val videoIdRegex = Regex("video_id\\s*=\\s*['\"]([^'\"]+)['\"]")
        val videoId = resDoc.select("script").mapNotNull { it.data() }
            .mapNotNull { videoIdRegex.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            
        if (!videoId.isNullOrEmpty()) {
            val domain = fixedUrl.split("/").let { parts ->
                if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
            }
            
            try {
                // API से वीडियो URL प्राप्त करें
                val apiUrl = "$domain/api/source/$videoId"
                val apiResponse = app.post(apiUrl, headers = headers).text
                val fileRegex = Regex("\"file\":\"([^\"]+)\"")
                val fileUrl = fileRegex.find(apiResponse)?.groupValues?.getOrNull(1)?.replace("\\\\", "")
                
                if (!fileUrl.isNullOrEmpty()) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            fileUrl,
                            fixedUrl,
                            Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                    return
                }
            } catch (e: Exception) {
                // API कॉल फेल होने पर अगले मेथड ट्राय करें
            }
        }
        
        // 2. पोस्टर इमेज से वीडियो ID निकालना - अपडेटेड सेलेक्टर
        val posterSelectors = listOf(
            "#player-button-container", ".player-button-container", ".player-poster",
            ".jw-preview", ".plyr-poster", ".video-poster"
        )
        
        val posterUrl = resDoc.select(posterSelectors.joinToString(", ")).firstOrNull()?.attr("style")?.let {
            Regex("background-image:\\s*url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.getOrNull(1)
        } ?: resDoc.select("img.poster, img.video-poster").firstOrNull()?.attr("src")
        
        if (!posterUrl.isNullOrEmpty()) {
            // नया पैटर्न - पूरा पाथ से वीडियो ID बनाना
            val segments = posterUrl.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 4) {
                // नए पैटर्न में पहले 4 सेगमेंट्स से वीडियो ID बनाते हैं
                val videoId = segments.take(4).joinToString("/")
                // वीडियो ID से m3u8 URL बनाएं - डोमेन से निकालें
                val domain = fixedUrl.split("/").let { parts ->
                    if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
                }
                val m3u8Url = "$domain/m3u8/$videoId/master.m3u8"
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
            
            // पुराना पैटर्न भी ट्राय करें
            val videoId = posterUrl.split("/").let { parts ->
                if (parts.size >= 3) parts[parts.size - 3] else null
            }
            
            if (!videoId.isNullOrEmpty()) {
                // वीडियो ID से m3u8 URL बनाएं - डोमेन से निकालें
                val domain = fixedUrl.split("/").let { parts ->
                    if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
                }
                val m3u8Url = "$domain/m3u8/$videoId/master.m3u8"
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
        
        // 3. sniff फंक्शन से वीडियो ID निकालना
        val sniffScript = resDoc.select("script").find { it.data().contains("sniff") }?.data()
        if (!sniffScript.isNullOrEmpty()) {
            val sniffPattern = Regex("sniff\\(([^)]+)\\)")
            val sniffMatch = sniffPattern.find(sniffScript)
            val sniffParams = sniffMatch?.groupValues?.getOrNull(1)?.split(",")?.map { it.replace("\"", "").trim() }
            
            if (sniffParams != null && sniffParams.size > 2) {
                val domain = fixedUrl.split("/").let { parts ->
                    if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
                }
                val m3u8Url = "$domain/m3u8/${sniffParams[1]}/${sniffParams[2]}/master.m3u8"
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
        
        // 4. JS packed script ढूंढना - अपडेटेड सेलेक्टर्स
        val packedScripts = resDoc.select("script").mapNotNull { it.data() }
            .filter { it.contains("function(p,a,c,k,e,d)") }
        
        for (packedScript in packedScripts) {
            try {
                val unpacked = JsUnpacker(packedScript).unpack()
                if (unpacked != null) {
                    // विभिन्न पैटर्न्स के साथ m3u8 URL खोजें
                    val patterns = listOf(
                        "sources:\\[\\{file:[\"']([^\"']+)[\"']\\}",
                        "file:[\"']([^\"']+\\.m3u8[^\"']*)",
                        "src:[\"']([^\"']+\\.m3u8[^\"']*)",
                        "file:[\"']([^\"']+)",
                        "src:[\"']([^\"']+)"
                    )
                    
                    for (pattern in patterns) {
                        val m3u8 = Regex(pattern).find(unpacked)?.groupValues?.getOrNull(1)
                        if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                            callback.invoke(
                                ExtractorLink(
                                    this.name,
                                    this.name,
                                    m3u8.toString(),
                                    fixedUrl,
                                    Qualities.P1080.value,
                                    type = if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    headers = headers
                                )
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                // JsUnpacker फेल होने पर अगले स्क्रिप्ट पर जाएं
            }
        }
        
        // 5. सभी स्क्रिप्ट्स में वीडियो URL खोजें
        val scripts = resDoc.select("script").mapNotNull { it.data() }
        val patterns = listOf(
            "sources:\\[\\{file:[\"']([^\"']+)[\"']\\}",
            "file:[\"']([^\"']+\\.m3u8[^\"']*)",
            "src:[\"']([^\"']+\\.m3u8[^\"']*)",
            "file:[\"']([^\"']+)",
            "src:[\"']([^\"']+)"
        )
        
        for (script in scripts) {
            for (pattern in patterns) {
                val m3u8 = Regex(pattern).find(script)?.groupValues?.getOrNull(1)
                if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            m3u8.toString(),
                            fixedUrl,
                            Qualities.P1080.value,
                            type = if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            headers = headers
                        )
                    )
                    return
                }
            }
        }
        
        // 6. iframe के अंदर iframe खोजें
        val nestedIframe = resDoc.select("iframe").firstOrNull()?.attr("src")
        if (!nestedIframe.isNullOrEmpty() && nestedIframe != iframeUrl) {
            try {
                val nestedUrl = if (nestedIframe.startsWith("http")) nestedIframe else {
                    val domain = fixedUrl.split("/").let { parts ->
                        if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
                    }
                    if (nestedIframe.startsWith("/")) "$domain$nestedIframe" else "$domain/$nestedIframe"
                }
                
                val nestedDoc = app.get(nestedUrl, headers = headers).document
                val nestedScripts = nestedDoc.select("script").mapNotNull { it.data() }
                
                for (script in nestedScripts) {
                    for (pattern in patterns) {
                        val m3u8 = Regex(pattern).find(script)?.groupValues?.getOrNull(1)
                        if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                            callback.invoke(
                                ExtractorLink(
                                    this.name,
                                    this.name,
                                    m3u8.toString(),
                                    nestedUrl,
                                    Qualities.P1080.value,
                                    type = if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                                    headers = headers
                                )
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                // नेस्टेड iframe फेल होने पर अगले मेथड ट्राय करें
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
    override val mainUrl = "https://movierulz.upn.one" // बेस डोमेन, रनटाइम में अपडेट होगा
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("user-agent" to USER_AGENT)
        
        // UUID पैटर्न के लिए चेक करें
        val uuidPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        val uuidMatch = uuidPattern.find(url)
        
        if (uuidMatch != null) {
            val domain = url.split("/").let { parts ->
                if (parts.size >= 3) "${parts[0]}//${parts[2]}" else "https://movierulz.upn.one"
            }
            
            // UUID से वीडियो ID निकालें
            val uuid = uuidMatch.value
            
            try {
                // वेबसाइट से HTML प्राप्त करें
                val response = app.get(url, headers = headers)
                val html = response.text
                
                // m3u8 URL पैटर्न खोजें
                val m3u8Pattern = Regex("\"/hls/[^\"]+/master\\.m3u8\"")
                val m3u8Match = m3u8Pattern.find(html)
                
                if (m3u8Match != null) {
                    // m3u8 URL निकालें और quotes हटाएं
                    val m3u8Path = m3u8Match.value.replace("\"", "")
                    val m3u8Url = "$domain$m3u8Path"
                    
                    callback.invoke(
                        ExtractorLink(
                            this.name,
                            this.name,
                            m3u8Url,
                            url,
                            Qualities.P1080.value,
                            type = ExtractorLinkType.M3U8,
                            headers = headers
                        )
                    )
                    return
                }
                
                // अगर पैटर्न नहीं मिला तो फॉलबैक URL का उपयोग करें
                val fallbackUrl = "$domain/hls/$uuid/master.m3u8"
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        fallbackUrl,
                        url,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            } catch (e: Exception) {
                // एक्सेप्शन के मामले में फॉलबैक URL का उपयोग करें
                val fallbackUrl = "$domain/hls/$uuid/master.m3u8"
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        this.name,
                        fallbackUrl,
                        url,
                        Qualities.P1080.value,
                        type = ExtractorLinkType.M3U8,
                        headers = headers
                    )
                )
                return
            }
        }
        
        // वर्किंग डोमेन चेक करें - अपडेटेड डोमेन लिस्ट
        val domains = listOf(
            "movierulz.upn.one", "movierulz.upn.lol", "movierulz.upn.pw",
            "filemoon.sx", "filemoon.to", "filemoon.in", "filemoon.wf",
            "filemoon.nl", "filemoon.art", "filemoon.top"
        )
        val workingDomain = domains.firstOrNull { domain ->
            try {
                val testUrl = "https://$domain"
                val response = app.get(testUrl, headers = headers)
                response.code == 200
            } catch (e: Exception) {
                false
            }
        } ?: "movierulz.upn.one"
        
        val currentMainUrl = "https://$workingDomain"
        val res = app.get(url, referer = referer, headers = headers).document
        
        // 1. नया पैटर्न: API से वीडियो URL निकालना
        val videoIdRegex = Regex("video_id\\s*=\\s*['\"]([^'\"]+)['\"]")
        val videoId = res.select("script").mapNotNull { it.data() }
            .mapNotNull { videoIdRegex.find(it)?.groupValues?.getOrNull(1) }
            .firstOrNull()
            
        if (!videoId.isNullOrEmpty()) {
            try {
                // API से वीडियो URL प्राप्त करें
                val apiUrl = "$currentMainUrl/api/source/$videoId"
                val apiResponse = app.post(apiUrl, headers = headers).text
                val fileRegex = Regex("\"file\":\"([^\"]+)\"")
                val fileUrl = fileRegex.find(apiResponse)?.groupValues?.getOrNull(1)?.replace("\\\\", "")
                
                if (!fileUrl.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            fileUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                            this.headers = headers
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                // API कॉल फेल होने पर अगले मेथड ट्राय करें
            }
        }
        
        // 2. पोस्टर इमेज से वीडियो ID निकालना - अपडेटेड सेलेक्टर
        val posterSelectors = listOf(
            "#player-button-container", ".player-button-container", ".player-poster",
            ".jw-preview", ".plyr-poster", ".video-poster"
        )
        
        val posterUrl = res.select(posterSelectors.joinToString(", ")).firstOrNull()?.attr("style")?.let {
            Regex("background-image:\\s*url\\(['\"]?(.*?)['\"]?\\)").find(it)?.groupValues?.getOrNull(1)
        } ?: res.select("img.poster, img.video-poster").firstOrNull()?.attr("src")
        
        if (!posterUrl.isNullOrEmpty()) {
            // नया पैटर्न - पूरा पाथ से वीडियो ID बनाना
            val segments = posterUrl.split("/").filter { it.isNotEmpty() }
            if (segments.size >= 4) {
                // नए पैटर्न में पहले 4 सेगमेंट्स से वीडियो ID बनाते हैं
                val videoId = segments.take(4).joinToString("/")
                // वीडियो ID से m3u8 URL बनाएं
                val m3u8Url = "$currentMainUrl/m3u8/$videoId/master.m3u8"
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
            
            // पुराना पैटर्न भी ट्राय करें
            val videoId = posterUrl.split("/").let { parts ->
                if (parts.size >= 3) parts[parts.size - 3] else null
            }
            
            if (!videoId.isNullOrEmpty()) {
                // वीडियो ID से m3u8 URL बनाएं
                val m3u8Url = "$currentMainUrl/m3u8/$videoId/master.m3u8"
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
        
        // 3. JS sniff function से ids निकालना
        val sniffScript = res.select("script").find { it.data().contains("sniff") }?.data()
        if (!sniffScript.isNullOrEmpty()) {
            val sniffPattern = Regex("sniff\\(([^)]+)\\)")
            val sniffMatch = sniffPattern.find(sniffScript)
            val sniffParams = sniffMatch?.groupValues?.getOrNull(1)?.split(",")?.map { it.replace("\"", "").trim() }
            
            if (sniffParams != null && sniffParams.size > 2) {
                val m3u8 = "$currentMainUrl/m3u8/${sniffParams[1]}/${sniffParams[2]}/master.m3u8"
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
                return
            }
        }
        
        // 4. JS packed script ढूंढना - अपडेटेड सेलेक्टर्स
        val packedScripts = res.select("script").mapNotNull { it.data() }
            .filter { it.contains("function(p,a,c,k,e,d)") }
        
        for (packedScript in packedScripts) {
            try {
                val unpacked = JsUnpacker(packedScript).unpack()
                if (unpacked != null) {
                    // विभिन्न पैटर्न्स के साथ m3u8 URL खोजें
                    val patterns = listOf(
                        "sources:\\[\\{file:[\"']([^\"']+)[\"']\\}",
                        "file:[\"']([^\"']+\\.m3u8[^\"']*)",
                        "src:[\"']([^\"']+\\.m3u8[^\"']*)",
                        "file:[\"']([^\"']+)",
                        "src:[\"']([^\"']+)"
                    )
                    
                    for (pattern in patterns) {
                        val m3u8 = Regex(pattern).find(unpacked)?.groupValues?.getOrNull(1)
                        if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    m3u8.toString(),
                                    if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.P1080.value
                                    this.headers = headers
                                }
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                // JsUnpacker फेल होने पर अगले स्क्रिप्ट पर जाएं
            }
        }
        
        // 5. सभी स्क्रिप्ट्स में वीडियो URL खोजें
        val scripts = res.select("script").mapNotNull { it.data() }
        val patterns = listOf(
            "sources:\\[\\{file:[\"']([^\"']+)[\"']\\}",
            "file:[\"']([^\"']+\\.m3u8[^\"']*)",
            "src:[\"']([^\"']+\\.m3u8[^\"']*)",
            "file:[\"']([^\"']+)",
            "src:[\"']([^\"']+)"
        )
        
        for (script in scripts) {
            for (pattern in patterns) {
                val m3u8 = Regex(pattern).find(script)?.groupValues?.getOrNull(1)
                if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            m3u8.toString(),
                            if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.P1080.value
                            this.headers = headers
                        }
                    )
                    return
                }
            }
        }
        
        // 6. iframe के अंदर iframe खोजें
        val nestedIframe = res.select("iframe").firstOrNull()?.attr("src")
        if (!nestedIframe.isNullOrEmpty()) {
            try {
                val nestedUrl = if (nestedIframe.startsWith("http")) nestedIframe else {
                    if (nestedIframe.startsWith("/")) "$currentMainUrl$nestedIframe" else "$currentMainUrl/$nestedIframe"
                }
                
                val nestedDoc = app.get(nestedUrl, headers = headers).document
                val nestedScripts = nestedDoc.select("script").mapNotNull { it.data() }
                
                for (script in nestedScripts) {
                    for (pattern in patterns) {
                        val m3u8 = Regex(pattern).find(script)?.groupValues?.getOrNull(1)
                        if (!m3u8.isNullOrEmpty() && (m3u8.contains(".m3u8") || m3u8.contains(".mp4"))) {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    m3u8.toString(),
                                    if (m3u8.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.P1080.value
                                    this.headers = headers
                                }
                            )
                            return
                        }
                    }
                }
            } catch (e: Exception) {
                // नेस्टेड iframe फेल होने पर अगले मेथड ट्राय करें
            }
        }
        
        // अगर कुछ नहीं मिला तो empty callback
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
