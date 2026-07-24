package com.voxapps.ipc

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoxLlmRequestQueueTest {

    private lateinit var dao: PendingLlmRequestDao
    private lateinit var sentRequests: MutableList<Triple<VoxLlmRequest, String, Long>>
    private lateinit var queue: VoxLlmRequestQueue

    @Before
    fun setup() {
        dao = mockk(relaxed = true)
        sentRequests = mutableListOf()
        queue = VoxLlmRequestQueue(dao) { _, request, targetPackage ->
            sentRequests.add(Triple(request, targetPackage, System.currentTimeMillis()))
        }
    }

    @Test
    fun `enqueueAndSend persists a row before dispatching`() = runTest {
        queue.enqueueAndSend(
            context = mockk(relaxed = true),
            sourcePackage = "com.voxapps.expenses",
            task = "NOTIFICATION_EXPENSE_PARSE",
            promptText = "prompt",
            targetPackage = "com.voxapps.commander"
        )

        coVerify(exactly = 1) {
            dao.insert(
                match { it.attemptCount == 1 && it.targetPackage == "com.voxapps.commander" }
            )
        }
        assertEquals(1, sentRequests.size)
    }

    @Test
    fun `enqueueAndSend appends a UUID requestId to the task and returns it`() = runTest {
        val requestId = queue.enqueueAndSend(
            context = mockk(relaxed = true),
            sourcePackage = "com.voxapps.expenses",
            task = "NOTIFICATION_EXPENSE_PARSE:encodedKey:encodedBank",
            promptText = "prompt",
            targetPackage = "com.voxapps.commander"
        )

        val sentTask = sentRequests.single().first.task
        assertEquals("NOTIFICATION_EXPENSE_PARSE:encodedKey:encodedBank:$requestId", sentTask)

        val (originalTask, extractedId) = VoxLlmRequestQueue.splitRequestId(sentTask)
        assertEquals("NOTIFICATION_EXPENSE_PARSE:encodedKey:encodedBank", originalTask)
        assertEquals(requestId, extractedId)
    }

    @Test
    fun `splitRequestId leaves a task with no requestId segment untouched`() {
        val (task, requestId) = VoxLlmRequestQueue.splitRequestId("CATEGORY_DEDUPLICATION")
        assertEquals("CATEGORY_DEDUPLICATION", task)
        assertNull(requestId)
    }

    @Test
    fun `splitRequestId does not mistake a non-UUID trailing segment for a requestId`() {
        val (task, requestId) = VoxLlmRequestQueue.splitRequestId("NOTIFICATION_EXPENSE_PARSE:encodedKey:encodedBank")
        assertEquals("NOTIFICATION_EXPENSE_PARSE:encodedKey:encodedBank", task)
        assertNull(requestId)
    }

    @Test
    fun `markFulfilled deletes the row for that requestId`() = runTest {
        queue.markFulfilled("some-request-id")
        coVerify(exactly = 1) { dao.deleteByRequestId("some-request-id") }
    }

    @Test
    fun `retryStale re-dispatches every stale row and increments its attempt count`() = runTest {
        val request = VoxLlmRequest(
            sourcePackage = "com.voxapps.expenses",
            task = "NOTIFICATION_EXPENSE_PARSE:abc",
            promptText = "prompt"
        )
        val entry = PendingLlmRequestEntity(
            requestId = "req-1",
            payloadJson = request.toJson(),
            targetPackage = "com.voxapps.commander",
            createdAt = 1000L,
            attemptCount = 1,
            lastAttemptAt = 1000L
        )
        coEvery { dao.getStale(any(), any()) } returns listOf(entry)

        queue.retryStale(context = mockk(relaxed = true), staleAfterMillis = 5 * 60_000L, maxAttempts = 50)

        coVerify(exactly = 1) { dao.incrementAttempt("req-1", any()) }
        assertEquals(1, sentRequests.size)
        assertEquals("NOTIFICATION_EXPENSE_PARSE:abc", sentRequests.single().first.task)
    }

    @Test
    fun `retryStale skips a row whose payload no longer parses`() = runTest {
        val entry = PendingLlmRequestEntity(
            requestId = "req-broken",
            payloadJson = "not valid json",
            targetPackage = "com.voxapps.commander",
            createdAt = 1000L,
            attemptCount = 1,
            lastAttemptAt = 1000L
        )
        coEvery { dao.getStale(any(), any()) } returns listOf(entry)

        queue.retryStale(context = mockk(relaxed = true), staleAfterMillis = 5 * 60_000L, maxAttempts = 50)

        assertTrue(sentRequests.isEmpty())
        coVerify(exactly = 0) { dao.incrementAttempt(any(), any()) }
    }

    @Test
    fun `enqueueAndSend generates a well-formed non-blank requestId`() = runTest {
        val requestId = queue.enqueueAndSend(
            context = mockk(relaxed = true),
            sourcePackage = "com.voxapps.expenses",
            task = "EXPENSE_SCAN_CLEANUP",
            promptText = "prompt",
            targetPackage = "com.voxapps.commander"
        )
        assertNotNull(requestId)
        assertTrue(requestId.isNotBlank())
    }
}
