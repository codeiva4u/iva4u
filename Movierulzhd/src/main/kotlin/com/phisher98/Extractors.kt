package com.phisher98

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.api.Log

/**
 * Utility functions for common extraction operations
 * These functions handle HTML parsing, JS unpacking, and error handling
 */
object ExtractorUtils {
    private const val TAG = "ExtractorUtils"

    /**
     * Safely retrieves an iframe source URL from a given page URL
     * @param url The page URL to fetch iframe from
     * @param referer Optional referer for the request
     * @return The iframe source URL or null if not found
     */
    suspend fun getIframeSrc(url: String, referer: String? = null): String? {
        return try {
            Log.d(TAG, "Fetching iframe from: $url")
            val doc = app.get(url, referer = referer).document
            val iframeSrc = doc.selectFirst("iframe")?.attr("src")
            
            if (iframeSrc.isNullOrBlank()) {
                Log.w(TAG, "No iframe found at: $url")
                null
            } else {
                Log.d(TAG, "Found iframe src: $iframeSrc")
                iframeSrc
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching iframe: ${e.message}")
            null
        }
    }

    /**
     * Extracts packed JavaScript content and unpacks it
     * @param url The URL containing packed JavaScript
     * @param headers Optional headers for the request
     * @return The unpacked JavaScript or null if extraction fails
     */
    suspend fun extractAndUnpackJs(url: String, headers: Map<String, String>? = null): String? {
        return try {
            Log.d(TAG, "Extracting packed JS from: $url")
            val doc = app.get(url, headers = headers ?: emptyMap()).document
            val packedJs = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            
            if (packedJs.isNullOrBlank()) {
                Log.w(TAG, "No packed JavaScript found at: $url")
                null
            } else {
                Log.d(TAG, "Found packed JS, attempting to unpack")
                JsUnpacker(packedJs).unpack()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting/unpacking JS: ${e.message}")
            null
        }
    }

    /**
     * Extracts m3u8 URL from unpacked JavaScript using regex
     * @param unpackedJs The unpacked JavaScript content
     * @param pattern Optional regex pattern to use (defaults to sources array pattern)
     * @return The extracted m3u8 URL or null if not found
     */
    fun extractM3u8FromJs(unpackedJs: String?, pattern: String = "sources:\\[\\{file:\"(.*?)\"") : String? {
        return try {
            if (unpackedJs.isNullOrBlank()) {
                Log.w(TAG, "Cannot extract m3u8: unpacked JS is null or blank")
                return null
            }
            
            Log.d(TAG, "Extracting m3u8 URL from unpacked JS")
            val m3u8Url = Regex(pattern).find(unpackedJs)?.groupValues?.getOrNull(1)
            
            if (m3u8Url.isNullOrBlank()) {
                Log.w(TAG, "No m3u8 URL found in unpacked JS")
                null
            } else {
                Log.d(TAG, "Found m3u8 URL: $m3u8Url")
                m3u8Url
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting m3u8 URL: ${e.message}")
            null
        }
    }
    
    /**
     * Extracts direct download links from movierulzhd page
     * @param url The URL to fetch download links from
     * @param referer Optional referer for the request
     * @return A list of extracted download links or empty list if none found
     */
    suspend fun extractDirectLinks(url: String, referer: String? = null): List<String> {
        return try {
            Log.d(TAG, "Extracting direct links from: $url")
            val doc = app.get(url, referer = referer).document
            val downloadLinks = doc.select("a.downloader-button[href*=download]").map { it.attr("href") }
            
            if (downloadLinks.isEmpty()) {
                Log.w(TAG, "No download links found at: $url")
                emptyList()
            } else {
                Log.d(TAG, "Found ${downloadLinks.size} download links")
                downloadLinks
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting direct links: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Determines video quality from filename
     * @param filename The filename to extract quality from
     * @return The quality as integer value
     */
    fun getQualityFromFilename(filename: String): Int {
        return when {
            filename.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
            filename.contains("720p", ignoreCase = true) -> Qualities.P720.value
            filename.contains("480p", ignoreCase = true) -> Qualities.P480.value
            filename.contains("360p", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}

/**
 * FMHD Extractor for fmhd.bar
 * Extends Filesim to extract video links
 */
class FMHD : Filesim() {
    override val name = "FMHD"
    override var mainUrl = "https://fmhd.bar/"
    override val requiresReferer = true
}

/**
 * Playonion Extractor for playonion.sbs
 * Extends Filesim to extract video links
 */
class Playonion : Filesim() {
    override val mainUrl = "https://playonion.sbs"
}

/**
 * Luluvdo Extractor for luluvdo.com
 * Extends StreamWishExtractor to extract video links
 */
class Luluvdo : StreamWishExtractor() {
    override val mainUrl = "https://luluvdo.com"
}

/**
 * Lulust Extractor for lulu.st
 * Extends StreamWishExtractor to extract video links
 */
class Lulust : StreamWishExtractor() {
    override val mainUrl = "https://lulu.st"
}

/**
 * FilemoonV2 Extractor
 * Extracts m3u8 links from Filemoon video host
 */
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
        try {
            Log.d(name, "Processing URL: $url")
            
            // Get iframe source
            val href = ExtractorUtils.getIframeSrc(url)
            if (href.isNullOrBlank()) {
                Log.e(name, "Failed to find iframe in $url")
                return
            }
            
            // Extract and unpack JS
            val headers = mapOf("Accept-Language" to "en-US,en;q=0.5", "sec-fetch-dest" to "iframe")
            val doc = app.get(href, headers = headers).document
            val scriptData = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            if (scriptData.isNullOrBlank()) {
                Log.e(name, "Failed to find packed JS in $href")
                return
            }
            
            // Unpack JavaScript
            val unpackedJs = JsUnpacker(scriptData).unpack()
            if (unpackedJs.isNullOrBlank()) {
                Log.e(name, "Failed to unpack JS from $href")
                return
            }
            
            // Extract m3u8 URL
            val m3u8 = ExtractorUtils.extractM3u8FromJs(unpackedJs)
            if (m3u8.isNullOrBlank()) {
                Log.e(name, "Failed to extract m3u8 URL from unpacked JS")
                return
            }
            
            // Invoke callback with extracted link
            Log.d(name, "Successfully extracted m3u8 URL: $m3u8")
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    m3u8,
                    url,
                    Qualities.P1080.value,
                    type = ExtractorLinkType.M3U8,
                )
            )
        } catch (e: Exception) {
            Log.e(name, "Error in getUrl: ${e.message}")
        }
    }
}

/**
 * FMX Extractor
 * Base class for extracting m3u8 links from FMX video host
 */
open class FMX : ExtractorApi() {
    override var name = "FMX"
    override var mainUrl = "https://fmx.lol"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        try {
            Log.d(name, "Processing URL: $url")
            
            // Get response
            val response = app.get(url, referer = mainUrl).document
            
            // Extract packed JS
            val extractedPack = response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data()
            if (extractedPack.isNullOrBlank()) {
                Log.e(name, "Failed to find packed JS in $url")
                return null
            }
            
            // Unpack JS
            val unpackedJs = JsUnpacker(extractedPack).unpack()
            if (unpackedJs.isNullOrBlank()) {
                Log.e(name, "Failed to unpack JS from $url")
                return null
            }
            
            // Extract m3u8 URL
            val m3u8Url = ExtractorUtils.extractM3u8FromJs(unpackedJs)
            if (m3u8Url.isNullOrBlank()) {
                Log.e(name, "Failed to extract m3u8 URL from unpacked JS")
                return null
            }
            
            // Return extracted link
            Log.d(name, "Successfully extracted m3u8 URL: $m3u8Url")
            return listOf(
                newExtractorLink(
                    this.name,
                    this.name,
                    url = m3u8Url,
                    INFER_TYPE
                ) {
                    this.referer = referer ?: ""
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            Log.e(name, "Error in getUrl: ${e.message}")
            return null
        }
    }
}

/**
 * Akamaicdn Extractor
 * Extracts video links from Akamai CDN sources using the sniff function
 */
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
        try {
            Log.d(name, "Processing URL: $url")
            
            // Default headers for request
            val headers = mapOf("user-agent" to "okhttp/4.12.0")
            
            // Get document and extract sniff function parameters
            val doc = app.get(url, referer = referer, headers = headers).document
            val sniffScript = doc.selectFirst("script:containsData(sniff\\()")?.data()
            if (sniffScript.isNullOrBlank()) {
                Log.e(name, "Failed to find sniff function in $url")
                return
            }
            
            // Extract parameters from sniff function
            val sniffParams = sniffScript.substringAfter("sniff(").substringBefore(");")
            if (sniffParams.isBlank()) {
                Log.e(name, "Failed to extract sniff parameters in $url")
                return
            }
            
            // Parse IDs from parameters
            val ids = sniffParams.split(",").map { it.replace("\"", "") }
            if (ids.size < 3) {
                Log.e(name, "Invalid sniff parameters format: $sniffParams")
                return
            }
            
            // Construct m3u8 URL
            val m3u8 = "$mainUrl/m3u8/${ids[1]}/${ids[2]}/master.txt?s=1&cache=1"
            Log.d(name, "Generated m3u8 URL: $m3u8")
            
            // Invoke callback with extracted link
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
        } catch (e: Exception) {
            Log.e(name, "Error in getUrl: ${e.message}")
        }
    }
}

/**
 * MovierulzDirect Extractor
 * Extracts direct download links from Movierulzhd pages
 */
class MovierulzDirect : ExtractorApi() {
    override val name = "MovierulzDirect"
    override val mainUrl = "https://1movierulzhd.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Processing URL: $url")
            
            // Get document
            val doc = app.get(url, referer = referer).document
            
            // Extract direct download links
            val downloadLinks = ExtractorUtils.extractDirectLinks(url, referer)
            if (downloadLinks.isEmpty()) {
                Log.e(name, "No download links found at $url")
                return
            }
            
            // Process each download link
            downloadLinks.forEachIndexed { index, link ->
                try {
                    // Extract quality from URL or filename
                    val fileName = link.substringAfterLast("title=").substringBefore(".mp4")
                    val quality = ExtractorUtils.getQualityFromFilename(fileName)
                    
                    // Generate a descriptive name
                    val displayName = if (downloadLinks.size > 1) {
                        "$name [Source ${index + 1}]"
                    } else {
                        name
                    }
                    
                    Log.d(name, "Found link: $link with quality: $quality")
                    
                    // Invoke callback with extracted link
                    callback.invoke(
                        ExtractorLink(
                            displayName,
                            displayName,
                            link,
                            url,
                            quality,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                } catch (e: Exception) {
                    Log.e(name, "Error processing link $link: ${e.message}")
                }
            }
            
            // Also check for embedded watch online players
            val watchOnlineLinks = doc.select("a.downloader-button[href*=upn]").map { it.attr("href") }
            
            // Process watch online links if available
            watchOnlineLinks.forEach { watchLink ->
                try {
                    Log.d(name, "Found watch online link: $watchLink")
                    loadExtractor(watchLink, url, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Error processing watch online link $watchLink: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(name, "Error in getUrl: ${e.message}")
        }
    }
}