package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.data.UiStyle
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily

/**
 * Form text field styled per [UiStyle] (demo.html `.f-inp` and `.f-ta`):
 * - 浮墨: 16dp rounded dark/warm well with subtle edge and monochrome inverse focus border.
 * - 素卡: 14dp rounded surface well with accent focus border.
 * - 清玻: 12dp rounded surface well with accent focus border.
 * - 印版: 0dp square paper box with 1.5dp ink border and 3dp hard shadow on focus.
 * Single-line fields sit centered in at least 42dp; multi-line fields align top in at least 76dp.
 */
@Composable
fun UnderlineField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    error: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    enabled: Boolean = true,
    fontSize: TextUnit = if (mono) 14.sp else 15.sp,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = !mono),
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val c = Clauderc.colors
    val ui = Clauderc.style
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = when (ui) {
        UiStyle.Plate -> RectangleShape
        UiStyle.Ink -> RoundedCornerShape(16.dp)
        UiStyle.Tile -> RoundedCornerShape(14.dp)
        UiStyle.Clear -> RoundedCornerShape(12.dp)
    }
    val fill = when {
        ui == UiStyle.Plate -> c.bg
        ui == UiStyle.Ink -> c.surface2
        ui == UiStyle.Clear && c.isDark -> c.surface2.copy(alpha = 0.65f)
        else -> c.surface
    }
    val focusColor = when (ui) {
        UiStyle.Plate -> c.fg
        UiStyle.Ink -> c.inverse
        UiStyle.Tile, UiStyle.Clear -> c.accent
    }
    val borderColor = when {
        error -> c.danger
        !enabled && ui == UiStyle.Plate -> c.fg3
        ui == UiStyle.Plate -> c.fg
        focused -> focusColor
        ui == UiStyle.Ink -> c.edge
        ui == UiStyle.Clear && c.isDark -> c.surface2
        else -> c.line
    }
    val cursorColor = when {
        error -> c.danger
        ui == UiStyle.Plate -> c.fg
        ui == UiStyle.Ink -> c.fg
        else -> c.accent
    }
    val selection = remember(cursorColor) {
        TextSelectionColors(handleColor = cursorColor, backgroundColor = cursorColor.copy(alpha = 0.24f))
    }
    val textLineHeight = if (singleLine) TextUnit.Unspecified else 1.5.em
    CompositionLocalProvider(LocalTextSelectionColors provides selection) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            interactionSource = interaction,
            keyboardOptions = keyboardOptions,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(cursorColor),
            textStyle = TextStyle(
                color = if (enabled) c.fg else c.fg3,
                fontSize = fontSize,
                fontWeight = if (ui == UiStyle.Plate) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = if (mono) MonoFamily else null,
                lineHeight = textLineHeight,
            ),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (singleLine) 42.dp else 76.dp)
                        .then(
                            if (ui == UiStyle.Plate && focused && enabled) {
                                Modifier.plateHardShadow(if (error) c.danger else c.fg, 3.dp)
                            } else {
                                Modifier
                            },
                        )
                        .clip(shape)
                        .background(fill)
                        .border(1.5.dp, borderColor, shape)
                        .padding(horizontal = 12.dp, vertical = if (singleLine) 9.dp else 10.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            color = c.fg3,
                            fontSize = fontSize,
                            fontFamily = if (mono) MonoFamily else null,
                            lineHeight = textLineHeight,
                            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** .f-lbl: form field label over an input. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        fontSize = if (mono) Clauderc.fontSizes.monoSm else Clauderc.fontSizes.caption,
        fontWeight = if (Clauderc.style == UiStyle.Plate) FontWeight.ExtraBold else FontWeight.Medium,
        fontFamily = if (mono) MonoFamily else null,
        color = Clauderc.colors.fg2,
    )
}

/** .ferr */
@Composable
fun FieldError(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = Clauderc.fontSizes.caption, color = Clauderc.colors.danger)
}
