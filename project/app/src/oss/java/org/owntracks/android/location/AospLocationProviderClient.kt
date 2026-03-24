package org.owntracks.android.location

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.location.LocationManagerCompat
import androidx.core.os.ExecutorCompat
import java.util.WeakHashMap
import org.owntracks.android.debug.RemoteDebugLogger
import timber.log.Timber

class AospLocationProviderClient(val context: Context) : LocationProviderClient() {
  enum class LocationSources {
    GPS,
    FUSED,
    NETWORK,
    PASSIVE
  }

  private val locationManager =
      context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?

  private val availableLocationProviders =
      (locationManager?.allProviders?.run {
        LocationSources.entries.filter { contains(it.name.lowercase()) }.toSet()
      } ?: emptySet())

  private val callbacks = WeakHashMap<LocationCallback, LocationListener>()

  private fun locationSourcesForPriority(priority: LocatorPriority): Set<LocationSources> =
      when (priority) {
        LocatorPriority.HighAccuracy -> setOf(LocationSources.GPS)
        else ->
            setOf(LocationSources.FUSED, LocationSources.NETWORK, LocationSources.PASSIVE)
                .intersect(availableLocationProviders)
      }

  @RequiresPermission(
      anyOf =
          ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"])
  override fun singleHighAccuracyLocation(clientCallBack: LocationCallback, looper: Looper) {
    Timber.d("Getting single high-accuracy location, posting to $clientCallBack")
    locationManager?.run {
      LocationManagerCompat.getCurrentLocation(
          this,
          LocationSources.GPS.name.lowercase(),
          android.os.CancellationSignal(),
          ExecutorCompat.create(Handler(looper))) { location: Location? ->
            location?.run {
              Timber.tag("OT-DEBUG").d("Location received: lat=$latitude, lon=$longitude, acc=$accuracy, provider=$provider")
              RemoteDebugLogger.log("AOSP_LOCATION", "Single location received", mapOf("lat" to latitude.toString(), "lon" to longitude.toString(), "acc" to accuracy.toString(), "provider" to (provider ?: "unknown")))
              clientCallBack.onLocationResult(LocationResult(this))
            } ?: run {
              Timber.w("Got null location from getCurrentLocation")
              Timber.tag("OT-DEBUG").w("Location unavailable: getCurrentLocation returned null")
              RemoteDebugLogger.logWarn("AOSP_LOCATION", "Location unavailable: getCurrentLocation returned null")
            }
          }
    }
  }

  @RequiresPermission(
      anyOf =
          ["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"])
  override fun actuallyRequestLocationUpdates(
      locationRequest: LocationRequest,
      clientCallBack: LocationCallback,
      looper: Looper
  ) {
    locationManager?.run {
      val listener = LocationListener { location ->
        Timber.tag("OT-DEBUG").d("Location received: lat=${location.latitude}, lon=${location.longitude}, acc=${location.accuracy}, provider=${location.provider}")
        RemoteDebugLogger.log("AOSP_LOCATION", "Location update received", mapOf("lat" to location.latitude.toString(), "lon" to location.longitude.toString(), "acc" to location.accuracy.toString(), "provider" to (location.provider ?: "unknown")))
        clientCallBack.onLocationResult(LocationResult(location))
      }
      callbacks[clientCallBack] = listener
      val sources = locationSourcesForPriority(locationRequest.priority)
      RemoteDebugLogger.log("LOCATION_REGISTER", "Registering location updates", mapOf(
          "sources" to sources.joinToString(","),
          "interval_ms" to locationRequest.interval.toMillis().toString(),
          "displacement_m" to (locationRequest.smallestDisplacement ?: 10f).toString(),
          "priority" to locationRequest.priority.name,
          "callback" to clientCallBack.toString()
      ))
      sources
          .apply {
            Timber.v("Requested location updates for sources $this to callback $clientCallBack")
          }
          .forEach {
            try {
              requestLocationUpdates(
                  it.name.lowercase(),
                  locationRequest.interval.toMillis(),
                  locationRequest.smallestDisplacement ?: 10f,
                  listener,
                  looper)
              RemoteDebugLogger.log("LOCATION_REGISTER_OK", "Registered ${it.name.lowercase()} provider", mapOf(
                  "provider" to it.name.lowercase(),
                  "interval_ms" to locationRequest.interval.toMillis().toString()
              ))
            } catch (e: Exception) {
              RemoteDebugLogger.logError("LOCATION_REGISTER_FAIL", "Failed to register ${it.name.lowercase()}: ${e.message}", mapOf(
                  "provider" to it.name.lowercase(),
                  "error" to (e.message ?: e.javaClass.simpleName)
              ))
            }
          }
    }
  }

  override fun removeLocationUpdates(clientCallBack: LocationCallback) {
    Timber.v("removeLocationUpdates for $clientCallBack")
    callbacks.getOrDefault(clientCallBack, null)?.apply {
      locationManager?.removeUpdates(this)
      callbacks.remove(clientCallBack)
      RemoteDebugLogger.log("LOCATION_UNREGISTER", "Removed location updates", mapOf("callback" to clientCallBack.toString(), "success" to "true"))
    } ?: run {
      Timber.w("No current location updates found for $clientCallBack")
      RemoteDebugLogger.logWarn("LOCATION_UNREGISTER", "No listener found for callback", mapOf("callback" to clientCallBack.toString(), "success" to "false"))
    }
  }

  override fun flushLocations() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      callbacks.values.zip(availableLocationProviders).forEach {
        try {
          Timber.v("Flushing locations for ${it.first} callback on ${it.second} provider")
          locationManager?.requestFlush(it.second.name.lowercase(), it.first, 0)
        } catch (e: IllegalArgumentException) {
          if (e.message == "unregistered listener cannot be flushed") {
            Timber.d(
                "Unable to flush locations for ${it.second} callback, as provider ${it.second} is not registered")
          } else {
            Timber.e(e, "Unable to flush locations for ${it.second} callback")
          }
        }
      }
    } else {
      Timber.w(
          "Can't flush locations, Android device API needs to be 31, is actually ${Build.VERSION.SDK_INT}")
    }
  }

  @Suppress("MissingPermission")
  override fun getLastLocation(): Location? =
      locationManager?.run {
        LocationSources.entries
            .map { getLastKnownLocation(it.name.lowercase()) }
            .maxByOrNull { it?.time ?: 0 }
      }

  init {
    Timber.i(
        "Using AOSP as a location provider. Available providers are ${locationManager?.allProviders}")
  }
}
