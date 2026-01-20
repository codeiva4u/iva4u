package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(HDhub4uProvider())
        
        // Register all extractors
        registerExtractorAPI(VidStack())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(Hubcdnn())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(HubStream())
        registerExtractorAPI(HdStream4u())
    }
}
