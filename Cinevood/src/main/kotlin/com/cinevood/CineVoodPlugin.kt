package com.cinevood

import com.lagradost.cloudstream3.Prerelease
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineVoodPlugin: BasePlugin() {
    @OptIn(Prerelease::class)
    override fun load() {
        // Register main provider
        registerMainAPI(CinevoodProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(Hubcloud())
        registerExtractorAPI(Filepress())
        // OxxFile is handled by CloudStream3's built-in extractors

    }
}
