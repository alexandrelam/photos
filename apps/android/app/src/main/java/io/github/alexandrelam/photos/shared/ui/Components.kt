package io.github.alexandrelam.photos.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Pill = RoundedCornerShape(percent = 50)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = Darkroom.colors
    Box(
        modifier = modifier
            .heightIn(min = 52.dp)
            .clip(Pill)
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Text(text, style = Darkroom.type.body, color = colors.onAccent)
        }
    }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    compact: Boolean = false,
) {
    val colors = Darkroom.colors
    Box(
        modifier = modifier
            .heightIn(min = if (compact) 44.dp else 52.dp)
            .clip(Pill)
            .border(1.dp, colors.border, Pill)
            .clickable(enabled = enabled && !loading, onClick = onClick)
            .padding(horizontal = if (compact) 18.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.text, strokeWidth = 2.dp)
        } else {
            Text(text, style = Darkroom.type.body, color = if (enabled) colors.text else colors.textMuted)
        }
    }
}

@Composable
fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Darkroom.colors
    Box(
        modifier = Modifier
            .clip(Pill)
            .then(if (selected) Modifier.background(colors.text) else Modifier.border(1.dp, colors.border, Pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, style = Darkroom.type.body.copy(fontSize = 13.sp), color = if (selected) colors.background else colors.text)
    }
}

@Composable
fun DarkroomSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = Darkroom.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = colors.accent,
            checkedThumbColor = colors.onAccent,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = colors.surfaceRaised,
            uncheckedThumbColor = colors.textSecondary,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Rounded card used for grouped rows (settings, phone albums). */
@Composable
fun DarkroomCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Darkroom.colors.surface)
            .padding(vertical = 4.dp),
        content = content,
    )
}

/** Small uppercase heading such as "ON THIS PHONE", with an optional icon and trailing text. */
@Composable
fun SectionLabel(text: String, icon: ImageVector? = null, trailing: String? = null, modifier: Modifier = Modifier) {
    val colors = Darkroom.colors
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text.uppercase(), style = Darkroom.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Darkroom.type.caption, color = colors.textSecondary)
    }
}

/** A row inside a [DarkroomCard]: title, optional subtitle, trailing slot. */
@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val colors = Darkroom.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, style = Darkroom.type.body, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, style = Darkroom.type.caption, color = subtitleColor ?: colors.textSecondary)
            }
        }
        trailing()
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    labelSuffix: String? = null,
    help: String? = null,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    val colors = Darkroom.colors
    Column(modifier.fillMaxWidth()) {
        Row {
            Text(label, style = Darkroom.type.caption.copy(fontWeight = Darkroom.type.body.fontWeight), color = colors.text)
            if (labelSuffix != null) Text(" · $labelSuffix", style = Darkroom.type.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = Darkroom.type.bodyRegular.copy(color = colors.text),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.surfaceRaised, RoundedCornerShape(14.dp))
                        .padding(PaddingValues(horizontal = 16.dp, vertical = 15.dp)),
                ) {
                    if (value.isEmpty()) Text(placeholder, style = Darkroom.type.bodyRegular, color = colors.textMuted)
                    inner()
                }
            },
        )
        if (help != null) {
            Spacer(Modifier.height(6.dp))
            Text(help, style = Darkroom.type.caption, color = colors.textSecondary)
        }
    }
}

/** Small round status dot (green when connected). */
@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
}
