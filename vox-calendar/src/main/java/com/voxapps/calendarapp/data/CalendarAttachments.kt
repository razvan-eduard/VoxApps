package com.voxapps.calendarapp.data

/** Shared constants for the generic per-entry manual attachments feature (see :core:attachments) —
 *  kept in one place since the dir name/authority/record-type tag need to match exactly wherever
 *  they're used. */
object CalendarAttachments {
    const val DIR = "attachments"
    const val FILE_PROVIDER_AUTHORITY = "com.voxapps.calendar.fileprovider"
    const val RECORD_TYPE = "calendar_entry"
}
