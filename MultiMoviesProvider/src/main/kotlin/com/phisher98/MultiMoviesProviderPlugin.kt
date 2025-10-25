package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        
        // Register all working video hosting extractors
        registerExtractorAPI(StreamWishExtractor())  // StreamWish/StreamHG
        registerExtractorAPI(VidHideExtractor())     // VidHide/EarnVids
        registerExtractorAPI(StreamP2PExtractor())   // StreamP2P
        registerExtractorAPI(RpmShareExtractor())    // RpmShare
        registerExtractorAPI(UpnShareExtractor())    // UpnShare
    }
}
