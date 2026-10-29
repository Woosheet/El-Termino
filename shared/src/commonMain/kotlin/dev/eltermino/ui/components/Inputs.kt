package dev.eltermino.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.eltermino.ui.theme.Carta

/**
 * A labelled text field: label above, 1 dp border that turns accent on focus and red
 * on [error], with the error message below.
 */
@Composable
fun Field(
    label: String?,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    placeholder: String? = null,
    error: String? = null,
    leadingIcon: ImageVector? = null,
    fieldModifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = Carta.colors
    val borderColor = when {
        error != null -> colors.danger
        focused -> colors.accent
        else -> colors.line
    }
    val shape = RoundedCornerShape(8.dp)

    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        if (label != null) {
            Text(label, style = Carta.type.label, color = if (error != null) colors.danger else colors.textDim, modifier = Modifier.padding(bottom = 4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 4,
            textStyle = Carta.type.body.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    secret -> KeyboardType.Password
                    numeric -> KeyboardType.Number
                    else -> KeyboardType.Text
                },
                autoCorrectEnabled = false,
            ),
            interactionSource = interaction,
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 38.dp)
                        .background(colors.background, shape)
                        .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leadingIcon != null) {
                        Icon(leadingIcon, contentDescription = null, tint = colors.textDim, size = 18.dp, modifier = Modifier.padding(end = 8.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, color = colors.textDim, maxLines = if (singleLine) 1 else Int.MAX_VALUE)
                        }
                        inner()
                    }
                }
            },
        )
        if (error != null) {
            Text(error, style = Carta.type.label, color = colors.danger, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Field(label = null, value = value, onValueChange = onValueChange, modifier = modifier, placeholder = placeholder, leadingIcon = Icons.Default.Search)
}

@Composable
fun Checkbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = Carta.colors
    Pressable(
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        role = Role.Checkbox,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            val box = RoundedCornerShape(4.dp)
            Box(
                Modifier
                    .size(18.dp)
                    .background(if (checked) colors.accent else colors.background, box)
                    .border(1.dp, if (checked) colors.accent else colors.lineStrong, box),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Canvas(Modifier.size(12.dp)) {
                        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                        val path = Path().apply {
                            moveTo(size.width * 0.12f, size.height * 0.52f)
                            lineTo(size.width * 0.4f, size.height * 0.8f)
                            lineTo(size.width * 0.9f, size.height * 0.22f)
                        }
                        drawPath(path, colors.background, style = stroke)
                    }
                }
            }
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
