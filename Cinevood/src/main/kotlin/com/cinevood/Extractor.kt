package com.cinevood

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

fun getBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        url.substringBefore("/", url)
    }
}

suspend fun getLatestUrl(url: String, source: String): String {
    return try {
        val link = JSONObject(
            app.get("https://raw.githubusercontent.com/codeiva4u/Utils-repo/refs/heads/main/urls.json").text
        ).optString(source)
        if (link.isNullOrEmpty()) getBaseUrl(url) else link
    } catch (_: Exception) {
        getBaseUrl(url)
    }
}
class OxxFileExtractor : ExtractorApi() {
    override val name = "OxxFile"
    override val mainUrl = "https://oxxfile.info"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Cinevood-OxxFile", "Extracting from: $url")
            
            // Method 1: Direct fetch और HTML parsing
            val document = app.get(url).document
            
            // Extract all links from page body
            val allLinks = document.select("a[href], button[data-url], [data-link]").mapNotNull { element ->
                element.attr("href").ifBlank { 
                    element.attr("data-url").ifBlank { 
                        element.attr("data-link") 
                    }
                }
            }.filter { it.isNotBlank() }
            
            Log.d("Cinevood-OxxFile", "Found ${allLinks.size} links in HTML")
            
            // Method 2: HTML body से regex से URLs extract करें
            val bodyHtml = document.body().html()
            
            // HubCloud URLs खोजें
            val hubcloudRegex = Regex("""(https?://[^\s"'<>]*hubcloud[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            val hubcloudUrls = hubcloudRegex.findAll(bodyHtml).map { it.groupValues[1] }.toSet()
            
            if (hubcloudUrls.isNotEmpty()) {
                Log.d("Cinevood-OxxFile", "Found ${hubcloudUrls.size} HubCloud URLs")
                hubcloudUrls.forEach { hubcloudUrl ->
                    val cleanUrl = hubcloudUrl.replace("\\u002F", "/").replace("\\", "")
                    Log.d("Cinevood-OxxFile", "Processing HubCloud: $cleanUrl")
                    HubCloudExtractor().getUrl(cleanUrl, url, subtitleCallback, callback)
                }
            }
            
            // Filepress URLs खोजें
            val filepressRegex = Regex("""(https?://[^\s"'<>]*filepress[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            val filepressUrls = filepressRegex.findAll(bodyHtml).map { it.groupValues[1] }.toSet()
            
            if (filepressUrls.isNotEmpty()) {
                Log.d("Cinevood-OxxFile", "Found ${filepressUrls.size} Filepress URLs")
                filepressUrls.forEach { filepressUrl ->
                    val cleanUrl = filepressUrl.replace("\\u002F", "/").replace("\\", "")
                    Log.d("Cinevood-OxxFile", "Processing Filepress: $cleanUrl")
                    FilepressExtractor().getUrl(cleanUrl, url, subtitleCallback, callback)
                }
            }
            
            // Method 3: Script tags में embedded URLs
            document.select("script").forEach { script ->
                val scriptContent = script.html()
                
                // HubCloud in scripts
                hubcloudRegex.findAll(scriptContent).forEach { match ->
                    val hubUrl = match.groupValues[1].replace("\\u002F", "/").replace("\\", "")
                    if (hubUrl.startsWith("http")) {
                        Log.d("Cinevood-OxxFile", "Found HubCloud in script: $hubUrl")
                        HubCloudExtractor().getUrl(hubUrl, url, subtitleCallback, callback)
                    }
                }
                
                // Filepress in scripts  
                filepressRegex.findAll(scriptContent).forEach { match ->
                    val fpUrl = match.groupValues[1].replace("\\u002F", "/").replace("\\", "")
                    if (fpUrl.startsWith("http")) {
                        Log.d("Cinevood-OxxFile", "Found Filepress in script: $fpUrl")
                        FilepressExtractor().getUrl(fpUrl, url, subtitleCallback, callback)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("Cinevood-OxxFile", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ==================== HubCloud Extractor ====================
class HubCloudExtractor : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Cinevood-HubCloud", "Extracting from: $url")

            val document = app.get(url).document

            // Check for "Generate Direct Download Link" or similar button
            val redirectButton =
                document.selectFirst("a#download, a:contains(Generate Direct Download Link)")
            val redirectUrl = redirectButton?.attr("href")

            val targetUrl = if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                Log.d("Cinevood-HubCloud", "Found redirect URL: $redirectUrl")
                redirectUrl
            } else {
                url
            }

            // 2. Fetch the target page (gamerxyt)
            val targetDoc = app.get(targetUrl).document

            targetDoc.select("a[href]").forEach { element ->
                val href = element.attr("href")
                val text = element.text()

                if (href.isBlank() || href.startsWith("javascript:")) return@forEach

                // Classify link based on text or domain
                when {
                    text.contains("FSL", ignoreCase = true) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud-FSL",
                                "HubCloud-FSL",
                                href,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }

                    href.contains("pixeldrain", ignoreCase = true) || text.contains(
                        "Pixel",
                        ignoreCase = true
                    ) -> {
                        val pdUrl = if (href.contains("/u/")) {

                            val id = href.substringAfter("/u/")
                            "https://pixeldrain.com/api/file/$id"
                        } else {
                            href
                        }
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud-PixelDrain",
                                "HubCloud-PixelDrain",
                                pdUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }

                    text.contains("ZipDisk", ignoreCase = true) || href.contains(
                        "workers.dev",
                        ignoreCase = true
                    ) -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud-ZipDisk",
                                "HubCloud-ZipDisk",
                                href,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }

                    href.endsWith(".mkv") || href.endsWith(".mp4") -> {
                        callback.invoke(
                            newExtractorLink(
                                "HubCloud-Direct",
                                "HubCloud-Direct",
                                href,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("Cinevood-HubCloud", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}

// ==================== Filepress Extractor ====================
class FilepressExtractor : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://new3.filepress.cloud/"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d("Cinevood-Filepress", "Extracting from: $url")

            val videoUrl = if (url.contains("/file/")) {
                url.replace("/file/", "/video/")
            } else {
                url
            }
            
            Log.d("Cinevood-Filepress", "Video Page URL: $videoUrl")
            
             val request = app.get(
                videoUrl,
                referer = referer ?: mainUrl,
                interceptor = WebViewResolver(
                    // Capture common video patterns
                    Regex("""(master\.m3u8|playlist\.m3u8|index\.m3u8|.*\.mp4|.*\.mkv|.*streamwish.*|.*dood.*)""")
                )
            )
            
            val capturedUrl = request.url
            Log.d("Cinevood-Filepress", "WebView captured: $capturedUrl")
            
            if (capturedUrl.contains("m3u8") || capturedUrl.endsWith(".mp4")) {
                 callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [Direct]",
                        capturedUrl,
                        if (capturedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: videoUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else if (capturedUrl.contains("streamwish") || capturedUrl.contains("dood")) {
                // If we captured a hoster URL, delegate to its extractor
                loadExtractor(capturedUrl, videoUrl, subtitleCallback, callback)
            } else {

                app.get(videoUrl).document
            }

        } catch (e: Exception) {
            Log.e("Cinevood-Filepress", "Extraction error: ${e.message}")
            e.printStackTrace()
        }
    }
}
