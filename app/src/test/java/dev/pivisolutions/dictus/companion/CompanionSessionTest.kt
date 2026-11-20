package dev.pivisolutions.dictus.companion

import dev.pivisolutions.dictus.core.service.TranscriptionOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionSessionTest {
    @Test
    fun `successful session emits every phase and inserts once`() = runTest {
        val fake = SessionFake(this)
        val statuses = mutableListOf<CompanionStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            fake.session.state.collect { statuses += it }
        }

        assertTrue(fake.session.start())
        runCurrent()
        assertEquals(CompanionStatus(CompanionPhase.PREPARING), fake.status)
        fake.prepared.complete(Unit)
        runCurrent()
        fake.session.stop()
        runCurrent()
        assertEquals(CompanionStatus(CompanionPhase.TRANSCRIBING), fake.status)
        fake.recognized.complete("hello world")
        runCurrent()

        assertEquals(
            listOf(
                CompanionStatus(),
                CompanionStatus(CompanionPhase.PREPARING),
                CompanionStatus(CompanionPhase.RECORDING),
                CompanionStatus(CompanionPhase.TRANSCRIBING),
                CompanionStatus(CompanionPhase.INSERTING, "hello world"),
                CompanionStatus(CompanionPhase.COMPLETED, message = "inserted"),
            ),
            statuses,
        )
        assertEquals(listOf("hello world"), fake.insertions)
        assertEquals(1, fake.beginCalls)
        assertEquals(1, fake.transcribeCalls)
        assertEquals(1, fake.releaseCalls)
        assertEquals(0, fake.abortCalls)
    }

    @Test
    fun `cancel during preparation prevents recording and insertion`() = runTest {
        val fake = SessionFake(this)
        fake.session.start()
        runCurrent()

        fake.session.cancel()
        fake.prepared.complete(Unit)
        runCurrent()
        fake.session.stop()

        assertEquals(CompanionStatus(CompanionPhase.CANCELLED), fake.status)
        assertEquals(1, fake.abortCalls)
        assertTrue(fake.releaseCalls > 0)
        assertEquals(0, fake.transcribeCalls)
        assertTrue(fake.insertions.isEmpty())
    }

    @Test
    fun `cancel during recording prevents transcription and insertion`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        assertEquals(CompanionPhase.RECORDING, fake.status.phase)

        fake.session.cancel()
        fake.session.stop()
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.CANCELLED), fake.status)
        assertEquals(1, fake.abortCalls)
        assertTrue(fake.releaseCalls > 0)
        assertEquals(0, fake.transcribeCalls)
        assertTrue(fake.insertions.isEmpty())
        assertTrue(fake.copies.isEmpty())
    }

    @Test
    fun `cancel during transcription discards pending result`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()
        assertEquals(1, fake.transcribeCalls)

        fake.session.cancel()
        fake.recognized.complete("discard me")
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.CANCELLED), fake.status)
        assertEquals(1, fake.abortCalls)
        // Cancellation and the transcription finally block may both release resources.
        assertTrue(fake.releaseCalls > 0)
        assertTrue(fake.insertions.isEmpty())
        assertTrue(fake.copies.isEmpty())
    }

    @Test
    fun `begin failure reports start error and cleans up`() = runTest {
        val fake = SessionFake(this)
        fake.session.start()
        runCurrent()
        fake.prepared.completeExceptionally(IllegalStateException("microphone unavailable"))
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "start"), fake.status)
        assertEquals(1, fake.abortCalls)
        assertEquals(1, fake.releaseCalls)
        assertEquals(0, fake.transcribeCalls)
        assertTrue(fake.insertions.isEmpty())
    }

    @Test
    fun `recognition exception reports error and releases without inserting`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()
        fake.recognized.completeExceptionally(IllegalStateException("recognizer unavailable"))
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "recognition"), fake.status)
        assertEquals(1, fake.releaseCalls)
        assertTrue(fake.insertions.isEmpty())
        assertTrue(fake.copies.isEmpty())
    }

    @Test
    fun `null empty and whitespace recognition results never insert`() = runTest {
        for (text in listOf(null, "", " \n\t")) {
            val fake = SessionFake(this)
            fake.autoCopy = true
            fake.prepared.complete(Unit)
            fake.recognized.complete(text)
            fake.session.start()
            runCurrent()
            fake.session.stop()
            runCurrent()

            assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "recognition"), fake.status)
            assertEquals(1, fake.releaseCalls)
            assertTrue(fake.insertions.isEmpty())
            assertTrue(fake.copies.isEmpty())
        }
    }

    @Test
    fun `rapid repeated start and stop stay single flight through job completions`() = runTest {
        val fake = SessionFake(this)
        val startResults = mutableListOf<Boolean>()
        var beginCompletions = 0
        var recognitionCompletions = 0
        fake.onBegin = {
            currentCoroutineContext().job.invokeOnCompletion {
                beginCompletions++
                repeat(3) { startResults += fake.session.start() }
                repeat(3) { fake.session.stop() }
            }
        }
        fake.onTranscribe = {
            currentCoroutineContext().job.invokeOnCompletion {
                recognitionCompletions++
                repeat(3) { fake.session.stop() }
                fake.session.retryInsertion()
            }
        }

        assertTrue(fake.session.start())
        repeat(3) {
            assertFalse(fake.session.start())
            fake.session.stop()
        }
        runCurrent()
        repeat(3) {
            assertFalse(fake.session.start())
            fake.session.stop()
        }
        fake.prepared.complete(Unit)
        runCurrent()
        assertEquals(1, beginCompletions)
        assertEquals(listOf(false, false, false), startResults)
        assertEquals(CompanionPhase.TRANSCRIBING, fake.status.phase)
        repeat(3) {
            assertFalse(fake.session.start())
            fake.session.stop()
        }
        fake.recognized.complete("one insertion")
        runCurrent()
        repeat(3) { fake.session.stop() }

        assertEquals(1, recognitionCompletions)
        assertEquals(1, fake.beginCalls)
        assertEquals(1, fake.transcribeCalls)
        assertEquals(listOf("one insertion"), fake.insertions)
        assertEquals(CompanionStatus(CompanionPhase.COMPLETED, message = "inserted"), fake.status)
    }

    @Test
    fun `manual results accumulate across recordings and cancellation until copied`() = runTest {
        val fake = SessionFake(this)
        fake.insertResult = false
        fake.prepared.complete(Unit)
        for (text in listOf("first", "second")) {
            fake.recognize = { text }
            assertTrue(fake.session.start())
            runCurrent()
            fake.session.stop()
            runCurrent()
        }
        assertTrue(fake.copies.isEmpty())
        assertTrue(fake.session.start())
        runCurrent()
        fake.session.cancel()
        assertEquals("first\n\nsecond", fake.status.pendingText)
        assertTrue(fake.session.copyPending())
        assertEquals(listOf("first\n\nsecond"), fake.copies)
        assertEquals("first\n\nsecond", fake.status.lastCopied)
    }

    @Test
    fun `auto copy writes each completed take once and immediately readies microphone`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        for (text in listOf("first", "second", "second")) {
            fake.recognize = { text }
            assertTrue(fake.session.start())
            runCurrent()
            repeat(3) { fake.session.stop() }
            runCurrent()
            assertEquals(CompanionStatus(lastCopied = text), fake.status)
        }
        assertEquals(listOf("first", "second", "second"), fake.copies)
        assertTrue(fake.insertions.isEmpty())
        assertEquals(3, fake.releaseCalls)
    }

    @Test
    fun `partial recognition can never replace the last clipboard result`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        for (outcome in listOf(TranscriptionOutcome.Text("whole take"), TranscriptionOutcome.Partial("cut off"))) {
            fake.outcome = outcome
            assertTrue(fake.session.start())
            runCurrent()
            fake.session.stop()
            runCurrent()
        }
        assertEquals(CompanionPhase.ERROR, fake.status.phase)
        assertEquals("whole take", fake.status.lastCopied)
        assertEquals(listOf("whole take"), fake.copies)
        assertTrue(fake.insertions.isEmpty())
    }

    @Test
    fun `clipboard failure keeps original text when next auto copy succeeds`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        fake.prepared.complete(Unit)
        fake.copyFails = true
        fake.recognize = { "keep me" }
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()
        assertEquals("copy_failed", fake.status.message)
        assertFalse(fake.session.copyPending())
        fake.copyFails = false
        fake.recognize = { "new words" }
        assertTrue(fake.session.start())
        runCurrent()
        fake.session.stop()
        runCurrent()
        assertEquals(CompanionPhase.IDLE, fake.status.phase)
        assertEquals("keep me", fake.status.pendingText)
        assertEquals(listOf("new words"), fake.copies)
        assertTrue(fake.session.copyPending())
        assertEquals(listOf("new words", "keep me"), fake.copies)
    }

    @Test
    fun `successful insertion retry consumes retained result exactly once`() = runTest {
        val fake = SessionFake(this)
        fake.insertResult = false
        fake.prepared.complete(Unit)
        fake.recognized.complete("retry me")
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()

        fake.insertResult = true
        repeat(3) { fake.session.retryInsertion() }

        assertEquals(listOf("retry me", "retry me"), fake.insertions)
        assertEquals(CompanionStatus(CompanionPhase.COMPLETED, message = "inserted"), fake.status)
        assertEquals(1, fake.transcribeCalls)
    }

    @Test
    fun `throwing initial insertion retains transcript for later retry`() = runTest {
        val fake = SessionFake(this)
        fake.insertFailure = IllegalStateException("editor disappeared")
        fake.prepared.complete(Unit)
        fake.recognized.complete("keep these words")
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()

        assertEquals(
            CompanionStatus(CompanionPhase.ERROR, "keep these words", "recognition"),
            fake.status,
        )
        assertEquals(1, fake.releaseCalls)
        fake.insertFailure = null
        fake.session.retryInsertion()
        fake.session.retryInsertion()

        assertEquals(listOf("keep these words", "keep these words"), fake.insertions)
        assertEquals(CompanionStatus(CompanionPhase.COMPLETED, message = "inserted"), fake.status)
    }

    @Test
    fun `throwing retry keeps manual fallback available`() = runTest {
        val fake = SessionFake(this)
        fake.insertResult = false
        fake.prepared.complete(Unit)
        fake.recognized.complete("still available")
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()

        fake.insertFailure = IllegalStateException("editor disappeared")
        fake.session.retryInsertion()

        assertEquals(CompanionStatus(CompanionPhase.COMPLETED, "still available", "copy"), fake.status)
        assertTrue(fake.session.start())
        assertEquals(listOf("still available", "still available"), fake.insertions)
    }

    @Test
    fun `cancelled obsolete recognition completion cannot insert into replacement session`() = runTest {
        val fake = SessionFake(this)
        fake.autoCopy = true
        val obsoleteResult = CompletableDeferred<String?>()
        fake.recognize = { withContext(NonCancellable) { obsoleteResult.await() } }
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()

        fake.session.cancel()
        assertTrue(fake.session.start())
        runCurrent()
        val replacementStatus = fake.status
        assertEquals(CompanionPhase.RECORDING, replacementStatus.phase)
        val releasesBeforeObsoleteCompletion = fake.releaseCalls
        assertEquals(1, releasesBeforeObsoleteCompletion)
        obsoleteResult.complete("obsolete words")
        runCurrent()

        assertEquals(replacementStatus, fake.status)
        assertEquals(releasesBeforeObsoleteCompletion, fake.releaseCalls)
        assertTrue(fake.insertions.isEmpty())
        assertTrue(fake.copies.isEmpty())
        fake.recognize = { fake.recognized.await() }
        fake.recognized.complete("current words")
        fake.session.stop()
        runCurrent()

        assertEquals(2, fake.beginCalls)
        assertEquals(2, fake.transcribeCalls)
        assertEquals(listOf("current words"), fake.copies)
        assertTrue(fake.insertions.isEmpty())
        assertEquals(releasesBeforeObsoleteCompletion + 1, fake.releaseCalls)
        assertEquals(CompanionStatus(lastCopied = "current words"), fake.status)
    }

    @Test
    fun `cancelled obsolete preparation completion cannot change replacement phase`() = runTest {
        val fake = SessionFake(this)
        val obsoletePreparation = CompletableDeferred<Unit>()
        fake.prepare = { withContext(NonCancellable) { obsoletePreparation.await() } }
        fake.session.start()
        runCurrent()
        fake.session.cancel()
        fake.prepare = { fake.prepared.await() }
        assertTrue(fake.session.start())
        runCurrent()

        obsoletePreparation.complete(Unit)
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.PREPARING), fake.status)
        assertTrue(fake.insertions.isEmpty())
        fake.prepared.complete(Unit)
        runCurrent()
        assertEquals(CompanionPhase.RECORDING, fake.status.phase)
    }

    @Test
    fun `capture failure during recording cleans up once and prevents transcription`() = runTest {
        val fake = SessionFake(this)
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        assertEquals(CompanionPhase.RECORDING, fake.status.phase)

        repeat(3) { fake.session.fail() }
        fake.session.stop()
        fake.recognized.complete("must not insert")
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "capture"), fake.status)
        assertEquals(1, fake.abortCalls)
        assertEquals(1, fake.releaseCalls)
        assertEquals(0, fake.transcribeCalls)
        assertTrue(fake.insertions.isEmpty())
        assertTrue(fake.session.start())
        runCurrent()
        assertEquals(CompanionPhase.RECORDING, fake.status.phase)
    }

    @Test
    fun `capture failure during transcription rejects late noncancellable result`() = runTest {
        val fake = SessionFake(this)
        val lateResult = CompletableDeferred<String?>()
        fake.recognize = { withContext(NonCancellable) { lateResult.await() } }
        fake.prepared.complete(Unit)
        fake.session.start()
        runCurrent()
        fake.session.stop()
        runCurrent()
        assertEquals(CompanionPhase.TRANSCRIBING, fake.status.phase)

        repeat(3) { fake.session.fail() }
        assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "capture"), fake.status)
        assertEquals(1, fake.abortCalls)
        assertEquals(1, fake.releaseCalls)
        lateResult.complete("must not insert")
        runCurrent()

        assertEquals(CompanionStatus(CompanionPhase.ERROR, message = "capture"), fake.status)
        assertEquals(1, fake.transcribeCalls)
        assertEquals(1, fake.abortCalls)
        assertEquals(1, fake.releaseCalls)
        assertTrue(fake.insertions.isEmpty())
    }

    @Test
    fun `repeated capture failure leaves idle and completed results unchanged`() = runTest {
        val idle = SessionFake(this)
        repeat(3) { idle.session.fail() }
        runCurrent()
        assertEquals(CompanionStatus(), idle.status)
        assertEquals(0, idle.beginCalls)
        assertEquals(0, idle.transcribeCalls)
        assertEquals(0, idle.abortCalls)
        assertEquals(0, idle.releaseCalls)
        assertTrue(idle.insertions.isEmpty())

        for (inserted in listOf(false, true)) {
            val fake = SessionFake(this)
            fake.insertResult = inserted
            fake.prepared.complete(Unit)
            fake.recognized.complete("completed words")
            fake.session.start()
            runCurrent()
            fake.session.stop()
            runCurrent()
            val completed = fake.status
            assertEquals(CompanionPhase.COMPLETED, completed.phase)

            repeat(3) { fake.session.fail() }
            runCurrent()

            assertEquals(completed, fake.status)
            assertEquals(0, fake.abortCalls)
            assertEquals(1, fake.releaseCalls)
            assertEquals(listOf("completed words"), fake.insertions)
        }
    }

    private class SessionFake(scope: CoroutineScope) {
        val prepared = CompletableDeferred<Unit>()
        val recognized = CompletableDeferred<String?>()
        val insertions = mutableListOf<String>()
        var beginCalls = 0
        var transcribeCalls = 0
        var abortCalls = 0
        var releaseCalls = 0
        val copies = mutableListOf<String>()
        var autoCopy = false
        var copyFails = false
        var outcome: TranscriptionOutcome? = null
        var insertResult = true
        var insertFailure: Exception? = null
        var onBegin: suspend () -> Unit = {}
        var onTranscribe: suspend () -> Unit = {}
        var prepare: suspend () -> Unit = { prepared.await() }
        var recognize: suspend () -> String? = { recognized.await() }

        val session = CompanionSession(
            scope = scope,
            begin = {
                beginCalls++
                onBegin()
                prepare()
            },
            transcribe = {
                transcribeCalls++
                onTranscribe()
                outcome ?: recognize()?.let(TranscriptionOutcome::Text)
            },
            insert = { text ->
                insertions += text
                insertFailure?.let { throw it }
                insertResult
            },
            autoCopy = { autoCopy },
            copyToClipboard = {
                if (copyFails) throw SecurityException("Clipboard unavailable")
                copies += it
            },
            abort = { abortCalls++ },
            release = { releaseCalls++ },
        )
        val status get() = session.state.value
    }
}
