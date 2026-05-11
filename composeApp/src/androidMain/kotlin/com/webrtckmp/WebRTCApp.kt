package com.webrtckmp

import android.app.Application
import android.content.Context
import org.webrtc.PeerConnectionFactory

class WebRTCApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        // PeerConnectionFactory.initialize() loads native libraries and registers
        // global state — it can only be called ONCE per process. Doing it here
        // (instead of inside WebRTCManager) means re-joining a call after
        // reset() won't crash with "Already initialized".
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
