package com.example.print

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class SendJobService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val jobTitle = intent?.getStringExtra(EXTRA_JOB_TITLE) ?: "Dokumen"
        val notification = NotificationCompat.Builder(this, PrintJobDispatcher.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Mengirim ke Printer...")
            .setContentText(jobTitle)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_JOB_TITLE = "extra_job_title"
        private const val NOTIF_ID = 4102
    }
}
