package app.loudness

import android.app.Application

/** Persistent process: Android starts it at boot and keeps it alive, so the effect stays attached. */
class BoostApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Boost.ensure(this)
    }
}
