package com.voxapps.notes.data

/** Shared constants for the generic per-note attachments feature (see :core:attachments) — kept in
 *  one place since the dir name/authority/record-type tag need to match exactly wherever they're
 *  used (OcrResultReceiver staging, LlmResultReceiver cleanup, the attachments UI). */
object NotesAttachments {
    const val DIR = "attachments"
    const val FILE_PROVIDER_AUTHORITY = "com.voxapps.notes.fileprovider"
    const val RECORD_TYPE = "note"
}
