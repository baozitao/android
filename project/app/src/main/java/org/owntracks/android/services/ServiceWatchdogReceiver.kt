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
        val lastLocationTime = BackgroundService.lastLocationReceivedTime
        val now = System.currentTimeMillis()
        val locationAgeMs = if (lastLocationTime > 0) now - lastLocationTime else -1L
        val locationAgeSec = if (locationAgeMs >= 0) locationAgeMs / 1000 else -1L

        Timber.tag("OT-DEBUG").d(
            "WATCHDOG_CHECK: service_running=$isRunning, last_location_age=${locationAgeSec}s, threshold=600s"
        )
        RemoteDebugLogger.log("WATCHDOG_CHECK", "Watchdog check executing", mapOf(
            "service_running" to isRunning.toString(),
            "last_location_age_s" to locationAgeSec.toString(),
            "threshold_s" to "600",
            "last_location_time" to lastLocationTime.toString(),
            "location_received" to (lastLocationTime > 0).toString()
        ))

        if (!isRunning) {
            Timber.tag("OT-DEBUG").w("ServiceWatchdog: BackgroundService NOT running, restarting!")
            Timber.tag("OT-DEBUG").w("WATCHDOG_ACTION: restarting_service")
            RemoteDebugLogger.logWarn("WATCHDOG_ACTION", "restarting_service - service was dead", mapOf(
                "action" to "restarting_service",
                "reason" to "service_not_running"
            ))
            try {
                val serviceIntent = Intent(context, BackgroundService::class.java)
                context.startForegroundService(serviceIntent)
                Timber.tag("OT-DEBUG").d("WATCHDOG_ACTION: startForegroundService sent successfully")
            } catch (e: Exception) {
                Timber.tag("OT-DEBUG").e(e, "WATCHDOG_ACTION: failed to restart service: ${e.message}")
                RemoteDebugLogger.logError("WATCHDOG_ACTION", "Failed to restart service: ${e.message}", mapOf(
                    "error" to e.javaClass.simpleName,
                    "message" to (e.message ?: "unknown")
                ))
            }
        } else {
            // 检查最后位置回调时间，超过 10 分钟没有回调则强制重新注册
            if (lastLocationTime > 0 && locationAgeSec > 600) {
                val staleMins = locationAgeMs / 60000
                Timber.tag("OT-DEBUG").w("WATCHDOG_ACTION: re-registering_location - no callback for ${staleMins} minutes")
                RemoteDebugLogger.logWarn("WATCHDOG_ACTION", "re-registering_location - GPS stale for ${staleMins} min", mapOf(
                    "action" to "re-registering_location",
                    "stale_minutes" to staleMins.toString(),
                    "last_location_age_s" to locationAgeSec.toString(),
                    "threshold_s" to "600"
                ))
                try {
                    val reregisterIntent = Intent(context, BackgroundService::class.java)
                    reregisterIntent.action = BackgroundService.INTENT_ACTION_FORCE_LOCATION_REREGISTER
                    context.startForegroundService(reregisterIntent)
                    Timber.tag("OT-DEBUG").d("WATCHDOG_ACTION: FORCE_LOCATION_REREGISTER sent successfully")
                } catch (e: Exception) {
                    Timber.tag("OT-DEBUG").e(e, "WATCHDOG_ACTION: failed to send FORCE_LOCATION_REREGISTER: ${e.message}")
                    RemoteDebugLogger.logError("WATCHDOG_ACTION", "Failed to send reregister intent: ${e.message}", mapOf(
                        "error" to e.javaClass.simpleName,
                        "message" to (e.message ?: "unknown")
                    ))
                }
            } else {
                Timber.tag("OT-DEBUG").d("ServiceWatchdog: service is alive, location fresh")
                Timber.tag("OT-DEBUG").d("WATCHDOG_ACTION: all_ok")
                RemoteDebugLogger.log("WATCHDOG_ACTION", "all_ok - service alive and location fresh", mapOf(
                    "action" to "all_ok",
                    "service_running" to "true",
                    "last_location_age_s" to locationAgeSec.toString()
                ))
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
