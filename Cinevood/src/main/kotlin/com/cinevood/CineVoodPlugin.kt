package com.cinevood

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineVoodPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(CinevoodProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(OxxFileExtractor())
        registerExtractorAPI(HubCloudExtractor())
        registerExtractorAPI(FilepressExtractor())
    }
}
