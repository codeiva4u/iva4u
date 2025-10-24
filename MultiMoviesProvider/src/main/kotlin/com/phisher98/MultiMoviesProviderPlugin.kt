package com.phisher98

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.app


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(StreamHGExtractor())
        registerExtractorAPI(RpmShareExtractor())
        registerExtractorAPI(UpnShareExtractor())
        registerExtractorAPI(SmoothPreExtractor())
        registerExtractorAPI(GTXGamerExtractor())
    }
}
