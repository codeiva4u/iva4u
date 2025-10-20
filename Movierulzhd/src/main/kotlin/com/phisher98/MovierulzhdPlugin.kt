package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(Movierulzhd())
        
        // Register all video hosting extractors
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(VidstackExtractor())
        registerExtractorAPI(VidhideExtractor())
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(FilemoonExtractor())
        registerExtractorAPI(DoodstreamExtractor())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(MultiHostExtractor())
    }
}
