package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
        
        // Register all Extractors
        registerExtractorAPI(Multimoviesshg())
        registerExtractorAPI(VidStack())  // Base VidStack
        registerExtractorAPI(RpmHub())    // VidStack subclass
        registerExtractorAPI(UnsBio())    // VidStack subclass
        registerExtractorAPI(P2pPlay())   // VidStack subclass
        registerExtractorAPI(SmoothPre())
        registerExtractorAPI(Techinmind())
    }
}
