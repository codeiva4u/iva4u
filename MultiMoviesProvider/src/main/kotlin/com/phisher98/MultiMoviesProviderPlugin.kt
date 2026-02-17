package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
        
        // Register all Extractors
        registerExtractorAPI(GDMirrorExtractor())
        registerExtractorAPI(StreamHGExtractor())
        registerExtractorAPI(RpmShareExtractor())
        registerExtractorAPI(UpnShareExtractor())
        registerExtractorAPI(StreamP2pExtractor())
    }
}
