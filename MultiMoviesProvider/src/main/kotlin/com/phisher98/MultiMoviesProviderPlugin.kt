package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin



@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        registerExtractorAPI(VidHidePro())
        registerExtractorAPI(StreamWishCom())
        registerExtractorAPI(Luluvdo())
        registerExtractorAPI(LuluSt())
        registerExtractorAPI(GDToTPro())
        registerExtractorAPI(GDToTTop())
        registerExtractorAPI(FilePressStore())
        registerExtractorAPI(FilePressClick())
        registerExtractorAPI(HubCloudInk())
        registerExtractorAPI(HubCloudArt())
        registerExtractorAPI(HubCloudDad())
        registerExtractorAPI(HubCloudBz())
        registerExtractorAPI(GoFileExtractor())
    }
}
