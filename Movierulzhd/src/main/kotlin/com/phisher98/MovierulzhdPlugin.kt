package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Movierulzhd())
        
        // Register custom extractors
        registerExtractorAPI(Daddylive())
        registerExtractorAPI(DeadDrive())
        registerExtractorAPI(GdMirrorBot())
    }
}
