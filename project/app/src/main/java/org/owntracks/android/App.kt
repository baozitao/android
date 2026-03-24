package org.owntracks.android

import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import java.util.concurrent.atomic.AtomicInteger
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationManagerCompat
import androidx.databinding.DataBindingUtil
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.EarlyEntryPoint
import dagger.hilt.android.EarlyEntryPoints
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import java.security.Security
import javax.inject.Provider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import org.owntracks.android.di.CustomBindingComponentBuilder
import org.owntracks.android.di.CustomBindingEntryPoint
import org.owntracks.android.geocoding.GeocoderProvider
import org.owntracks.android.logging.TimberInMemoryLogTree
import org.owntracks.android.preferences.Preferences
import org.owntracks.android.preferences.types.AppTheme
import org.owntracks.android.services.MessageProcessor
import org.owntracks.android.services.worker.Scheduler
import org.owntracks.android.support.RunThingsOnOtherThreads
import org.owntracks.android.support.receiver.StartBackgroundServiceReceiver
import org.owntracks.android.debug.RemoteDebugLogger
import timber.log.Timber

@HiltAndroidApp
class App : BaseApp() {
  override fun onCreate() {
    super.onCreate()
    StartBackgroundServiceReceiver.enable(this)
  }
}

open class BaseApp :
    Application(),
    Configuration.Provider,
    Preferences.OnPreferenceChangeListener,
    ComponentCallbacks2 {

  @EarlyEntryPoint
  @InstallIn(SingletonComponent::class)
  internal interface ApplicationEntrypoint {
    fun preferences(): Preferences

    fun workerFactory(): HiltWorkerFactory

    fun scheduler(): Scheduler

    fun bindingComponentProvider(): Provider<CustomBindingComponentBuilder>

    fun messageProcessor(): MessageProcessor

    fun notificationManager(): NotificationManagerCompat

    fun runThingsOnOtherThreads(): RunThingsOnOtherThreads
  }

  private val preferences by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).preferences()
  }

  private val workerFactory: HiltWorkerFactory by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).workerFactory()
  }

  private val scheduler: Scheduler by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).scheduler()
  }

  private val bindingComponentProvider: Provider<CustomBindingComponentBuilder> by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).bindingComponentProvider()
  }

  private val notificationManager: NotificationManagerCompat by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).notificationManager()
  }

  private val runThingsOnOtherThreads: RunThingsOnOtherThreads by lazy {
    EarlyEntryPoints.get(this, ApplicationEntrypoint::class.java).runThingsOnOtherThreads()
  }

  private val _workManagerFailedToInitialize = MutableStateFlow(false)
  val workManagerFailedToInitialize: StateFlow<Boolean> = _workManagerFailedToInitialize

  override fun onCreate() {
    Timber.tag("OT-DEBUG").d("App.onCreate started")
    RemoteDebugLogger.init(this)

    // APP_INIT: 版本、flavor、构建类型信息
    val versionName = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "unknown" }
    val flavor = try { BuildConfig.FLAVOR } catch (e: Exception) { "unknown" }
    val buildType = try { BuildConfig.BUILD_TYPE } catch (e: Exception) { "unknown" }
    val versionCode = try { BuildConfig.VERSION_CODE.toString() } catch (e: Exception) { "unknown" }
    val isDebug = try { BuildConfig.DEBUG.toString() } catch (e: Exception) { "unknown" }
    Timber.tag("OT-DEBUG").d("APP_INIT: version=$versionName, flavor=$flavor, build_type=$buildType, versionCode=$versionCode, isDebug=$isDebug")
    RemoteDebugLogger.log("APP_INIT", "Application initializing", mapOf(
        "version" to versionName,
        "version_code" to versionCode,
        "flavor" to flavor,
        "build_type" to buildType,
        "is_debug" to isDebug,
        "android_sdk" to Build.VERSION.SDK_INT.toString(),
        "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "process_id" to android.os.Process.myPid().toString()
    ))

    RemoteDebugLogger.log("APP_START", "App.onCreate started")
    // Make sure we use Conscrypt for advanced TLS features on all devices.
    Security.insertProviderAt(Conscrypt.newProviderBuilder().provideTrustManager(true).build(), 1)

    // Bring in a real version of BC and don't use the device version.
    Security.removeProvider("BC")
    Security.addProvider(BouncyCastleProvider())

    super.onCreate()

    setGlobalExceptionHandler()

    val dataBindingComponent = bindingComponentProvider.get().build()
    val dataBindingEntryPoint =
        EntryPoints.get(dataBindingComponent, CustomBindingEntryPoint::class.java)

    DataBindingUtil.setDefaultComponent(dataBindingEntryPoint)

    scheduler.cancelAllTasks()
    Timber.plant(TimberInMemoryLogTree(BuildConfig.DEBUG))

    if (BuildConfig.DEBUG) {

      Timber.e("StrictMode enabled in DEBUG build")
      StrictMode.setThreadPolicy(
          StrictMode.ThreadPolicy.Builder()
              .detectNetwork()
              .penaltyFlashScreen()
              .penaltyDialog()
              .build())
      StrictMode.setVmPolicy(
          StrictMode.VmPolicy.Builder()
              .detectLeakedSqlLiteObjects()
              .detectLeakedClosableObjects()
              .detectFileUriExposure()
              .penaltyLog()
              .build())
    }

    preferences.registerOnPreferenceChangedListener(this)

    setThemeFromPreferences()

    // Foreground/background detection
    val activeActivityCount = AtomicInteger(0)
    registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
      override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
      override fun onActivityStarted(activity: android.app.Activity) {
        if (activeActivityCount.getAndIncrement() == 0) {
          Timber.tag("OT-DEBUG").d("App moved to foreground")
          RemoteDebugLogger.log("APP_FOREGROUND", "App moved to foreground")
        }
      }
      override fun onActivityResumed(activity: android.app.Activity) {}
      override fun onActivityPaused(activity: android.app.Activity) {}
      override fun onActivityStopped(activity: android.app.Activity) {
        if (activeActivityCount.decrementAndGet() == 0) {
          Timber.tag("OT-DEBUG").d("App moved to background")
          RemoteDebugLogger.log("APP_BACKGROUND", "App moved to background")
        }
      }
      override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
      override fun onActivityDestroyed(activity: android.app.Activity) {}
    })

    Timber.tag("OT-DEBUG").d("App.onCreate completed")
    RemoteDebugLogger.log("APP_START", "App.onCreate completed")

    // System permission and power status check
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoringBatteryOptimizations = pm.isIgnoringBatteryOptimizations(packageName)

    val notificationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true

    val fineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val backgroundLocation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else true

    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val backgroundRestricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        activityManager.isBackgroundRestricted
    } else false

    val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val networkLocationEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    RemoteDebugLogger.log("SYSTEM_STATUS", "System permission and power status check", mapOf(
        "battery_unrestricted" to ignoringBatteryOptimizations.toString(),
        "notification_enabled" to notificationEnabled.toString(),
        "fine_location" to fineLocation.toString(),
        "background_location" to backgroundLocation.toString(),
        "background_restricted" to backgroundRestricted.toString(),
        "gps_enabled" to gpsEnabled.toString(),
        "network_location_enabled" to networkLocationEnabled.toString()
    ))

    if (!ignoringBatteryOptimizations) {
        RemoteDebugLogger.logWarn("SYSTEM_WARNING", "Battery optimization is NOT disabled - app may be killed in background")
    }
    if (!backgroundLocation) {
        RemoteDebugLogger.logWarn("SYSTEM_WARNING", "Background location permission NOT granted")
    }
    if (backgroundRestricted) {
        RemoteDebugLogger.logWarn("SYSTEM_WARNING", "App is background restricted by system")
    }

    // Notifications can be sent from multiple places, so let's make sure we've got the channels in
    // place
    createNotificationChannels()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      (this.getSystemService(ACTIVITY_SERVICE) as ActivityManager)
          .getHistoricalProcessExitReasons(this.packageName, 0, 10)
          .firstOrNull()
          ?.run {
            Timber.i(
                "Historical process exited at ${Instant.fromEpochMilliseconds(timestamp)}. reason: $description, status: $status, reason: $reason")
          }
    }
    applicationContext.noBackupFilesDir.resolve("crash.log").run {
      if (exists()) {
        readText().let { Timber.e("Previous crash: $it") }
        delete()
      }
    }
  }

  private fun setGlobalExceptionHandler() {
    val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
      try {
        applicationContext.noBackupFilesDir
            .resolve("crash.log")
            .writeText(
                """
          |Thread: ${t.name}
          |Exception: ${e.message}
          |Stacktrace:
          |${e.stackTrace.joinToString("\n\t")}
          """
                    .trimMargin())
      } catch (e: Exception) {
        Timber.e(e, "Error writing crash log")
      }
      currentHandler?.uncaughtException(t, e)
    }
  }

  @MainThread
  private fun setThemeFromPreferences() {
    when (preferences.theme) {
      AppTheme.Auto -> AppCompatDelegate.setDefaultNightMode(Preferences.SYSTEM_NIGHT_AUTO_MODE)
      AppTheme.Dark -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
      AppTheme.Light -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
  }

  private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      // Importance min will show normal priority notification for foreground service. See
      // https://developer.android.com/reference/android/app/NotificationManager#IMPORTANCE_MIN
      // User has to actively configure this in the notification channel settings.
      val ongoingNotificationChannelName =
          if (getString(R.string.notificationChannelOngoing).trim().isNotEmpty()) {
            getString(R.string.notificationChannelOngoing)
          } else {
            "Ongoing"
          }
      NotificationChannel(
              NOTIFICATION_CHANNEL_ONGOING,
              ongoingNotificationChannelName,
              NotificationManager.IMPORTANCE_LOW)
          .apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = getString(R.string.notificationChannelOngoingDescription)
            enableLights(false)
            enableVibration(false)
            setShowBadge(false)
            setSound(null, null)
          }
          .run { notificationManager.createNotificationChannel(this) }

      val eventsNotificationChannelName =
          if (getString(R.string.events).trim().isNotEmpty()) {
            getString(R.string.events)
          } else {
            "Events"
          }
      NotificationChannel(
              NOTIFICATION_CHANNEL_EVENTS,
              eventsNotificationChannelName,
              NotificationManager.IMPORTANCE_HIGH)
          .apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            description = getString(R.string.notificationChannelEventsDescription)
            enableLights(false)
            enableVibration(false)
            setShowBadge(true)
            setSound(null, null)
          }
          .run { notificationManager.createNotificationChannel(this) }

      val errorNotificationChannelName =
          if (getString(R.string.notificationChannelErrors).trim().isNotEmpty()) {
            getString(R.string.notificationChannelErrors)
          } else {
            "Errors"
          }
      NotificationChannel(
              GeocoderProvider.ERROR_NOTIFICATION_CHANNEL_ID,
              errorNotificationChannelName,
              NotificationManager.IMPORTANCE_LOW)
          .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
          .run { notificationManager.createNotificationChannel(this) }
    }
  }

  override fun onPreferenceChanged(properties: Set<String>) {
    if (properties.contains(Preferences::theme.name)) {
      Timber.d("Theme changed. Setting theme to ${preferences.theme}")
      // Can only call setThemeFromPreferences on the main thread
      runThingsOnOtherThreads.postOnMainHandlerDelayed(::setThemeFromPreferences, 0)
    }
    Timber.v("Idling preferenceSetIdlingResource because of $properties")
  }

  override fun onTrimMemory(level: Int) {
    Timber.w(
        "onTrimMemory notified ${getAvailableMemory().run { "isLowMemory: $lowMemory availMem: ${android.text.format.Formatter.formatShortFileSize(applicationContext,availMem)}, threshold: ${android.text.format.Formatter.formatShortFileSize(applicationContext,threshold)} totalMemory: ${android.text.format.Formatter.formatShortFileSize(applicationContext,totalMem)} " }}")
    super.onTrimMemory(level)
  }

  override fun onLowMemory() {
    Timber.w(
        "onLowMemory notified ${getAvailableMemory().run { "isLowMemory: $lowMemory availMem: ${android.text.format.Formatter.formatShortFileSize(applicationContext,availMem)}, threshold: ${android.text.format.Formatter.formatShortFileSize(applicationContext,threshold)} totalMemory: ${android.text.format.Formatter.formatShortFileSize(applicationContext,totalMem)} " }}")
    super.onLowMemory()
  }

  private fun getAvailableMemory(): ActivityManager.MemoryInfo {
    val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().also { memoryInfo ->
      activityManager.getMemoryInfo(memoryInfo)
    }
  }

  companion object {
    const val NOTIFICATION_CHANNEL_ONGOING = "O"
    const val NOTIFICATION_CHANNEL_EVENTS = "E"
    const val NOTIFICATION_ID_ONGOING = 1
    const val NOTIFICATION_ID_EVENT_GROUP = 2
    const val NOTIFICATION_GROUP_EVENTS = "events"
  }

  override val workManagerConfiguration: Configuration
    get() =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setInitializationExceptionHandler { throwable ->
              Timber.e(throwable, "Exception thrown when initializing WorkManager")
              _workManagerFailedToInitialize.value = true
            }
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
