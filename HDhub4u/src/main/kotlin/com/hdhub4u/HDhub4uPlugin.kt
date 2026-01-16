package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HDhub4uPlugin : BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(HDhub4uProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(HubDrive())
        registerExtractorAPI(GadgetsWeb())
        registerExtractorAPI(HDStream4u())
        registerExtractorAPI(HubStream())
        registerExtractorAPI(HubCloud())
    }
}
