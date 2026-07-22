package com.zerochat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@ZiltAndroidApp
class ZeroChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
