package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        // Main Provider
        registerMainAPI(HDhub4uProvider())
        
        // Download Extractors (Direct Downloads Only)
        registerExtractorAPI(HubCloud())      // Main: gamerxyt.com CDN links
        registerExtractorAPI(Hubdrive())      // Redirects to HubCloud
        registerExtractorAPI(Hblinks())       // Download aggregator

        registerExtractorAPI(HUBCDN())        // hubcdn.fans instant downloads
        registerExtractorAPI(PixelDrainDev()) // pixeldrain.dev
        registerExtractorAPI(Hubstreamdad())  // hblinks variant
        
     
    }
}
