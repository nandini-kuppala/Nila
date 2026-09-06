package com.nila

import android.app.Application
import com.nila.actions.Notifier
import com.nila.data.NilaDatabase

class NilaApp : Application() {
    val database by lazy { NilaDatabase.get(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier(this).ensureChannels()
    }
}
