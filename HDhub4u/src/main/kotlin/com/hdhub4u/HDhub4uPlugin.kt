package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * HDhub4u CloudStream Plugin
 * 
 * ACTIVE EXTRACTORS (Direct Downloads Only):
 * - HubCloud: Main download extractor (gamerxyt.com CDN)
 * - Hubdrive: Redirects to HubCloud
 * - Hblinks/FourKHDHub: Download aggregator pages
 * - HUBCDN: hubcdn.fans instant downloads
 * - PixelDrainDev: pixeldrain.dev direct downloads
 * 
 * REMOVED EXTRACTORS (Streaming Only - Causes Buffering):
 * - Hubstream: hubstream.art (M3U8 only)
 * - HDStream4u: hdstream4u.com (M3U8 + reCAPTCHA)
 * - Hubcdnn: Returned M3U8 streams
 */
@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        // Main Provider
        registerMainAPI(HDhub4uProvider())
        
        // Download Extractors (Direct Downloads Only)
        registerExtractorAPI(HubCloud())      // Main: gamerxyt.com CDN links
        registerExtractorAPI(Hubdrive())      // Redirects to HubCloud
        registerExtractorAPI(Hblinks())       // Download aggregator
        registerExtractorAPI(FourKHDHub())    // 4khdhub aggregator
        registerExtractorAPI(HUBCDN())        // hubcdn.fans instant downloads
        registerExtractorAPI(PixelDrainDev()) // pixeldrain.dev
        registerExtractorAPI(Hubstreamdad())  // hblinks variant
        
        // REMOVED: Streaming-only extractors (cause buffering issues)
        // registerExtractorAPI(HDStream4u())  // M3U8 + reCAPTCHA
        // registerExtractorAPI(Hubstream())   // M3U8 only
        // registerExtractorAPI(Hubcdnn())     // Returned M3U8 streams
    }
}
