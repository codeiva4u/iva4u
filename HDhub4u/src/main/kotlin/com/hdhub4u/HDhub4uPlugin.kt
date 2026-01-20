package com.hdhub4u

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class HDhub4uPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(HDhub4uProvider())
        
        // Register actual hoster extractors (handle redirect chains internally)
        registerExtractorAPI(HubDrive())
        registerExtractorAPI(HubCloud())
    }
}