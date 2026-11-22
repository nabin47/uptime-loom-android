package io.github.nabin47.uptimeloom

import android.app.Application
import io.github.nabin47.uptimeloom.tracking.Notifier
import io.github.nabin47.uptimeloom.tracking.Scheduling

class UptimeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannel(this)
        Scheduling.ensureDailyMaintenance(this)
    }
}
