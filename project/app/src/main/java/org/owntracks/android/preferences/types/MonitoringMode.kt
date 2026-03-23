package org.owntracks.android.preferences.types

import com.fasterxml.jackson.annotation.JsonValue

enum class MonitoringMode(@JsonValue val value: Int) {
  Quiet(-1),
  Manual(0),
  Significant(1),
  Move(2),
  Adaptive(3);

  fun next(): MonitoringMode =
      when (this) {
        Quiet -> Manual
        Manual -> Significant
        Significant -> Move
        Move -> Adaptive
        Adaptive -> Quiet
      }

  companion object {
    @JvmStatic
    @FromConfiguration
    fun getByValue(value: Int): MonitoringMode =
        entries.firstOrNull { it.value == value } ?: Adaptive

    @JvmStatic
    @FromConfiguration
    fun getByValue(value: String): MonitoringMode =
        value.toIntOrNull()?.run(::getByValue)
            ?: entries.firstOrNull { it.name.equals(value, true) }
            ?: Adaptive
  }
}
