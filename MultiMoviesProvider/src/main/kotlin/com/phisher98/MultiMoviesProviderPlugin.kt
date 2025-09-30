package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.VidHidePro


@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        registerExtractorAPI(Multimovies())
        registerExtractorAPI(Animezia())
        registerExtractorAPI(server2())
        registerExtractorAPI(MultimoviesAIO())
        registerExtractorAPI(GDMirrorbot())
        registerExtractorAPI(Asnwish())
        registerExtractorAPI(CdnwishCom())
        registerExtractorAPI(Strwishcom())
        registerExtractorAPI(Streamcasthub())
        registerExtractorAPI(Dhcplay())
        registerExtractorAPI(server1())
        registerExtractorAPI(VidHidePro())
        // Download Links Extractors
        registerExtractorAPI(GDTOTExtractor())
        registerExtractorAPI(FilePress ())
        registerExtractorAPI(Hubcloud())
        }
}
