package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(StreamP2P())
        registerExtractorAPI(RpmShare())
        registerExtractorAPI(UpnShare())
        registerExtractorAPI(EarnVids())
        registerExtractorAPI(GDMirrorBot())
        registerExtractorAPI(TechInMind())
    }
}
