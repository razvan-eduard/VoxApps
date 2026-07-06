package com.voxcommander.app.domain.model

import com.voxcommander.app.data.remote.RemoteModelItem
import com.voxcommander.app.data.remote.VirtualModelItem
import com.voxcommander.app.domain.intent.interpreter.LlmModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppModelTest {

    @Test
    fun `RemoteModelItem with is_remote=false is built-in`() {
        val item = RemoteModelItem(
            id = "alexa_v0.1",
            label = "Alexa",
            path = "openwakeword/alexa_v0.1.onnx",
            size_mb = 1,
            engine_type = "wake_openwakeword",
            is_remote = false
        )
        assertTrue(item.isBuiltIn)
    }

    @Test
    fun `RemoteModelItem with is_remote=true is not built-in`() {
        val item = RemoteModelItem(
            id = "base",
            label = "Whisper Base",
            path = "models/base.bin",
            size_mb = 74,
            engine_type = "stt_whisper",
            is_remote = true
        )
        assertFalse(item.isBuiltIn)
    }

    @Test
    fun `VirtualModelItem is always built-in by default`() {
        val item = VirtualModelItem(
            id = "porcupine_builtin_alexa",
            label = "Alexa",
            engineType = "wake_porcupine"
        )
        assertTrue(item.isBuiltIn)
    }

    @Test
    fun `VirtualModelItem isBuiltIn can be overridden`() {
        val item = VirtualModelItem(
            id = "custom",
            label = "Custom",
            engineType = "wake_porcupine",
            isBuiltIn = false
        )
        assertFalse(item.isBuiltIn)
    }

    @Test
    fun `LlmModelInfo is never built-in`() {
        val item = LlmModelInfo(
            id = "qwen2.5-1.5b-q8",
            label = "Qwen 2.5 1.5B",
            sizeDescription = "1.5 GB",
            url = "https://example.com/model.gguf",
            engineTypeTag = "MEDIAPIPE_GENAI"
        )
        assertFalse(item.isBuiltIn)
    }

    @Test
    fun `RemoteModelItem derives url from path`() {
        val item = RemoteModelItem(
            id = "test",
            label = "Test",
            path = "openwakeword/test.onnx",
            size_mb = 1,
            is_remote = false
        )
        assertEquals("openwakeword/test.onnx", item.url)
    }

    @Test
    fun `RemoteModelItem sizeDescription uses size_label when provided`() {
        val item = RemoteModelItem(
            id = "test",
            label = "Test",
            path = "test.onnx",
            size_mb = 1,
            size_label = "1.2 MB"
        )
        assertEquals("1.2 MB", item.sizeDescription)
    }

    @Test
    fun `RemoteModelItem sizeDescription falls back to size_mb`() {
        val item = RemoteModelItem(
            id = "test",
            label = "Test",
            path = "test.onnx",
            size_mb = 74
        )
        assertEquals("74 MB", item.sizeDescription)
    }

    @Test
    fun `VirtualModelItem default sizeDescription is Cloud API`() {
        val item = VirtualModelItem(
            id = "google",
            label = "Google",
            engineType = "stt_google"
        )
        assertEquals("Cloud API", item.sizeDescription)
    }

    @Test
    fun `RemoteModelItem engineType defaults to empty string when null`() {
        val item = RemoteModelItem(
            id = "test",
            label = "Test",
            path = "test.onnx",
            size_mb = 1,
            engine_type = null
        )
        assertEquals("", item.engineType)
    }

    @Test
    fun `RemoteModelItem langCode is passed through`() {
        val item = RemoteModelItem(
            id = "test",
            label = "Test",
            path = "test.onnx",
            size_mb = 1,
            lang_code = "ro"
        )
        assertEquals("ro", item.langCode)
    }
}
