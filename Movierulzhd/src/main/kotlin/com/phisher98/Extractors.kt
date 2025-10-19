package com.phisher98

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import okhttp3.FormBody
import org.json.JSONObject

open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            val response = app.get(url, referer = referer ?: mainUrl).document
            val extractedpack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            
            if (extractedpack.isNullOrEmpty()) {
                Log.e("FMX", "No packed script found")
                return null
            }
            
            JsUnpacker(extractedpack).unpack()?.let { unPacked ->
                Regex("sources:\\[\\{file:[\"'](.*?)[\"']").find(unPacked)?.groupValues?.get(1)?.let { link ->
                    return listOf(
                        newExtractorLink(
                            this.name,
                            this.name,
                            url = link,
                            if (link.contains(".m3u8")) INFER_TYPE else null
                        ) {
                            this.referer = referer ?: mainUrl
                            this.quality = getQualityFromString(link)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("FMX", "Error: ${e.message}")
        }
        return null
    }
    
    private fun getQualityFromString(url: String): Int {
        return when {
            url.contains("1080") || url.contains("1920") -> Qualities.P1080.value
            url.contains("720") || url.contains("1280") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

open class Akamaicdn : ExtractorApi() {
    override val name = "Akamaicdn"
    override val mainUrl = "https://molop.art"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "user-agent" to "okhttp/4.12.0",
                "referer" to (referer ?: mainUrl)
            )
            
            val res = app.get(url, referer = referer, headers = headers).document
            val sniffScript = res.selectFirst("script:containsData(sniff\\()")
                ?.data()
                ?.substringAfter("sniff(")
                ?.substringBefore(");") ?: return
            
            val cleaned = sniffScript.replace(Regex("\\[.*?\\]"), "")
            val regex = Regex("\"(.*?)\"")
            val args = regex.findAll(cleaned).map { it.groupValues[1].trim() }.toList()
            
            if (args.size < 3) {
                Log.e("Akamaicdn", "Insufficient arguments extracted from sniff script")
                return
            }
            
            val token = args.lastOrNull().orEmpty()
            val m3u8 = "$mainUrl/m3u8/${args[1]}/${args[2]}/master.txt?s=1&cache=1&plt=$token"
            
            M3u8Helper.generateM3u8(name, m3u8, mainUrl, headers = headers).forEach(callback)
        } catch (e: Exception) {
            Log.e("Akamaicdn", "Error: ${e.message}")
        }
    }
}

@Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://new10.gdflix.dad"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        source: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val newUrl = try {
            app.get(url)
                .document
                .selectFirst("meta[http-equiv=refresh]")
                ?.attr("content")
                ?.substringAfter("url=")
        } catch (e: Exception) {
            Log.e("Error", "Failed to fetch redirect: ${e.localizedMessage}")
            return
        } ?: url

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")

        document.select("div.text-center a").amap { anchor ->
            val text = anchor.select("a").text()

            when {
                text.contains("DIRECT DL",ignoreCase = true) -> {
                    val link = anchor.attr("href")
                    val linkName = if (fileName.contains(".mp4", ignoreCase = true) || 
                                       fileName.contains(".mkv", ignoreCase = true) ||
                                       fileName.contains(".avi", ignoreCase = true)) {
                        "$source GDFlix[Stream/Download]"
                    } else {
                        "$source GDFlix[Direct]"
                    }
                    callback.invoke(
                        newExtractorLink("$source GDFlix", "$linkName [$fileSize]", link, if (link.contains(".m3u8")) INFER_TYPE else null) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                text.contains("Index Links",ignoreCase = true) -> {
                    try {
                        val link = anchor.attr("href")
                        app.get("https://new6.gdflix.dad$link").document
                            .select("a.btn.btn-outline-info").amap { btn ->
                                val serverUrl = "https://new6.gdflix.dad" + btn.attr("href")
                                app.get(serverUrl).document
                                    .select("div.mb-4 > a").amap { sourceAnchor ->
                                        val sourceurl = sourceAnchor.attr("href")
                                        callback.invoke(
                                            newExtractorLink("$source GDFlix", "$source GDFlix[Index Stream] [$fileSize]", sourceurl, if (sourceurl.contains(".m3u8")) INFER_TYPE else null) {
                                                this.quality = getIndexQuality(fileName)
                                            }
                                        )
                                    }
                            }
                    } catch (e: Exception) {
                        Log.d("Index Links", e.toString())
                    }
                }

                text.contains("DRIVEBOT",ignoreCase = true) -> {
                    try {
                        val driveLink = anchor.attr("href")
                        val id = driveLink.substringAfter("id=").substringBefore("&")
                        val doId = driveLink.substringAfter("do=").substringBefore("==")
                        val baseUrls = listOf("https://drivebot.sbs", "https://drivebot.cfd")

                        baseUrls.amap { baseUrl ->
                            val indexbotLink = "$baseUrl/download?id=$id&do=$doId"
                            val indexbotResponse = app.get(indexbotLink, timeout = 100L)

                            if (indexbotResponse.isSuccessful) {
                                val cookiesSSID = indexbotResponse.cookies["PHPSESSID"]
                                val indexbotDoc = indexbotResponse.document

                                val token = Regex("""formData\.append\('token', '([a-f0-9]+)'\)""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val postId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""")
                                    .find(indexbotDoc.toString())?.groupValues?.get(1).orEmpty()

                                val requestBody = FormBody.Builder()
                                    .add("token", token)
                                    .build()

                                val headers = mapOf("Referer" to indexbotLink)
                                val cookies = mapOf("PHPSESSID" to "$cookiesSSID")

                                val downloadLink = app.post(
                                    "$baseUrl/download?id=$postId",
                                    requestBody = requestBody,
                                    headers = headers,
                                    cookies = cookies,
                                    timeout = 100L
                                ).text.let {
                                    Regex("url\":\"(.*?)\"").find(it)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
                                }

                                callback.invoke(
                                    newExtractorLink("$source GDFlix", "$source GDFlix[DriveBot Stream] [$fileSize]", downloadLink, if (downloadLink.contains(".m3u8")) INFER_TYPE else null) {
                                        this.referer = baseUrl
                                        this.quality = getIndexQuality(fileName)
                                    }
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DriveBot", e.toString())
                    }
                }

                text.contains("Instant DL",ignoreCase = true) -> {
                    try {
                        val instantLink = anchor.attr("href")
                        val link = app.get(instantLink, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()

                        callback.invoke(
                            newExtractorLink("$source GDFlix", "$source GDFlix[Instant Stream] [$fileSize]", link, if (link.contains(".m3u8")) INFER_TYPE else null) {
                                this.quality = getIndexQuality(fileName)
                            }
                        )
                    } catch (e: Exception) {
                        Log.d("Instant DL", e.toString())
                    }
                }


                text.contains("GoFile",ignoreCase = true) -> {
                    try {
                        app.get(anchor.attr("href")).document
                            .select(".row .row a").amap { gofileAnchor ->
                                val link = gofileAnchor.attr("href")
                                if (link.contains("gofile")) {
                                    Gofile().getUrl(link, "", subtitleCallback, callback)
                                }
                            }
                    } catch (e: Exception) {
                        Log.d("Gofile", e.toString())
                    }
                }

                text.contains("PixelDrain",ignoreCase = true) || text.contains("Pixel",ignoreCase = true)-> {
                    val pixelLink = anchor.attr("href")
                    callback.invoke(
                        newExtractorLink(
                            "$source GDFlix",
                            "$source GDFlix[PixelDrain Stream] [$fileSize]",
                            pixelLink,
                            if (pixelLink.contains(".m3u8")) INFER_TYPE else null
                        ) { 
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }

                else -> {
                    Log.d("Error", "No Server matched")
                }
            }
        }

        // Cloudflare backup links
        try {
            val types = listOf("type=1", "type=2")
            types.map { type ->
                val sourceurl = app.get("${newUrl.replace("file", "wfile")}?$type")
                    .document.select("a.btn-success").attr("href")

                if (source?.isNotEmpty() == true) {
                    callback.invoke(
                        newExtractorLink("$source GDFlix", "$source GDFlix[CF Stream] [$fileSize]", sourceurl, if (sourceurl.contains(".m3u8")) INFER_TYPE else null) {
                            this.quality = getIndexQuality(fileName)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.d("CF", e.toString())
        }
    }
}


class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {
            val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
            val responseText = app.post("$mainApi/accounts").text
            val json = JSONObject(responseText)
            val token = json.getJSONObject("data").getString("token")

            val globalJs = app.get("$mainUrl/dist/js/global.js").text
            val wt = Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""")
                .find(globalJs)?.groupValues?.getOrNull(1) ?: return

            val responseTextfile = app.get(
                "$mainApi/contents/$id?wt=$wt",
                headers = mapOf("Authorization" to "Bearer $token")
            ).text

            val fileDataJson = JSONObject(responseTextfile)

            val data = fileDataJson.getJSONObject("data")
            val children = data.getJSONObject("children")
            val firstFileId = children.keys().asSequence().first()
            val fileObj = children.getJSONObject(firstFileId)

            val link = fileObj.getString("link")
            val fileName = fileObj.getString("name")
            val fileSize = fileObj.getLong("size")

            val sizeFormatted = if (fileSize < 1024L * 1024 * 1024) {
                "%.2f MB".format(fileSize / 1024.0 / 1024)
            } else {
                "%.2f GB".format(fileSize / 1024.0 / 1024 / 1024)
            }
            
            // Check if it's a streamable video file
            val isVideoFile = fileName.endsWith(".mp4", ignoreCase = true) ||
                             fileName.endsWith(".mkv", ignoreCase = true) ||
                             fileName.endsWith(".avi", ignoreCase = true) ||
                             fileName.endsWith(".mov", ignoreCase = true) ||
                             fileName.endsWith(".webm", ignoreCase = true) ||
                             fileName.endsWith(".m3u8", ignoreCase = true)
            
            val linkLabel = if (isVideoFile) {
                "Gofile[Stream/Download] [$sizeFormatted]"
            } else {
                "Gofile[Download] [$sizeFormatted]"
            }

            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    linkLabel,
                    link,
                    if (fileName.endsWith(".m3u8", ignoreCase = true)) INFER_TYPE else null
                ) {
                    this.quality = getQuality(fileName)
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        } catch (e: Exception) {
            Log.e("Gofile", "Error occurred: ${e.message}")
        }
    }

    private fun getQuality(fileName: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(fileName ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}

fun getIndexQuality(str: String?): Int {
    return Regex("(\\d{3,4})[pP]").find(str ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Qualities.Unknown.value
}

open class Cherry : ExtractorApi() {
    override val name = "Cherry"
    override val mainUrl = "https://cherry.upns.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // Extract video ID - handle both #id and #id&dl=1 formats
            val videoId = url.substringAfter("#").substringBefore("&").takeIf { it.isNotEmpty() } ?: run {
                Log.e("Cherry", "No video ID found in URL: $url")
                return
            }
            
            Log.d("Cherry", "Extracting video with ID: $videoId from URL: $url")
            
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to (referer ?: mainUrl),
                "Accept" to "*/*",
                "Origin" to mainUrl,
                "Accept-Language" to "en-US,en;q=0.9"
            )
            
            // Method 1: Try API endpoint first (most reliable)
            try {
                val apiUrl = "$mainUrl/api/v1/video?id=$videoId&w=1440&h=900&r="
                Log.d("Cherry", "Trying API: $apiUrl")
                val apiResponse = app.get(apiUrl, headers = headers, referer = referer ?: mainUrl)
                
                if (apiResponse.isSuccessful) {
                    val responseText = apiResponse.text
                    Log.d("Cherry", "API Response received: ${responseText.take(200)}")
                    
                    val apiJson = apiResponse.parsed<CherryApiResponse>()
                    if (apiJson.sources.isNullOrEmpty()) {
                        Log.e("Cherry", "API returned no sources")
                    } else {
                        Log.d("Cherry", "Found ${apiJson.sources.size} sources from API")
                        apiJson.sources.forEach { source ->
                            if (source.file.isNotEmpty()) {
                                Log.d("Cherry", "Processing source: ${source.file}")
                                try {
                                    when {
                                        source.file.contains(".m3u8") || source.file.contains("master.txt") -> {
                                            Log.d("Cherry", "Generating M3U8 links for: ${source.file}")
                                            M3u8Helper.generateM3u8(
                                                name,
                                                source.file,
                                                referer ?: mainUrl,
                                                headers = headers
                                            ).forEach(callback)
                                        }
                                        source.file.contains(".mp4") -> {
                                            Log.d("Cherry", "Adding MP4 link: ${source.file}")
                                            callback.invoke(
                                                newExtractorLink(
                                                    name,
                                                    "${name} ${source.label ?: ""}",
                                                    source.file,
                                                    null
                                                ) {
                                                    this.referer = referer ?: mainUrl
                                                    this.quality = getQualityFromLabel(source.label)
                                                }
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("Cherry API", "Error processing source: ${e.message}")
                                }
                            }
                        }
                        return
                    }
                } else {
                    Log.e("Cherry", "API request failed with status: ${apiResponse.code}")
                }
            } catch (e: Exception) {
                Log.e("Cherry", "API method failed: ${e.message}")
                e.printStackTrace()
            }
            
            // Method 2: Fallback to HTML parsing
            val doc = app.get(url, headers = headers).document
            val pageHtml = doc.toString()
            
            val patterns = listOf(
                Regex("""["']?(https?://[^"'\s]+\.m3u8[^"'\s]*)["']?"""),
                Regex("""src["']?\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""source["']?\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""file["']?\s*[=:]\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["'](https?://[^"']*master\.txt[^"']*)["']"""),
                Regex("""["'](https?://[^"']+/m3u8/[^"']+)["']"""),
                Regex("""https?://[^\s"']+/[^\s"']+\.m3u8""")
            )
            
            val foundUrls = mutableSetOf<String>()
            
            patterns.forEach { regex ->
                regex.findAll(pageHtml).forEach { match ->
                    val videoUrl = match.groupValues.getOrNull(1)?.trim() 
                        ?: match.value.trim().removeSurrounding("'").removeSurrounding("\"")
                    
                    if (videoUrl.isNotEmpty() && 
                        (videoUrl.contains(".m3u8") || videoUrl.contains("master.txt") || videoUrl.contains("/m3u8/")) &&
                        videoUrl.startsWith("http") &&
                        !foundUrls.contains(videoUrl)) {
                        
                        foundUrls.add(videoUrl)
                        
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                referer ?: mainUrl,
                                headers = headers
                            ).forEach(callback)
                        } catch (e: Exception) {
                            Log.d("Cherry M3U8", "Error with URL $videoUrl: ${e.message}")
                        }
                    }
                }
            }
            
            if (foundUrls.isEmpty()) {
                Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").findAll(pageHtml).forEach { match ->
                    val mp4Url = match.groupValues.getOrNull(1) ?: return@forEach
                    if (mp4Url.isNotEmpty() && !mp4Url.contains("poster") && mp4Url.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name MP4",
                                mp4Url,
                                null
                            ) {
                                this.referer = referer ?: mainUrl
                                this.quality = getQualityFromName(mp4Url)
                            }
                        )
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("Cherry", "Error: ${e.message}")
        }
    }
    
    private fun getQualityFromLabel(label: String?): Int {
        return when {
            label == null -> Qualities.Unknown.value
            label.contains("2160") || label.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            label.contains("1080") -> Qualities.P1080.value
            label.contains("720") -> Qualities.P720.value
            label.contains("480") -> Qualities.P480.value
            label.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    private fun getQualityFromName(url: String): Int {
        return when {
            url.contains("2160") || url.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080") -> Qualities.P1080.value
            url.contains("720") -> Qualities.P720.value
            url.contains("480") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
    
    data class CherrySource(
        @com.fasterxml.jackson.annotation.JsonProperty("file") val file: String,
        @com.fasterxml.jackson.annotation.JsonProperty("label") val label: String? = null,
        @com.fasterxml.jackson.annotation.JsonProperty("type") val type: String? = null
    )
    
    data class CherryApiResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("sources") val sources: List<CherrySource>? = null
    )
}
