package com.megix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

// PixelDrain Extractor - Simple and reliable
class PixelDrain : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback, callback)
    }
}

// HubCloud Extractor - Simple direct links only
open class HubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.one"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return
        
        val doc = app.get(url).document
        
        // Find all download links - simple approach
        doc.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val text = link.text()
            
            when {
                href.contains("pixeldrain") -> {
                    loadExtractor(href, referer, subtitleCallback, callback)
                }
                text.matches(Regex("(?i).*(server|download|gbps|fsl|zipdisk).*")) && 
                href.matches(Regex("https?://[\\w.-]+/.*")) -> {
                    loadExtractor(href, referer, subtitleCallback, callback)
                }
            }
        }
    }
}

// GDFlix Extractor - Simple direct links only
open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!url.startsWith("http")) return
        
        val doc = app.get(url).document
        
        // Find all download links - simple approach
        doc.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val text = link.text()
            
            when {
                href.contains("pixeldrain") -> {
                    loadExtractor(href, referer, subtitleCallback, callback)
                }
                text.matches(Regex("(?i).*(instant|cloud|download|zipdisk|gofile).*")) && 
                href.matches(Regex("https?://[\\w.-]+/.*")) &&
                !href.contains("t.me") -> {  // Skip Telegram
                    loadExtractor(href, referer, subtitleCallback, callback)
                }
            }
        }
    }
}

// fastdlserver - Simple redirect handler
open class fastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://pixel.hubcdn.fans/"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback, callback)
    }
}
