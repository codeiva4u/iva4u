package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(VidStackIva())
        registerExtractorAPI(VidhideIva())
        registerExtractorAPI(StreamWishIva())
        registerExtractorAPI(VidHideComIva())
        registerExtractorAPI(FilePressIva())
        registerExtractorAPI(GofileIva())
        registerExtractorAPI(GDTotIva())
        registerExtractorAPI(BuzzheavierIva())


    }
}
