package com.example.kindlesend

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast

/** 前台服务：保证切到后台/锁屏后 HTTP 服务不被系统杀掉，并负责自动停止 */
class TransferService : Service() {
    private companion object {
        const val CHANNEL = "transfer"
        const val IDLE_MS = 10 * 60 * 1000L   // 闲置 10 分钟自动停止
        const val DONE_GRACE_MS = 20 * 1000L  // 全部下载后 20 秒停止
    }

    private var server: SimpleHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastActivity = 0L

    private val checker = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() - lastActivity > IDLE_MS) stopSelf()
            else handler.postDelayed(this, 5000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (server != null) return START_NOT_STICKY
        startInForeground()

        val books = BookStore.list(this).mapIndexed { i, f -> Book(i + 1, f) }
        val s = SimpleHttpServer(books, ::touch) { b ->
            ServerState.downloaded.add(b.file.name)
            // 全部下载完：把“最后活动时间”拨到只剩 20 秒就会超时
            if (ServerState.downloaded.size >= books.size) {
                lastActivity = System.currentTimeMillis() - IDLE_MS + DONE_GRACE_MS
            }
            ServerState.changed()
        }
        val port = s.start()
        if (port < 0) {
            Toast.makeText(this, "无法启动服务（端口被占用）", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        server = s
        ServerState.downloaded.clear()
        ServerState.port = port
        ServerState.running = true

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kindlesend:transfer")
            .apply { acquire(30 * 60 * 1000L) }

        touch()
        handler.postDelayed(checker, 5000)
        ServerState.changed()
        return START_NOT_STICKY
    }

    private fun touch() { lastActivity = System.currentTimeMillis() }

    private fun startInForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "传书服务", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Kindle 传书服务运行中")
            .setContentText("点击返回 APP 查看地址或停止")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override fun onDestroy() {
        handler.removeCallbacks(checker)
        server?.stop()
        server = null
        wakeLock?.let { if (it.isHeld) it.release() }
        ServerState.running = false
        ServerState.changed()
        super.onDestroy()
    }
}
