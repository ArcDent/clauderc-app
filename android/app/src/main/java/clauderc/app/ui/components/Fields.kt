package clauderc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import clauderc.app.ui.theme.Clauderc
import clauderc.app.ui.theme.MonoFamily

/** .input: borderless text with a 1dp underline, 2dp accent when focused, 2dp danger on error. */
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
    fontSize: TextUnit = if (mono) 15.sp else Clauderc.fontSizes.body,
    keyboardOptions: KeyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = !mono),
) {
    val c = Clauderc.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lineColor = when {
        error -> c.danger
        focused -> c.accent
        // Inside the card style's cards the rule is a hairline.
        isCardStyle -> c.line
        else -> c.surface2
    }
    val thick = error || focused
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
        cursorBrush = SolidColor(c.accent),
        textStyle = TextStyle(
            color = c.fg,
            fontSize = fontSize,
            fontFamily = if (mono) MonoFamily else null,
            lineHeight = if (singleLine) TextUnit.Unspecified else 1.5.em,
        ),
        decorationBox = { inner ->
            Column {
                Box(Modifier.padding(vertical = 10.dp)) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = c.fg3, fontSize = fontSize, fontFamily = if (mono) MonoFamily else null)
                    }
                    inner()
                }
                Box(Modifier.fillMaxWidth().height(if (thick) 2.dp else 1.dp).background(lineColor))
            }
        },
    )
}

/** .field label */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        fontSize = if (mono) Clauderc.fontSizes.monoSm else Clauderc.fontSizes.caption,
        fontFamily = if (mono) MonoFamily else null,
        color = Clauderc.colors.fg2,
    )
}

/** .ferr */
@Composable
fun FieldError(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = Clauderc.fontSizes.caption, color = Clauderc.colors.danger)
}
