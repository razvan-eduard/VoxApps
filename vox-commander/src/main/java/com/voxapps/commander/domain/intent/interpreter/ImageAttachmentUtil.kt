package com.voxapps.commander.domain.intent.interpreter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.voxapps.logging.Logger
import java.io.ByteArrayOutputStream

/**
 * Shared image-reading helper for the multimodal engines ([OpenAiInterpreter], [GeminiCloudInterpreter]).
 * The caller (e.g. Expenses) must have already granted Commander read access to [imageUri] via
 * [android.content.Context.grantUriPermission] — this just resolves and decodes it.
 */
object ImageAttachmentUtil {

    private const val TAG = "ImageAttachmentUtil"

    /** Decodes [imageUri] into a [Bitmap], or null if it can't be read/decoded. */
    fun readBitmap(context: Context, imageUri: String): Bitmap? = try {
        context.contentResolver.openInputStream(Uri.parse(imageUri))?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        Logger.log("Failed to read image attachment: ${e.message}", TAG)
        null
    }

    /** [readBitmap] re-encoded as a base64 JPEG data URI (`data:image/jpeg;base64,...`), for APIs
     *  (e.g. OpenAI's chat completions) that take an inline data URI rather than a raw bitmap. */
    fun readAsBase64DataUri(context: Context, imageUri: String, quality: Int = 85): String? {
        val bitmap = readBitmap(context, imageUri) ?: return null
        val bytes = ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        }
        return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
    }
}
