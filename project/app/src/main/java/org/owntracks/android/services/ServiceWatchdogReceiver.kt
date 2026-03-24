package org.owntracks.android.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.owntracks.android.debug.RemoteDebugLogger
import timber.log.Timber

class ServiceWatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        Timber.tag("OT-DEBUG").d("ServiceWatchdog: checking service status")

        // 检查 BackgroundService 是否在运行
        val isRunning = isServiceRunning(context)

        if (!isRunning) {
            Timber.tag("OT-DEBUG").w("ServiceWatchdog: BackgroundService NOT running, restarting!")
            RemoteDebugLogger.logWarn("WATCHDOG_RESTART", "Service was dead, restarting via watchdog")
            try {
                val serviceIntent = Intent(context, BackgroundService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Timber.tag("OT-DEBUG").e(e, "ServiceWatchdog: failed to restart service")
            }
        } else {
            Timber.tag("OT-DEBUG").d("ServiceWatchdog: service is alive")
            RemoteDebugLogger.log("WATCHDOG_OK", "Service alive check passed")

            // 检查最后位置回调时间，超过 10 分钟没有回调则强制重新注册
            val lastLocationTime = BackgroundService.lastLocationReceivedTime
            val now = System.currentTimeMillis()
            if (lastLocationTime > 0 && (now - lastLocationTime) > 10 * 60 * 1000) {
                val staleMins = (now - lastLocationTime) / 60000
                Timber.tag("OT-DEBUG").w("Watchdog: no location callback for ${staleMins} minutes, forcing re-register")
                RemoteDebugLogger.logWarn("WATCHDOG_LOCATION_STALE", "No location callback for ${staleMins} minutes, forcing re-register")
                try {
                    val reregisterIntent = Intent(context, BackgroundService::class.java)
                    reregisterIntent.action = BackgroundService.INTENT_ACTION_FORCE_LOCATION_REREGISTER
                    context.startForegroundService(reregisterIntent)
                } catch (e: Exception) {
                    Timber.tag("OT-DEBUG").e(e, "ServiceWatchdog: failed to send FORCE_LOCATION_REREGISTER intent")
                }
            }
        }

        // 调度下一次检查（5分钟后）
        scheduleNext(context)
    }

    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (BackgroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 5 * 60 * 1000L // 5分钟
        private const val REQUEST_CODE = 1002

        fun scheduleNext(context: Context) {
            try {
                val intent = Intent(context, ServiceWatchdogReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, REQUEST_CODE, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                    pi
                )
                Timber.tag("OT-DEBUG").d("ServiceWatchdog: next check in 5 minutes")
            } catch (e: Exception) {
                Timber.tag("OT-DEBUG").e(e, "ServiceWatchdog: failed to schedule next check")
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, ServiceWatchdogReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }
    }
}
