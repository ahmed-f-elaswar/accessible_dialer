package com.accessible.dialer

import android.app.Application
import com.accessible.dialer.settings.SettingsRepository

/**
 * Application entry point. Kept intentionally minimal — no DI framework is used so
 * the project stays easy to build/read. Any process-wide initialization belongs here.
 */
class DialerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
    }
}
