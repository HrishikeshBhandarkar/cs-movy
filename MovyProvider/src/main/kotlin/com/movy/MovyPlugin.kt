package com.movy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovyPlugin : Plugin() {
    override fun load(context: Context) {
        NetMirrorStore.init(context)
        registerMainAPI(MovyProvider())
        registerMainAPI(NetMirrorProvider())
        openSettings = { ctx -> NetMirrorSettings.show(ctx) }
    }

    override fun beforeUnload() {
        MovyAdvisoryOverlay.release()
    }
}
