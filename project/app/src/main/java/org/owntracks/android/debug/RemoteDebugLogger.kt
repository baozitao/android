package org.owntracks.android.debug

import android.os.Build
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
 * 远程调试日志上报器，将 OT-DEBUG 日志异步上报到 REDACTED_DEBUG_HOST
 * 单例模式，非阻塞，失败静默
 */
object RemoteDebugLogger {

    private const val LOG_URL = "https://REDACTED_DEBUG_HOST/api/log"
    private const val APP_NAME = "owntracks"
    private const val TAG = "RemoteDebugLogger"
    private const val BATCH_SIZE = 10
    private const val FLUSH_INTERVAL_SECONDS = 30L

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "remote-debug-logger").apply { isDaemon = true }
    }

    private val queue = LinkedBlockingQueue<LogEntry>(200)

    private val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}"
    private val androidVersion: String = Build.VERSION.SDK_INT.toString()
    private val appVersion: String = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "unknown" }
    private val appFlavor: String = try { BuildConfig.FLAVOR } catch (e: Exception) { "unknown" }

    init {
        // 定期刷新队列
        executor.scheduleWithFixedDelay(
            { flushQueue() },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
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

    private fun sendLog(entry: LogEntry) {
        var connection: HttpURLConnection? = null
        try {
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
        }
    }
}
