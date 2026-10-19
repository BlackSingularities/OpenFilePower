package pl.openfilepower

import android.app.Application
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
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

class OpenFilePowerApp : Application(), ImageLoaderFactory {
    override fun newImageLoader() = ImageLoader.Builder(this).components { add(RawFetcher.Factory()); add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
}

/**
 * Utrzymuje proces przy życiu podczas długich operacji na plikach (kopiowanie, pakowanie),
 * także gdy aplikacja jest w tle. Postęp pokazuje powiadomienie.
 */
class TransferService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build(this, intent?.getStringExtra(EXTRA_TITLE) ?: "Operacja na plikach", "Przygotowanie…", -1)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, notification)
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
    companion object {
        private const val CHANNEL = "transfers"
        private const val ID = 7301
        private const val EXTRA_TITLE = "title"
        private fun channel(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "Operacje na plikach", NotificationManager.IMPORTANCE_LOW))
        }
        private fun build(context: Context, title: String, text: String, percent: Int): Notification {
            channel(context)
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_transfer).setContentTitle(title).setContentText(text)
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setCategory(Notification.CATEGORY_PROGRESS)
                .apply { if (percent >= 0) setProgress(100, percent, false) else setProgress(0, 0, true) }.build()
        }
        fun start(context: Context, title: String) {
            runCatching { context.startForegroundService(Intent(context, TransferService::class.java).putExtra(EXTRA_TITLE, title)) }
        }
        fun update(context: Context, title: String, text: String, percent: Int) {
            runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, title, text, percent)) }
        }
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
            runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
        }
    }
}
