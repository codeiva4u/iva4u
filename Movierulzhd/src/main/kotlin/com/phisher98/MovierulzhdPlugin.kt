package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.VidSrcTo

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Movierulzhd())
        registerExtractorAPI(VidSrcTo())
        registerExtractorAPI(Akamaicdn())
        registerExtractorAPI(FMX())
        registerExtractorAPI(FilemoonV2())
    }
}
