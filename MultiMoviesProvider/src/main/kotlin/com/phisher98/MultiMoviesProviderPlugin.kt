package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        
        // Register all video hosting extractors
        registerExtractorAPI(MultiMoviesShgExtractor())
        registerExtractorAPI(GdMirrorExtractor())
        registerExtractorAPI(TechInMindExtractor())
        registerExtractorAPI(StreamwishExtractor())
        registerExtractorAPI(VidHideExtractor())
        registerExtractorAPI(FilepressExtractor())
        registerExtractorAPI(GofileExtractor())
    }
}
