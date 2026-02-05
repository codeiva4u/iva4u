package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
        
        // Register all Extractors
        registerExtractorAPI(TechInMindStream())
        registerExtractorAPI(SSNTechInMind())
        registerExtractorAPI(DDNIQSmartGames())
        registerExtractorAPI(ProIQSmartGames())
        registerExtractorAPI(MultiMoviesSHG())
        registerExtractorAPI(RpmHub())
        registerExtractorAPI(UnsBio())
        registerExtractorAPI(P2pPlay())
        registerExtractorAPI(GDMirrorDownload())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(GDFlix())
    }
}
