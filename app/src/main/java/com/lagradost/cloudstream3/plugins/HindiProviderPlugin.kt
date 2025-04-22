package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.HomePageType
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.extractors.*
import android.content.Context

@CloudstreamPlugin
class HindiProviderPlugin: PluginManager() {
    override fun load(context: Context) {
        // All extractors should be added in this function
        registerMainAPI(MoviesWeb())
        registerMainAPI(VegaMovies())
        registerMainAPI(AnimeKhor())
        registerMainAPI(HDHub4U())
        registerExtractor(Multimovies())
        registerExtractor(StreamHG())
        registerExtractor(RpmShare())
        registerExtractor(EarnVids())
        registerExtractor(UpnShare())
    }
} 