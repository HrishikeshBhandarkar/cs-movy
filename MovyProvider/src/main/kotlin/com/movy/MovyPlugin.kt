package com.movy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovyPlugin : Plugin() {
    override fun load(context: Context) {
        runCatching { NetMirrorStore.init(context) }
        runCatching { NetMirrorSettings.init(context) }
        runCatching { MovyAdvisoryOverlay.install(context) }
        runCatching { NetMirrorStatusOverlay.install(context) }
        registerMainAPI(MovyProvider())
        registerMainAPI(NetMirrorProvider())
        openSettings = { ctx -> NetMirrorSettings.show(ctx) }
    }

    override fun beforeUnload() {
        runCatching { NetMirrorStatusOverlay.release() }
        runCatching { MovyAdvisoryOverlay.release() }
    }
}
