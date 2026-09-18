package com.example.mediadownloader.downloader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.mediadownloader.MainActivity
import com.example.R
import com.example.mediadownloader.data.db.AppDatabase
import com.example.mediadownloader.data.repository.DownloadRepository
import com.example.mediadownloader.resolver.CompositeMediaResolver
import com.example.mediadownloader.resolver.DirectMediaResolver
import com.example.mediadownloader.resolver.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ACTIVE = "active_downloads_channel"
        const val CHANNEL_COMPLETED = "completed_downloads_channel"
        const val NOTIFICATION_ID_FOREGROUND = 1001

        const val ACTION_START_QUEUE = "com.example.mediadownloader.START_QUEUE"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.mediadownloader.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_ALL = "com.example.mediadownloader.CANCEL_ALL"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        fun startQueue(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_QUEUE
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelDownload(context: Context, id: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, id)
            }
            context.startService(intent)
        }

        fun cancelAll(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ALL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private lateinit var downloadManager: DownloadManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaDownloader:DownloadWakeLock")

        val database = AppDatabase.getInstance(this)
        val repository = DownloadRepository(database.downloadDao())
        val resolver = CompositeMediaResolver(listOf(YouTubeResolver(), DirectMediaResolver()))

        downloadManager = DownloadManager.getInstance(
            applicationContext,
            repository,
            resolver
        )

        // Listen for progress updates to update the notification
        serviceScope.launch {
            downloadManager.activeProgress.collect { progress ->
                if (progress != null) {
                    updateForegroundNotification(progress)
                }
            }
        }

        // Listen for download completions or failures to show notifications
        serviceScope.launch {
            downloadManager.lastCompletedEvent.collect { completedItem ->
                if (completedItem != null) {
                    showCompletionNotification(completedItem.title, completedItem.format)
                }
            }
        }

        serviceScope.launch {
            downloadManager.lastFailedEvent.collect { failedItem ->
                if (failedItem != null) {
                    showFailureNotification(failedItem.title, failedItem.errorMessage ?: "Unknown error")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification("Preparing download queue...", 0, 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_FOREGROUND,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, initialNotification)
        }

        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // max 2 hours
        }

        when (intent?.action) {
            ACTION_START_QUEUE -> {
                serviceScope.launch {
                    downloadManager.processQueue { isQueueEmpty ->
                        if (isQueueEmpty) {
                            stopSelf()
                        }
                    }
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) {
                    downloadManager.cancel(id)
                }
            }
            ACTION_CANCEL_ALL -> {
                downloadManager.cancelAll()
                stopSelf()
            }
            else -> {
                serviceScope.launch {
                    downloadManager.processQueue { isQueueEmpty ->
                        if (isQueueEmpty) {
                            stopSelf()
                        }
                    }
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (e: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val activeChannel = NotificationChannel(
                CHANNEL_ACTIVE,
                getString(R.string.channel_active_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_active_desc)
                setShowBadge(false)
            }

            val completedChannel = NotificationChannel(
                CHANNEL_COMPLETED,
                getString(R.string.channel_completed_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.channel_completed_desc)
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(activeChannel)
            notificationManager.createNotificationChannel(completedChannel)
        }
    }

    private fun updateForegroundNotification(progress: ActiveDownloadProgress) {
        val notification = buildNotification(
            title = progress.title,
            progress = progress.progress,
            currentIndex = progress.currentItemIndex,
            totalCount = progress.totalItems
        )
        notificationManager.notify(NOTIFICATION_ID_FOREGROUND, notification)
    }

    private fun buildNotification(
        title: String,
        progress: Int,
        currentIndex: Int,
        totalCount: Int
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (totalCount > 1) {
            "Item $currentIndex of $totalCount ($progress%)"
        } else {
            "$progress% completed"
        }

        val cancelIntent = Intent(this, DownloadService::class.java).apply {
            action = ACTION_CANCEL_ALL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress <= 0)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel All", cancelPendingIntent)
            .build()
    }

    private fun showCompletionNotification(title: String, format: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETED)
            .setContentTitle("Download completed")
            .setContentText("$title ($format)")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun showFailureNotification(title: String, errorReason: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            System.currentTimeMillis().toInt(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETED)
            .setContentTitle("Download failed")
            .setContentText("$title: $errorReason")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
