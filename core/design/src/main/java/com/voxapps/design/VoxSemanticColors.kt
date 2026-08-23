package com.voxapps.design

import androidx.compose.ui.graphics.Color

/**
 * Colours that mean something rather than match something.
 *
 * These are the ones deliberately kept out of the theme: "done" stays green and "important" stays
 * amber whatever palette the user picked, because their job is to be recognised at a glance rather
 * than to belong. That argument is sound — but it was made separately in each file that needed one,
 * so the same idea existed as `Color(0xFF2E7D32)` in one place, `Color(0xFF43A047)` in another and
 * `Color(0xFF4CAF50)` in a third, none of which knew about the others.
 *
 * Anything that *should* follow the theme belongs in `MaterialTheme.colorScheme` instead — the
 * now-line uses `error`, selection uses `primary`. This file is only for the fixed few.
 */
object VoxSemanticColors {

    /** A finished thing. Green regardless of theme — the one colour everyone already reads as done. */
    val done: Color = Color(0xFF2E7D32)

    /**
     * A finished item's own surface, and the line around it.
     *
     * Flat grey and flat black, carrying nothing of the colour the item was given. A tint of that
     * colour — a wash, a fade, a desaturation — says "this is still that item, quieter", and a list
     * of them reads as a list of dimmed items rather than a list of finished ones. Grey says the
     * item is spent; the black keeps it legible while saying it, which a faded outline cannot.
     *
     * Thickness is nobody's business here: what a border means is set by the item being important,
     * not by its being done, so done changes the colour and never the weight.
     */
    val doneFill: Color = Color(0xFFBDBDBD)
    val doneOutline: Color = Color(0xFF000000)

    /** A thing marked important. Amber for the same reason: it has to stand out from the item's own
     *  colour, which the user chose and which could be anything. */
    val important: Color = Color(0xFFF9A825)

    /** The toggle that *sets* importance, on and off. Red rather than amber, because a control is
     *  not the state it produces — and the off state has to read as "not set" without vanishing. */
    val importantToggleOn: Color = Color(0xFFE53935)
    val importantToggleOff: Color = Color(0xFF8B1A1A)

    /**
     * A value the app is offering because it has one it believes — a re-read, a correction it has
     * seen before, an answer some rung was not allowed to write. Accepting it fills a field.
     */
    val offered: Color = Color(0xFF4CAF50)

    /**
     * A value the app cannot identify and is asking about. Amber for the same reason [important] is,
     * and deliberately not [offered]: accepting one of these teaches something that changes how
     * later messages are read, and it must not look like accepting a value the app already trusts.
     */
    val asked: Color = Color(0xFFF9A825)

    /** Today, when a surface is tinted to say so — the week grid's column. Fades rather than fills;
     *  see the alphas at the call site. */
    val today: Color = Color(0xFF4CAF50)
}
