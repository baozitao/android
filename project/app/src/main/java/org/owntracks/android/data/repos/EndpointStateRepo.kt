package org.owntracks.android.data.repos

import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import org.owntracks.android.data.EndpointState
import org.owntracks.android.debug.RemoteDebugLogger
import timber.log.Timber

@Singleton
class EndpointStateRepo @Inject constructor() {

  val endpointState: MutableStateFlow<EndpointState> = MutableStateFlow(EndpointState.IDLE)

  val endpointQueueLength: MutableStateFlow<Int> = MutableStateFlow(0)

  val serviceStartedDate: MutableStateFlow<Instant> = MutableStateFlow(Instant.now())

  suspend fun setState(newEndpointState: EndpointState) {
    val oldState = endpointState.value
    Timber.v(
        "Setting endpoint state $newEndpointState called from: ${
            Thread.currentThread().stackTrace[3].run {
                "$className: $methodName"
            }
            }")
    Timber.tag("OT-DEBUG").d("EndpointState changed: $oldState -> $newEndpointState")
    RemoteDebugLogger.log("ENDPOINT_STATE_CHANGE", "EndpointState changed: $oldState -> $newEndpointState", mapOf("old" to oldState.toString(), "new" to newEndpointState.toString()))
    endpointState.emit(newEndpointState)
  }

  suspend fun setQueueLength(queueLength: Int) {
    Timber.v("Setting queuelength=$queueLength")
    endpointQueueLength.emit(queueLength)
  }

  suspend fun setServiceStartedNow() {
    serviceStartedDate.emit(Instant.now())
  }
}
