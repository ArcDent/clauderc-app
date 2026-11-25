package clauderc.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The four looks of the app (design/ui-mockup/demo.html):
 * - 「浮墨」: near-black floating pills and orbs with high-contrast monochrome actions (default).
 * - 「素卡」: warm grey paper with white rounded bento cards and a floating pill nav bar.
 * - 「清玻」: white or pure black paper with frosted glass bars and sheets over content.
 * - 「印版」: high-contrast letterpress print on cotton paper with 8 selectable inks and square corners.
 */
enum class UiStyle(val label: String) {
    Ink("浮墨"),
    Tile("素卡"),
    Clear("清玻"),
    Plate("印版"),
    ;

    companion object {
        /** Reads stored names including legacy styles before 浮墨 / 素卡 / 清玻 / 印版. */
        fun fromStored(name: String?): UiStyle? = when (name) {
            "Minimal" -> Clear
            "Grid" -> Plate
            "Card", "Sticker" -> Tile
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** 跟随系统 / 浅色 / 深色. */
enum class ThemeMode { System, Light, Dark }

/** Accent choice for 浮墨 / 素卡 / 清玻: style default, wallpaper dynamic colour (Android 12+), or one of eight presets. */
enum class AccentChoice(val rgb: Long?) {
    Default(null),
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

/** 印版's eight ink colours (design/ui-mockup/demo.html INKS), defaulting to high-contrast carbon black. */
enum class PlateInk(val label: String, val lightInk: Long, val darkInk: Long, val darkPaper: Long) {
    Black("墨黑", 0x0B0B0D, 0xF5F4EE, 0x0A0A0C),
    Ultra("群青", 0x163096, 0xCDD8FF, 0x0A0D18),
    Red("朱红", 0x9A1419, 0xFFBFBF, 0x140B0C),
    Green("松绿", 0x0D4D32, 0xB0EBD0, 0x09130E),
    Violet("靛紫", 0x461C8F, 0xDAC6FF, 0x0F0B17),
    Ochre("赭石", 0x853108, 0xFFC6A3, 0x140E09),
    Rose("茜红", 0x8F1246, 0xFFB8D3, 0x150A0F),
    Olive("橄榄", 0x5E4200, 0xF5DC87, 0x121008),
}

/** 印版's bottom navigation style: floating square frame with sliding ink block, or full-width docked bar. */
enum class PlateNavStyle(val label: String) {
    Float("方形悬浮"),
    Dock("贴底通栏"),
}

data class Appearance(
    val style: UiStyle = UiStyle.Ink,
    val mode: ThemeMode = ThemeMode.System,
    val accent: AccentChoice = AccentChoice.Default,
    val plateInk: PlateInk = PlateInk.Black,
    val plateNav: PlateNavStyle = PlateNavStyle.Float,
)

/** What the user picked in 设置 for the look of the app, kept on this phone. */
class ThemeStore(private val store: DataStore<Preferences>) {
    private val styleKey = stringPreferencesKey("appearance|style")
    private val modeKey = stringPreferencesKey("appearance|mode")
    private val accentKey = stringPreferencesKey("appearance|accent")
    private val plateInkKey = stringPreferencesKey("appearance|plateInk")
    private val plateNavKey = stringPreferencesKey("appearance|plateNav")

    val appearance: Flow<Appearance> = store.data.map { prefs ->
        val default = Appearance()
        Appearance(
            style = UiStyle.fromStored(prefs[styleKey]) ?: default.style,
            mode = ThemeMode.entries.firstOrNull { it.name == prefs[modeKey] } ?: default.mode,
            accent = AccentChoice.entries.firstOrNull { it.name == prefs[accentKey] } ?: default.accent,
            plateInk = PlateInk.entries.firstOrNull { it.name == prefs[plateInkKey] } ?: default.plateInk,
            plateNav = PlateNavStyle.entries.firstOrNull { it.name == prefs[plateNavKey] } ?: default.plateNav,
        )
    }

    suspend fun setStyle(style: UiStyle) = store.edit { it[styleKey] = style.name }

    suspend fun setMode(mode: ThemeMode) = store.edit { it[modeKey] = mode.name }

    suspend fun setAccent(accent: AccentChoice) = store.edit { it[accentKey] = accent.name }

    suspend fun setPlateInk(ink: PlateInk) = store.edit { it[plateInkKey] = ink.name }

    suspend fun setPlateNav(nav: PlateNavStyle) = store.edit { it[plateNavKey] = nav.name }
}
