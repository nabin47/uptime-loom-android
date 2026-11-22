package io.github.nabin47.uptimeloom.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.nabin47.uptimeloom.R
import io.github.nabin47.uptimeloom.ui.MainActivity
import java.util.Date

/** Quiet, optional "arrived" / "left" notifications. */
object Notifier {
    private const val CHANNEL = "tracking"
    private const val ID = 1

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Arriving and leaving", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A quiet note when tracking starts or stops"
                setShowBadge(false)
            }
        )
    }

    fun arrived(context: Context, place: String, time: Long) =
        post(context, "Tracking started", "Arrived at $place at ${clock(context, time)}")

    fun left(context: Context, place: String, time: Long) =
        post(context, "Tracking stopped", "Left $place at ${clock(context, time)}")

    private fun clock(context: Context, t: Long) =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(t))

    @SuppressLint("MissingPermission") // checked at the top for Android 13+
    private fun post(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_loom)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        NotificationManagerCompat.from(context).notify(ID, n)
    }

}
