package com.multimovies

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.BasePlugin

@CloudstreamPlugin
class MultiMoviesPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(MultiMoviesProvider())
    }
}