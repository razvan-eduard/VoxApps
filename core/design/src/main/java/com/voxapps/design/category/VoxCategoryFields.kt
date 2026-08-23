package com.voxapps.design.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voxapps.design.color.VoxColorSwatchPicker
import com.voxapps.design.icon.VoxIconPickerDialog

/** What [VoxCategoryFields] needs said, in the caller's language. */
data class VoxCategoryFieldStrings(
    val nameLabel: String,
    val iconTitle: String,
    val iconNone: String,
    val iconCustom: String,
    val save: String,
    val cancel: String,
    val customColorTitle: String,
    val customColorUse: String,
    val customColorHue: String,
    val customColorSaturation: String,
    val customColorBrightness: String
)

/**
 * The three things that make a category: what it is called, what it looks like, what marks it.
 *
 * A category is created in more than one place — a settings screen where they are managed, and
 * wherever a record is being filed and the right one does not exist yet — and each place had its own
 * copy of these fields. Copies drift: a slot added to one is missing from the other, and which one
 * you happened to use decides what your category can carry.
 *
 * The container is the caller's. This is the body, so a settings card and a dialog can each hold it
 * without either dictating the other's shape. The icon dialog is owned here rather than by the
 * caller, since a caller that has to remember to host it is a caller that can forget.
 */
@Composable
fun VoxCategoryFields(
    name: String,
    onNameChange: (String) -> Unit,
    icon: String?,
    onIconChange: (String?) -> Unit,
    color: Long,
    onColorChange: (Long) -> Unit,
    strings: VoxCategoryFieldStrings,
    modifier: Modifier = Modifier
) {
    var pickingIcon by remember { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Offered whether or not one is set: a slot that only appears once something is in it is
            // a slot nothing can be put into.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { pickingIcon = true },
                contentAlignment = Alignment.Center
            ) {
                Text(icon ?: "＋", fontSize = if (icon != null) 22.sp else 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text(strings.nameLabel) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        VoxColorSwatchPicker(
            selectedColor = color,
            onColorSelected = onColorChange,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            customColorDialogTitle = strings.customColorTitle,
            customColorUseLabel = strings.customColorUse,
            customColorCancelLabel = strings.cancel,
            customColorHueLabel = strings.customColorHue,
            customColorSaturationLabel = strings.customColorSaturation,
            customColorBrightnessLabel = strings.customColorBrightness
        )
    }

    if (pickingIcon) {
        VoxIconPickerDialog(
            title = strings.iconTitle,
            selected = icon,
            onPick = { picked -> onIconChange(picked); pickingIcon = false },
            onDismiss = { pickingIcon = false },
            noneLabel = strings.iconNone,
            customLabel = strings.iconCustom,
            confirmLabel = strings.save,
            cancelLabel = strings.cancel
        )
    }
}
