package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
        
        // Register all Extractors
        registerExtractorAPI(Multimoviesshg())
        registerExtractorAPI(UnsBio())
        registerExtractorAPI(RpmHub())
        registerExtractorAPI(P2pPlay())
        registerExtractorAPI(SmoothPre())
        registerExtractorAPI(Techinmind())
    }
}
