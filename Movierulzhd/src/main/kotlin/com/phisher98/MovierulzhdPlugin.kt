package com.phisher98

// Import new extractors
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Movierulzhd())
        // registerExtractorAPI(FilemoonV2()) // Commented out as FilemoonV2 is not defined
        // New extractors for 1movierulzhd.lol
        registerExtractorAPI(MovieRulzVidstack())
        registerExtractorAPI(MovieRulzStreamcasthub())
        registerExtractorAPI(MovieRulzFilemoon())
        registerExtractorAPI(MovieRulzAkamaicdn())
        registerExtractorAPI(MovieRulzMocdn())
    }
}
