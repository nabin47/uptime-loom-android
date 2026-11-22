package io.github.nabin47.uptimeloom

import android.content.Context
import io.github.nabin47.uptimeloom.data.AppDb
import io.github.nabin47.uptimeloom.data.SettingsRepo
import io.github.nabin47.uptimeloom.tracking.Tracker

/** Tiny service locator: one instance of each per process. */
object Graph {
    lateinit var db: AppDb; private set
    lateinit var settings: SettingsRepo; private set
    lateinit var tracker: Tracker; private set
    @Volatile private var ready = false

    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val app = context.applicationContext
            db = AppDb.get(app)
            settings = SettingsRepo(app)
            tracker = Tracker(app, db, settings)
            ready = true
        }
    }
}
