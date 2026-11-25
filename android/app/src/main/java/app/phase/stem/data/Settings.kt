package app.phase.stem.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class GestureAction(val label: String) {
    TOGGLE_MUTE("Mute / unmute"),
    TOGGLE_SOLO("Solo / un-solo"),
    OPEN_EFFECTS("Open effects"),
    RESET_STEM("Reset stem"),
    NONE("Nothing"),
}

data class AppSettings(
    val serverUrl: String = "",
    val serverToken: String = "",
    val separationModel: String = "htdemucs",
    val autoSeparate: Boolean = true,
    val lowLatency: Boolean = true,
    val haptics: Boolean = true,
    val swipeLeft: GestureAction = GestureAction.TOGGLE_MUTE,
    val swipeRight: GestureAction = GestureAction.TOGGLE_SOLO,
    val doubleTap: GestureAction = GestureAction.TOGGLE_MUTE,
    val longPress: GestureAction = GestureAction.OPEN_EFFECTS,
    val verticalVolume: Boolean = true,
    val stemColors: List<Long> = DEFAULT_STEM_COLORS,
    val exportFormat: String = "wav",
    val mp3Bitrate: Int = 256,
    val recordFormat: String = "wav",
    val onlineLyrics: Boolean = true,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        val DEFAULT_STEM_COLORS = listOf(0xFFFFB547, 0xFFFF4D5E, 0xFF8B6CFF, 0xFF3EE6E0)
        val PALETTE = listOf(
            0xFFFFB547, 0xFFFF4D5E, 0xFF8B6CFF, 0xFF3EE6E0, 0xFF5BFF8F, 0xFFFF7AD9,
            0xFF4D9BFF, 0xFFF5F5F0, 0xFFFF8A3D, 0xFFC6FF3D,
        )
    }
}

/** SharedPreferences-backed settings exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("phase_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        fun gesture(key: String, def: GestureAction) =
            prefs.getString(key, null)?.let { runCatching { GestureAction.valueOf(it) }.getOrNull() } ?: def
        return AppSettings(
            serverUrl = prefs.getString("serverUrl", d.serverUrl)!!,
            serverToken = prefs.getString("serverToken", d.serverToken)!!,
            separationModel = prefs.getString("separationModel", d.separationModel)!!,
            autoSeparate = prefs.getBoolean("autoSeparate", d.autoSeparate),
            lowLatency = prefs.getBoolean("lowLatency", d.lowLatency),
            haptics = prefs.getBoolean("haptics", d.haptics),
            swipeLeft = gesture("swipeLeft", d.swipeLeft),
            swipeRight = gesture("swipeRight", d.swipeRight),
            doubleTap = gesture("doubleTap", d.doubleTap),
            longPress = gesture("longPress", d.longPress),
            verticalVolume = prefs.getBoolean("verticalVolume", d.verticalVolume),
            stemColors = prefs.getString("stemColors", null)?.split(",")?.mapNotNull { it.toLongOrNull() }
                ?.takeIf { it.size == 4 } ?: d.stemColors,
            exportFormat = prefs.getString("exportFormat", d.exportFormat)!!,
            mp3Bitrate = prefs.getInt("mp3Bitrate", d.mp3Bitrate),
            recordFormat = prefs.getString("recordFormat", d.recordFormat)!!,
            onlineLyrics = prefs.getBoolean("onlineLyrics", d.onlineLyrics),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putString("serverUrl", s.serverUrl.trim())
            .putString("serverToken", s.serverToken.trim())
            .putString("separationModel", s.separationModel)
            .putBoolean("autoSeparate", s.autoSeparate)
            .putBoolean("lowLatency", s.lowLatency)
            .putBoolean("haptics", s.haptics)
            .putString("swipeLeft", s.swipeLeft.name)
            .putString("swipeRight", s.swipeRight.name)
            .putString("doubleTap", s.doubleTap.name)
            .putString("longPress", s.longPress.name)
            .putBoolean("verticalVolume", s.verticalVolume)
            .putString("stemColors", s.stemColors.joinToString(","))
            .putString("exportFormat", s.exportFormat)
            .putInt("mp3Bitrate", s.mp3Bitrate)
            .putString("recordFormat", s.recordFormat)
            .putBoolean("onlineLyrics", s.onlineLyrics)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .apply()
    }
}
