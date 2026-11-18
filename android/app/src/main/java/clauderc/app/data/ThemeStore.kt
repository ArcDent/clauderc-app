package clauderc.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The three looks of the app, named as in 设置: 「清玻」, white with glass bars floating over the content;
 * 「格栅」, a Swiss grid with monospace and square corners; 「贴纸」, dotted paper with ink-outlined stickers.
 */
enum class UiStyle(val label: String) {
    Clear("清玻"),
    Grid("格栅"),
    Sticker("贴纸"),
    ;

    companion object {
        /** The stored name, including the two styles before 清玻 / 格栅 / 贴纸: 极简 became 清玻 and 卡片 became 贴纸. */
        fun fromStored(name: String?): UiStyle? = when (name) {
            "Minimal" -> Clear
            "Card" -> Sticker
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** 跟随系统 / 浅色 / 深色. */
enum class ThemeMode { System, Light, Dark }

/** 贴纸's accent: the wallpaper's colour (Android 12 and later) or one of eight presets. */
enum class AccentChoice(val rgb: Long?) {
    Wallpaper(null),
    Blue(0x007AFF),
    Purple(0xCB30E0),
    Pink(0xFF2D55),
    Red(0xFF383C),
    Orange(0xFF8D28),
    Yellow(0xFFCC00),
    Green(0x34C759),
    Gray(0x8E8E93),
}

data class Appearance(
    val style: UiStyle = UiStyle.Clear,
    val mode: ThemeMode = ThemeMode.System,
    val accent: AccentChoice = AccentChoice.Blue,
)

/** What the user picked in 设置 for the look of the app, kept on this phone. */
class ThemeStore(private val store: DataStore<Preferences>) {
    private val styleKey = stringPreferencesKey("appearance|style")
    private val modeKey = stringPreferencesKey("appearance|mode")
    private val accentKey = stringPreferencesKey("appearance|accent")

    val appearance: Flow<Appearance> = store.data.map { prefs ->
        val default = Appearance()
        Appearance(
            style = UiStyle.fromStored(prefs[styleKey]) ?: default.style,
            mode = ThemeMode.entries.firstOrNull { it.name == prefs[modeKey] } ?: default.mode,
            accent = AccentChoice.entries.firstOrNull { it.name == prefs[accentKey] } ?: default.accent,
        )
    }

    suspend fun setStyle(style: UiStyle) = store.edit { it[styleKey] = style.name }

    suspend fun setMode(mode: ThemeMode) = store.edit { it[modeKey] = mode.name }

    suspend fun setAccent(accent: AccentChoice) = store.edit { it[accentKey] = accent.name }
}
