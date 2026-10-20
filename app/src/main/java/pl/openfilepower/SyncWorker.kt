package pl.openfilepower

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Synchronizacja w tle co ~15 minut (najkrótszy okres WorkManagera). Dysk niedostępny – tylko liczy
 * zmiany na telefonie; po powrocie do sieci wysyła je. Przerwany przebieg wznawia się dzięki zapisom stanu.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val pairs = SyncStore(applicationContext).pairs().filter { it.auto }
        for (pair in pairs) {
            if (isStopped) break
            val state = SyncStore(applicationContext).state(pair.id)
            if (state.guard != null) continue // czeka na potwierdzenie w aplikacji
            val run = runCatching { SyncRunner.run(applicationContext, pair, "w tle") }.getOrNull() ?: continue
            val conflicts = SyncStore(applicationContext).state(pair.id).conflicts.size
            val guard = SyncStore(applicationContext).state(pair.id).guard
            when {
                guard != null -> notify(applicationContext, pair, "Synchronizacja wstrzymana", guard)
                run.failure == null && (run.changes > 0 || conflicts > 0) -> notify(applicationContext, pair, pair.name, run.summary() + if (conflicts > 0) " – dotknij, by rozstrzygnąć" else "")
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "folder-sync"
        private const val CHANNEL = "sync"
        fun schedule(context: Context, enabled: Boolean) {
            runCatching {
                val wm = WorkManager.getInstance(context)
                if (!enabled) { wm.cancelUniqueWork(WORK); return }
                wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build())
            }
        }
        private fun notify(context: Context, pair: SyncPair, title: String, text: String) = runCatching {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Synchronizacja folderów", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SYNC).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(pair.id.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_transfer).setContentTitle(title).setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
