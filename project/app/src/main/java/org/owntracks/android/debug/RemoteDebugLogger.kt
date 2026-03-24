package org.owntracks.android.debug

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import org.owntracks.android.BuildConfig
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 远程调试日志上报器，将 OT-DEBUG 日志异步上报到远程服务器
 * 单例模式，非阻塞，失败静默
 * 使用 WakeLock 防止 Android Doze 模式下网络被限制
 */
object RemoteDebugLogger {

    private val LOG_URL = BuildConfig.OT_DEBUG_URL
    private const val APP_NAME = "owntracks"
    private const val TAG = "RemoteDebugLogger"
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_SECONDS = 30L
    private const val WAKELOCK_TIMEOUT_MS = 10_000L // 最多持有 10 秒

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "remote-debug-logger").apply { isDaemon = true }
    }

    private val queue = LinkedBlockingQueue<LogEntry>(200)

    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val androidVersion: String = Build.VERSION.SDK_INT.toString()
    private val appVersion: String = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "unknown" }
    private val appFlavor: String = try { BuildConfig.FLAVOR } catch (e: Exception) { "unknown" }

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var appContext: Context? = null

    init {
        // 定期刷新队列
        executor.scheduleWithFixedDelay(
            { flushQueue() },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * 初始化 WakeLock，应在 Application.onCreate 或 BackgroundService.onCreate 中调用
     */
    @JvmStatic
    fun init(context: Context) {
        try {
            appContext = context.applicationContext
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OwnTracks:DebugLogger")
            wakeLock?.setReferenceCounted(false)
        } catch (e: Exception) {
            // 静默失败，WakeLock 是优化手段，不影响核心逻辑
        }
    }

    data class LogEntry(
        val event: String,
        val message: String,
        val level: String = "INFO",
        val extra: Map<String, String> = emptyMap(),
        val timestamp: String = currentTimestamp()
    )

    private fun currentTimestamp(): String {
        return try {
            OffsetDateTime.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (e: Exception) {
            ""
        }
    }

    @JvmStatic
    fun log(event: String, message: String, extra: Map<String, String> = emptyMap()) {
        try {
            val entry = LogEntry(event = event, message = message, level = "INFO", extra = extra)
            if (!queue.offer(entry)) {
                // 队列满了，丢弃最旧的
                queue.poll()
                queue.offer(entry)
            }
            if (queue.size >= BATCH_SIZE) {
                executor.execute { flushQueue() }
            }
        } catch (e: Exception) {
            // 静默失败
        }
    }

    @JvmStatic
    fun logWarn(event: String, message: String, extra: Map<String, String> = emptyMap()) {
        try {
            val entry = LogEntry(event = event, message = message, level = "WARN", extra = extra)
            queue.offer(entry)
            executor.execute { flushQueue() }
        } catch (e: Exception) {
            // 静默失败
        }
    }

    @JvmStatic
    fun logError(event: String, message: String, extra: Map<String, String> = emptyMap()) {
        try {
            val entry = LogEntry(event = event, message = message, level = "ERROR", extra = extra)
            queue.offer(entry)
            executor.execute { flushQueue() }
        } catch (e: Exception) {
            // 静默失败
        }
    }

    private fun flushQueue() {
        if (queue.isEmpty()) return
        val entries = mutableListOf<LogEntry>()
        queue.drainTo(entries, BATCH_SIZE)
        entries.forEach { entry ->
            sendLog(entry)
        }
    }

    private fun buildJson(entry: LogEntry): String {
        val extraJson = if (entry.extra.isEmpty()) {
            "{}"
        } else {
            entry.extra.entries.joinToString(",", "{", "}") { (k, v) ->
                "\"${k.replace("\"", "\\\"")}\": \"${v.replace("\"", "\\\"")}\""
            }
        }
        return """{"app":"$APP_NAME","device":"${deviceModel.replace("\"", "\\\"")}","level":"${entry.level}","tag":"OT-DEBUG","event":"${entry.event}","message":"${entry.message.replace("\"", "\\\"")}","timestamp":"${entry.timestamp}","android_version":"$androidVersion","app_version":"${appVersion.replace("\"", "\\\"")}","flavor":"$appFlavor","extra":$extraJson}"""
    }

    /**
     * 检查网络是否可用，避免 Doze 模式下无意义的超时等待
     */
    private fun isNetworkAvailable(): Boolean {
        val ctx = appContext ?: return true // 未初始化时乐观放行
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            true // 检查失败时乐观放行
        }
    }

    private fun sendLog(entry: LogEntry) {
        if (LOG_URL.isEmpty()) return
        // 网络不可用时跳过，避免 Doze 模式下长时间阻塞
        if (!isNetworkAvailable()) return
        var connection: HttpURLConnection? = null
        val wl = wakeLock
        try {
            // 获取 WakeLock，防止 Doze 模式下 CPU 休眠导致网络请求中断
            wl?.acquire(WAKELOCK_TIMEOUT_MS)
            val json = buildJson(entry)
            val url = URL(LOG_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(json)
                writer.flush()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Timber.tag(TAG).w("Remote log upload failed, HTTP $responseCode")
            }
        } catch (e: Exception) {
            // 静默失败，不影响主流程
        } finally {
            connection?.disconnect()
            // 释放 WakeLock
            try {
                if (wl?.isHeld == true) wl.release()
            } catch (e: Exception) {
                // 静默失败
            }
        }
    }
}
