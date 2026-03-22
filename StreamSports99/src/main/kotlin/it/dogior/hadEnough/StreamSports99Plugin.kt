package it.dogior.hadEnough

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamSports99Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamSports99())
    }
}
