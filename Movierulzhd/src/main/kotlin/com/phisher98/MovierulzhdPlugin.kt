package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(Movierulzhd())
        
        // Note: Cherry extractor not registered as it uses protected streams
        // CloudStream's built-in extractors will handle other video hosters
    }
}
