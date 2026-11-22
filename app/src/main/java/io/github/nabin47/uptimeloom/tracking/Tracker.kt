package io.github.nabin47.uptimeloom.tracking

import android.content.Context
import io.github.nabin47.uptimeloom.data.AppDb
import io.github.nabin47.uptimeloom.data.EventLog
import io.github.nabin47.uptimeloom.data.Presence
import io.github.nabin47.uptimeloom.data.Session
import io.github.nabin47.uptimeloom.data.SettingsRepo
import io.github.nabin47.uptimeloom.widget.TodayWidget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns "entered area X" / "left area X" into work sessions.
 *
 * - You're at work while you're inside ANY enabled area (several areas can
 *   overlap or be visited in a row without splitting the day).
 * - Leaving is only final after the "leave delay": a quick trip out (coffee,
 *   car park) doesn't end the session. That's done by a delayed worker
 *   ([ExitConfirmWorker]) that closes the session at the time you actually
 *   left, if you haven't come back by then.
 */
class Tracker(
    private val context: Context,
    private val db: AppDb,
    private val settings: SettingsRepo,
) {
    private val lock = Mutex()

    suspend fun log(kind: String, detail: String, time: Long = System.currentTimeMillis()) {
        db.events().insert(EventLog(time = time, kind = kind, detail = detail))
    }

    /** Arrived in [placeId] at [time] (already adjusted for the arrive delay). */
    suspend fun onEnter(placeId: Long, time: Long, via: String) {
        var startedNew = false
        lock.withLock {
            val place = db.places().byId(placeId)
            if (place == null || !place.enabled) {
                log("ignored", "Entered unknown or disabled area #$placeId ($via)")
                return
            }
            Scheduling.cancelExitConfirm(context)
            db.presence().insert(Presence(placeId, time))
            val open = db.sessions().open()
            if (open == null) {
                val start = minOf(time, System.currentTimeMillis())
                db.sessions().insert(Session(start = start, end = null, placeId = placeId, source = Session.SOURCE_AUTO))
                startedNew = true
            }
            log("arrived", "${place.name} ($via)", time)
        }
        refreshUi()
        if (startedNew) notify { Notifier.arrived(context, it, time) }
    }

    /** Left [placeId] at [time]. The session ends only if no other area holds us. */
    suspend fun onExit(placeId: Long, time: Long, via: String) {
        lock.withLock {
            val name = db.places().byId(placeId)?.name ?: "area #$placeId"
            db.presence().delete(placeId)
            log("left", "$name ($via)", time)
            if (db.presence().all().isEmpty() && db.sessions().open()?.source == Session.SOURCE_AUTO) {
                val delayMin = settings.current().leaveDelayMin
                Scheduling.scheduleExitConfirm(context, exitTime = time, delayMs = delayMin * 60_000L)
            }
        }
        refreshUi()
    }

    /** Called by [ExitConfirmWorker] once the leave delay has passed. */
    suspend fun confirmExit(exitTime: Long) {
        var closedMs = -1L
        lock.withLock {
            if (db.presence().all().isNotEmpty()) return            // came back in time
            val open = db.sessions().open() ?: return
            if (open.source != Session.SOURCE_AUTO) return             // manual check-in wins
            val end = exitTime.coerceAtLeast(open.start)
            if (end - open.start < 60_000L) {
                db.sessions().delete(open.id)                          // just passing through
                log("dropped", "Session under a minute was discarded")
            } else {
                db.sessions().update(open.copy(end = end))
                closedMs = end - open.start
                log("ended", "Work session ended", end)
            }
        }
        refreshUi()
        if (closedMs >= 0) notify { Notifier.left(context, it, exitTime) }
    }

    suspend fun checkIn() {
        lock.withLock {
            if (db.sessions().open() == null) {
                db.sessions().insert(Session(start = System.currentTimeMillis(), end = null, source = Session.SOURCE_MANUAL))
                log("check-in", "Manual check-in")
            }
        }
        refreshUi()
    }

    /** Ends the running session now. Automatic tracking resumes next time you arrive. */
    suspend fun checkOut() {
        lock.withLock {
            val open = db.sessions().open() ?: return
            val now = System.currentTimeMillis()
            db.sessions().update(open.copy(end = now.coerceAtLeast(open.start)))
            Scheduling.cancelExitConfirm(context)
            log("check-out", "Manual check-out")
        }
        refreshUi()
    }

    /**
     * After a restart we don't know if the phone is still inside an area, so
     * presence is cleared and geofences re-armed (they report "inside" again
     * if we are). If nothing confirms it within [BootCheckWorker]'s delay,
     * the open session is closed at boot time.
     */
    suspend fun onBoot(bootTime: Long) {
        lock.withLock {
            db.presence().clear()
            log("restart", "Phone restarted", bootTime)
        }
        val open = db.sessions().open()
        if (open != null && open.source == Session.SOURCE_AUTO) Scheduling.scheduleBootCheck(context, bootTime)
    }

    suspend fun bootCheck(bootTime: Long) {
        lock.withLock {
            if (db.presence().all().isNotEmpty()) return
            val open = db.sessions().open() ?: return
            if (open.source != Session.SOURCE_AUTO) return
            db.sessions().update(open.copy(end = bootTime.coerceAtLeast(open.start)))
            log("ended", "Session closed at restart (not in a work area afterwards)", bootTime)
        }
        refreshUi()
    }

    /** An area was deleted or disabled: forget that we're inside it. */
    suspend fun forgetPlace(placeId: Long) {
        val wasInside = db.presence().all().any { it.placeId == placeId }
        if (wasInside) onExit(placeId, System.currentTimeMillis(), "area removed")
    }

    private suspend fun notify(block: (String) -> Unit) {
        if (settings.current().notifications) {
            val names = db.presence().all().mapNotNull { db.places().byId(it.placeId)?.name }
            block(names.firstOrNull() ?: "work area")
        }
    }

    private suspend fun refreshUi() {
        runCatching { TodayWidget.refresh(context) }
    }
}
