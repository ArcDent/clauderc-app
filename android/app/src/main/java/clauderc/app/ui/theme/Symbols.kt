package clauderc.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/** Material Symbols Outlined used by the mockup, built from [SymbolPaths]. */
object Symbols {
    val AccountTree by lazy { symbol("account_tree", SymbolPaths.accountTree) }
    val AccountTreeFilled by lazy { symbol("account_tree_filled", SymbolPaths.accountTreeFilled) }
    val Add by lazy { symbol("add", SymbolPaths.add) }
    val AltRoute by lazy { symbol("alt_route", SymbolPaths.altRoute) }
    val Animation by lazy { symbol("animation", SymbolPaths.animation) }
    val ArrowBack by lazy { symbol("arrow_back", SymbolPaths.arrowBack) }
    val ArrowUpward by lazy { symbol("arrow_upward", SymbolPaths.arrowUpward) }
    val Backspace by lazy { symbol("backspace", SymbolPaths.backspace) }
    val CallSplit by lazy { symbol("call_split", SymbolPaths.callSplit) }
    val Check by lazy { symbol("check", SymbolPaths.check) }
    val CheckBox by lazy { symbol("check_box", SymbolPaths.checkBox) }
    val CheckBoxOutlineBlank by lazy { symbol("check_box_outline_blank", SymbolPaths.checkBoxOutlineBlank) }
    val ChevronRight by lazy { symbol("chevron_right", SymbolPaths.chevronRight) }
    val Close by lazy { symbol("close", SymbolPaths.close) }
    val CloudOff by lazy { symbol("cloud_off", SymbolPaths.cloudOff) }
    val Computer by lazy { symbol("computer", SymbolPaths.computer) }
    val ComputerFilled by lazy { symbol("computer_filled", SymbolPaths.computerFilled) }
    val ContentCopy by lazy { symbol("content_copy", SymbolPaths.contentCopy) }
    val History by lazy { symbol("history", SymbolPaths.history) }
    /** history at weight 400, as the rewind button of 清玻 and 贴纸 draws it. */
    val HistoryRegular by lazy { symbol("history_regular", SymbolPaths.historyRegular) }
    val Contrast by lazy { symbol("contrast", SymbolPaths.contrast) }
    val Delete by lazy { symbol("delete", SymbolPaths.delete) }
    val Description by lazy { symbol("description", SymbolPaths.description) }
    val Dns by lazy { symbol("dns", SymbolPaths.dns) }
    val Download by lazy { symbol("download", SymbolPaths.download) }
    val Edit by lazy { symbol("edit", SymbolPaths.edit) }
    val Error by lazy { symbol("error", SymbolPaths.error) }
    val ErrorFilled by lazy { symbol("error_filled", SymbolPaths.errorFilled) }
    val ExpandMore by lazy { symbol("expand_more", SymbolPaths.expandMore) }
    val Folder by lazy { symbol("folder", SymbolPaths.folder) }
    val Forum by lazy { symbol("forum", SymbolPaths.forum) }
    val ForumFilled by lazy { symbol("forum_filled", SymbolPaths.forumFilled) }
    val Image by lazy { symbol("image", SymbolPaths.image) }
    val KeyboardArrowDown by lazy { symbol("keyboard_arrow_down", SymbolPaths.keyboardArrowDown) }
    val KeyboardArrowLeft by lazy { symbol("keyboard_arrow_left", SymbolPaths.keyboardArrowLeft) }
    val KeyboardArrowRight by lazy { symbol("keyboard_arrow_right", SymbolPaths.keyboardArrowRight) }
    val KeyboardArrowUp by lazy { symbol("keyboard_arrow_up", SymbolPaths.keyboardArrowUp) }
    val KeyboardReturn by lazy { symbol("keyboard_return", SymbolPaths.keyboardReturn) }
    val Mail by lazy { symbol("mail", SymbolPaths.mail) }
    val MoreVert by lazy { symbol("more_vert", SymbolPaths.moreVert) }
    val OpenInBrowser by lazy { symbol("open_in_browser", SymbolPaths.openInBrowser) }
    val Palette by lazy { symbol("palette", SymbolPaths.palette) }
    val PhotoCamera by lazy { symbol("photo_camera", SymbolPaths.photoCamera) }
    val PowerSettingsNew by lazy { symbol("power_settings_new", SymbolPaths.powerSettingsNew) }
    val RadioChecked by lazy { symbol("radio_button_checked", SymbolPaths.radioButtonChecked) }
    val RadioUnchecked by lazy { symbol("radio_button_unchecked", SymbolPaths.radioButtonUnchecked) }
    val Refresh by lazy { symbol("refresh", SymbolPaths.refresh) }
    val Settings by lazy { symbol("settings", SymbolPaths.settings) }
    val SettingsFilled by lazy { symbol("settings_filled", SymbolPaths.settingsFilled) }
    val Style by lazy { symbol("style", SymbolPaths.style) }
    val Terminal by lazy { symbol("terminal", SymbolPaths.terminal) }
    val Upload by lazy { symbol("upload", SymbolPaths.upload) }
    val Visibility by lazy { symbol("visibility", SymbolPaths.visibility) }
    val VisibilityOff by lazy { symbol("visibility_off", SymbolPaths.visibilityOff) }
    val Wallpaper by lazy { symbol("wallpaper", SymbolPaths.wallpaper) }
    val Warning by lazy { symbol("warning", SymbolPaths.warning) }
}

// Material Symbols SVGs use viewBox "0 -960 960 960".
private fun symbol(name: String, data: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).apply {
        group(translationY = 960f) {
            data.split('|').forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }
    }.build()
