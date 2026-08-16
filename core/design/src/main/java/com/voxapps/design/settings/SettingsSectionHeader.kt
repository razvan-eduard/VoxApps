package com.voxapps.design.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The name of a group of settings — a subheader, not a band.
 *
 * This used to be a full-width strip in a darker surface colour, which separated the groups by
 * building a wall between them. Walls read louder than what they enclose: with several on a screen
 * the eye lands on the labels and the page stripes, and the switches and options they are supposed
 * to be organising become the quieter thing. Worse, a screen using them alongside dividers and
 * spacing ends up separating content three different ways at once.
 *
 * So the separation is space, and the label is only a label: accented, small, with enough room above
 * it that the gap does the dividing. A divider belongs above one of these only where the groups
 * either side are genuinely different kinds of thing — never floating between entries of one list.
 *
 * @param containerColor a background for callers that do want a filled band. Transparent by default,
 *  because the flat reading is the one that suits a settings page; a caller placing this over an
 *  image or inside a coloured sheet can pass one rather than reimplementing the header.
 */
@Composable
fun SettingsSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color.Transparent
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (containerColor == Color.Transparent) Modifier
                else Modifier.background(containerColor)
            )
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
    )
}
