package com.phisher98

import com.lagradost.cloudstream3.extractors.MixDrop
import com.lagradost.cloudstream3.extractors.VidHidePro5
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(MultiMoviesProvider())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(MixDrop())
        // New extractors for multimovies.guru
        registerExtractorAPI(MultiMoviesGuruVidstack())
        registerExtractorAPI(MultiMoviesGuruStreamcasthub())
        registerExtractorAPI(MultiMoviesGuruFilemoon())
        registerExtractorAPI(MultiMoviesGuruGDMirrorbot())
        }
}
