package com.multimovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MultimoviesPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(MultimoviesProvider())
        
        // Register all extractors
        registerExtractorAPI(Multimoviesshg())
        registerExtractorAPI(Server1UnsBio())
        registerExtractorAPI(TechInMindSpace())
        registerExtractorAPI(StreamTechInMind())
    }
}
