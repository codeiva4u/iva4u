package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(MultiMoviesProvider())
        
        // Register all MultiMovies video hoster extractors
        registerExtractorAPI(StreamHGExtractor())
        registerExtractorAPI(StreamP2PExtractor())
        registerExtractorAPI(RpmShareExtractor())
        registerExtractorAPI(UpnShareExtractor())
        registerExtractorAPI(EarnVidsExtractor())
        registerExtractorAPI(DDNDownloadExtractor())
    }
}
