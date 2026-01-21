package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(HDhub4uProvider())
        
        // Register extractors (only those used on HDhub4u)
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(HUBCDN())
        registerExtractorAPI(Hubcdnn())
    }
}

