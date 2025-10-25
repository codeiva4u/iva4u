package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(MultiMoviesProvider())
        
        // Register all video hoster extractors
        registerExtractorAPI(GofileExtractor())
        registerExtractorAPI(FilePressExtractor())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(StreamP2PExtractor())
        registerExtractorAPI(VidHideExtractor())
        registerExtractorAPI(GDMirrorBotExtractor())
        registerExtractorAPI(LoadMyFileExtractor())
    }
}