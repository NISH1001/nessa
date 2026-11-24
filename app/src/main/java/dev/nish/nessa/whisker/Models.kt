package dev.nish.nessa.whisker

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Shapes returned by Whisker's Litter-Robot 5 / EVO API (see pylitterbot robot/litterrobot5.py).
 * Everything is optional: Whisker adds, removes and nulls fields without notice.
 */
val WhiskerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

@Serializable
data class Robot(
    val serial: String,
    val type: String? = null,
    val name: String? = null,
    val timezone: String? = null,
    val state: RobotState? = null,
    val sleepSchedules: List<SleepEntry> = emptyList(),
    val nightLightSettings: NightLightSettings? = null,
    val panelSettings: PanelSettings? = null,
    val litterRobotSettings: LitterRobotSettings? = null,
)

@Serializable
data class RobotState(
    val powerStatus: String? = null,
    val isOnline: Boolean? = null,
    val isSleeping: Boolean? = null,
    val state: String? = null,
    val statusIndicator: StatusIndicator? = null,
    val dfiLevelPercent: Int? = null,
    val litterLevelPercent: Double? = null,
    val globeLitterLevelIndicator: String? = null,
    val wasteLabel: String? = null,
    val weightSensor: Double? = null,
    val isDrawerFull: Boolean? = null,
    val isDrawerRemoved: Boolean? = null,
    val isBonnetRemoved: Boolean? = null,
    val lastSeen: String? = null,
    val wifiRssi: Int? = null,
    val odometerCleanCycles: Int? = null,
)

@Serializable
data class StatusIndicator(val title: String? = null, val type: String? = null)

/**
 * One day of the robot's own sleep schedule. [dayOfWeek] 0 = Sunday; times are minutes from midnight,
 * robot's timezone. Defaults keep one damaged entry from breaking the whole robot; a [dayOfWeek] of -1
 * marks an entry Whisker sent without a day.
 */
@Serializable
data class SleepEntry(
    val dayOfWeek: Int = -1,
    val isEnabled: Boolean = false,
    val sleepTime: Int = 0,
    val wakeTime: Int = 0,
)

@Serializable
data class NightLightSettings(val mode: String? = null, val brightness: Int? = null, val color: String? = null)

@Serializable
data class PanelSettings(val displayIntensity: String? = null, val isKeypadLocked: Boolean? = null)

@Serializable
data class LitterRobotSettings(val cycleDelay: Int? = null, val isSmartWeightEnabled: Boolean? = null)

/** An entry in the robot's history. [petWeight] is pounds × 100; [duration] is seconds. */
@Serializable
data class Activity(
    val type: String = "",
    val timestamp: String = "",
    val petWeight: Double? = null,
    val duration: Int? = null,
    val petIds: List<String> = emptyList(),
    val subtype: String? = null,
)

enum class RobotCommand { CLEAN_CYCLE, POWER_ON, POWER_OFF }
