package com.tripsplit.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the trip chat connected in the background and shows a pop-up when a message arrives.
 * It shows a small "connected" notification while running. Tap "Stop listening" to turn it off.
 */
class ListenService : Service() {
    companion object {
        const val CH_LISTEN = "listen"
        const val CH_MSG = "messages"
        const val ACTION_STOP = "stop"

        fun start(ctx: Context, trip: Trip) {
            val i = Intent(ctx, ListenService::class.java).putExtra("id", trip.id).putExtra("key", trip.key)
                .putExtra("me", trip.me).putExtra("name", trip.name)
                .putExtra("s", trip.start).putExtra("e", trip.end)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ListenService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LISTEN, "Connected to trip chat", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Trip messages", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun ongoing(name: String) = NotificationCompat.Builder(this, CH_LISTEN)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("TripSplit is connected")
        .setContentText("$name chat is on. Listening for messages on this Wi-Fi.")
        .setOngoing(true).setContentIntent(openApp())
        .addAction(0, "Stop listening", PendingIntent.getService(this, 1,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NetHolder.net?.stop(); NetHolder.net = null
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("id"); val key = intent?.getStringExtra("key"); val me = intent?.getStringExtra("me")
        val name = intent?.getStringExtra("name") ?: "Trip"
        startForeground(1, ongoing(name))
        val window = tripWindow(intent?.getLongExtra("s", 0L) ?: 0L, intent?.getLongExtra("e", 0L) ?: 0L)
        if (id == null || key == null || me == null || System.currentTimeMillis() !in window) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val cur = NetHolder.net
        if (cur == null || cur.tripId != id || cur.me != me) {
            cur?.stop()
            NetHolder.tripName = name
            val n = TripNet(applicationContext, id, key, me, devId(applicationContext), window)
            n.onIncoming = { m -> notifyMessage(m, me) }
            n.onExpired = {
                NetHolder.net?.stop(); NetHolder.net = null
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
            n.start()
            NetHolder.net = n
        }
        return START_NOT_STICKY
    }

    private fun notifyMessage(m: ChatMsg, me: String) {
        if (NetHolder.uiVisible || m.from == me) return
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        val body = when (m.kind) {
            "text" -> m.text
            "image" -> "sent a photo"
            "video" -> "sent a video"
            "deleted" -> return
            else -> "sent a file: ${m.fname}"
        }
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("${NetHolder.tripName} · ${m.from}")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true).setContentIntent(openApp())
            .setPriority(NotificationCompat.PRIORITY_HIGH).setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()
        try { nm.notify(m.id.hashCode(), n) } catch (e: SecurityException) { }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
