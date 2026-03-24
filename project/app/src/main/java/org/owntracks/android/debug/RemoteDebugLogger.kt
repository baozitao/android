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
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

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
            {
                val queueSize = queue.size
                val isFlushing = queueSize > 0
                Timber.tag(TAG).d("DEBUG_QUEUE: size=$queueSize, flushing=$isFlushing (periodic)")
                flushQueue()
            },
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
            Timber.tag(TAG).d("WAKELOCK_INIT: WakeLock created successfully")
        } catch (e: Exception) {
            Timber.tag(TAG).w("WAKELOCK_INIT: Failed to create WakeLock: ${e.message}")
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
                Timber.tag(TAG).w("DEBUG_QUEUE: queue full (200), dropped oldest entry to make room")
            }
            val queueSizeAfter = queue.size
            if (queueSizeAfter >= BATCH_SIZE) {
                Timber.tag(TAG).d("DEBUG_QUEUE: size=$queueSizeAfter, flushing=true (batch threshold reached)")
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
            Timber.tag(TAG).d("DEBUG_QUEUE: size=${queue.size}, flushing=true (WARN immediate flush)")
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
            Timber.tag(TAG).d("DEBUG_QUEUE: size=${queue.size}, flushing=true (ERROR immediate flush)")
            executor.execute { flushQueue() }
        } catch (e: Exception) {
            // 静默失败
        }
    }

    private fun flushQueue() {
        if (queue.isEmpty()) return
        val entries = mutableListOf<LogEntry>()
        queue.drainTo(entries, BATCH_SIZE)
        Timber.tag(TAG).d("DEBUG_QUEUE: flushing ${entries.size} entries")
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
     * 返回 Pair<是否可用, 网络类型>
     */
    private fun checkNetworkAvailability(): Pair<Boolean, String> {
        val ctx = appContext ?: return Pair(true, "unknown") // 未初始化时乐观放行
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return Pair(false, "none")
            val caps = cm.getNetworkCapabilities(network) ?: return Pair(false, "none")
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val networkType = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
            Pair(hasInternet, networkType)
        } catch (e: Exception) {
            Pair(true, "unknown") // 检查失败时乐观放行
        }
    }

    private fun sendLog(entry: LogEntry) {
        if (LOG_URL.isEmpty()) return

        // 网络检查
        val (networkAvailable, networkType) = checkNetworkAvailability()
        Timber.tag(TAG).d("NETWORK_CHECK: available=$networkAvailable, type=$networkType")
        if (!networkAvailable) {
            Timber.tag(TAG).d("DEBUG_LOG_FAILED: error=NoNetwork, type=none, skipping entry ${entry.event}")
            return
        }

        var connection: HttpURLConnection? = null
        val wl = wakeLock
        val startTime = System.currentTimeMillis()
        try {
            // 获取 WakeLock，防止 Doze 模式下 CPU 休眠导致网络请求中断
            val wasHeld = wl?.isHeld ?: false
            wl?.acquire(WAKELOCK_TIMEOUT_MS)
            if (!wasHeld) {
                Timber.tag(TAG).d("WAKELOCK_ACQUIRE: acquired for log upload (timeout=${WAKELOCK_TIMEOUT_MS}ms)")
            }

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
            val elapsed = System.currentTimeMillis() - startTime
            if (responseCode in 200..299) {
                Timber.tag(TAG).d("DEBUG_LOG_SENT: url=$LOG_URL, response=$responseCode, took=${elapsed}ms, event=${entry.event}")
            } else {
                Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=HTTP_$responseCode, url=$LOG_URL, took=${elapsed}ms, event=${entry.event}")
            }
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=SocketTimeout, took=${elapsed}ms, event=${entry.event}, msg=${e.message}")
        } catch (e: SSLException) {
            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=SSLError, took=${elapsed}ms, event=${entry.event}, msg=${e.message}")
        } catch (e: UnknownHostException) {
            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=DNS, took=${elapsed}ms, event=${entry.event}, host=${e.message}")
        } catch (e: java.net.ConnectException) {
            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=ConnectionRefused, took=${elapsed}ms, event=${entry.event}, msg=${e.message}")
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Timber.tag(TAG).w("DEBUG_LOG_FAILED: error=${e.javaClass.simpleName}, took=${elapsed}ms, event=${entry.event}, msg=${e.message}")
        } finally {
            connection?.disconnect()
            // 释放 WakeLock
            try {
                if (wl?.isHeld == true) {
                    wl.release()
                    Timber.tag(TAG).d("WAKELOCK_RELEASE: released after log upload")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("WAKELOCK_RELEASE: failed to release: ${e.message}")
            }
        }
    }
}
