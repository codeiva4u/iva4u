package com.phisher98

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MultiMoviesProviderPlugin: BasePlugin() {
    override fun load() {
        // मुख्य प्रोवाइडर रजिस्टर करना
        registerMainAPI(MultiMoviesProvider())

        // कस्टम एक्सट्रैक्टर्स रजिस्टर करना
        registerExtractorAPI(GDMIRROR())
        registerExtractorAPI(StreamHG())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(EarnVids())
    }
}
