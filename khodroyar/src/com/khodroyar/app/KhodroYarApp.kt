package com.khodroyar.app

import android.app.Application

class KhodroYarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}
