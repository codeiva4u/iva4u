package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovierulzhdPlugin: BasePlugin() {
    override fun load() {
        // Register main provider
        registerMainAPI(Movierulzhd())
    }
}
