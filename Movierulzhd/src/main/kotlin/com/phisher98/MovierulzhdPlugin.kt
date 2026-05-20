package com.phisher98

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class MovierulzhdPlugin: Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        
        // Initialize ActivityTracker to track current foreground activity
        (context.applicationContext as? android.app.Application)?.let {
            com.phisher98.stealth.ActivityTracker.init(it)
        }
        
        // Register main provider
        registerMainAPI(Movierulzhd())
        
        // Register Cherry extractor (using download link approach)
        registerExtractorAPI(CherryExtractor())
    }
}
