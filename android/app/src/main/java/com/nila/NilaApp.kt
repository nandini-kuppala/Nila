package com.nila

import android.app.Application
import com.nila.actions.Notifier
import com.nila.data.NilaDatabase

class NilaApp : Application() {
    val database by lazy { NilaDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier(this).ensureChannels()
        // Both are read by things that run above the view model -- the theme by
        // the Activity, the recording policy by a foreground service that a
        // notification action can start with no Activity at all -- so they are
        // hydrated here, once, where every entry point has already run.
        com.nila.ui.theme.Appearance.load(this)
        com.nila.data.ClipPolicy.load(this)
    }
}
