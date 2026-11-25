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
    val CheckCircle by lazy {
        symbol(
            "check_circle",
            "m424-312.31 268.92-268.92-42.15-42.15L424-396.62l-114-114L267.85-468.46 424-312.31ZM480.07-100q-78.84 0-148.21-29.92t-120.68-81.21q-51.31-51.29-81.25-120.63Q100-401.1 100-479.93q0-78.84 29.92-148.21t81.21-120.68q51.29-51.31 120.63-81.25Q401.1-860 479.93-860q78.84 0 148.21 29.92t120.68 81.21q51.31 51.29 81.25 120.63Q860-558.9 860-480.07q0 78.84-29.92 148.21t-81.21 120.68q-51.29 51.31-120.63 81.25Q558.9-100 480.07-100Zm-.07-60q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Z",
        )
    }
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
    val Menu by lazy { symbol("menu", "M120-240v-80h720v80H120Zm0-200v-80h720v80H120Zm0-200v-80h720v80H120Z") }
    val MoreVert by lazy { symbol("more_vert", SymbolPaths.moreVert) }
    val OpenInBrowser by lazy { symbol("open_in_browser", SymbolPaths.openInBrowser) }
    val Palette by lazy { symbol("palette", SymbolPaths.palette) }
    val PhotoCamera by lazy { symbol("photo_camera", SymbolPaths.photoCamera) }
    val PowerSettingsNew by lazy { symbol("power_settings_new", SymbolPaths.powerSettingsNew) }
    val QrCodeScanner by lazy {
        symbol(
            "qr_code_scanner",
            "M80-680v-200h200v80H160v120H80Zm0 600v-200h80v120h120v80H80Zm600 0v-80h120v-120h80v200H680Zm120-600v-120H680v-80h200v200H800ZM700-260h60v60h-60v-60Zm0-120h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60-60h60v60h-60v-60Zm120-120h60v60h-60v-60Zm-60 60h60v60h-60v-60Zm-60-60h60v60h-60v-60Zm240-320v240H520v-240h240ZM440-440v240H200v-240h240Zm0-320v240H200v-240h240Zm-60 500v-120H260v120h120Zm0-320v-120H260v120h120Zm320 0v-120H580v120h120Z",
        )
    }
    val RadioChecked by lazy { symbol("radio_button_checked", SymbolPaths.radioButtonChecked) }
    val RadioUnchecked by lazy { symbol("radio_button_unchecked", SymbolPaths.radioButtonUnchecked) }
    val Refresh by lazy { symbol("refresh", SymbolPaths.refresh) }
    val Remove by lazy { symbol("remove", "M220-450v-60h520v60H220Z") }
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
