package com.phisher98

import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import android.content.Context

@CloudstreamPlugin
class Moviesfree9Plugin: Plugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        
        // Register main provider
        registerMainAPI(Moviesfree9())
        
        // Register Cherry extractor (using download link approach)
        registerExtractorAPI(CherryExtractor())
    }
}
