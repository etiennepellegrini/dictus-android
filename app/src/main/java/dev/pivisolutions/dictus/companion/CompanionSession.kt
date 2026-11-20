package dev.pivisolutions.dictus.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CompanionPhase { IDLE, PREPARING, RECORDING, TRANSCRIBING, INSERTING, COMPLETED, ERROR, CANCELLED }

data class CompanionStatus(
    val phase: CompanionPhase = CompanionPhase.IDLE,
    val transcript: String? = null,
    val message: String? = null,
    // Uncopied text from earlier takes. Only the current transcript can be retried for insertion.
    val earlier: String? = null,
) {
    val pendingText: String? get() = when {
        earlier == null -> transcript
        transcript == null -> earlier
        else -> "$earlier\n\n$transcript"
    }
    val busy get() = phase in setOf(CompanionPhase.PREPARING, CompanionPhase.RECORDING,
        CompanionPhase.TRANSCRIBING, CompanionPhase.INSERTING)
}

/** Platform-free single-flight session. Uncopied results stay in memory across recordings. */
class CompanionSession(
    private val scope: CoroutineScope,
    private val begin: suspend () -> Unit,
    private val transcribe: suspend () -> String?,
    private val insert: (String) -> Boolean,
    private val abort: () -> Unit,
    private val release: () -> Unit,
    private val autoCopy: () -> Boolean,
    private val copyToClipboard: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(CompanionStatus())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var generation = 0L
    private var inserted = false

    private fun publish(phase: CompanionPhase, transcript: String? = null, message: String? = null) {
        mutableState.value = CompanionStatus(phase, transcript, message, state.value.earlier)
    }

    fun start(): Boolean {
        if (state.value.busy || operation?.isActive == true) return false
        val id = ++generation
        inserted = false
        mutableState.value = CompanionStatus(CompanionPhase.PREPARING, earlier = state.value.pendingText)
        operation = scope.launch {
            try {
                begin()
                if (generation == id) publish(CompanionPhase.RECORDING)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == id) {
                    abort()
                    release()
                    publish(CompanionPhase.ERROR, message = "start")
                }
            }
        }
        return true
    }

    fun stop() {
        if (state.value.phase != CompanionPhase.RECORDING) return
        val id = generation
        publish(CompanionPhase.TRANSCRIBING)
        operation = scope.launch {
            try {
                val text = transcribe()
                if (generation != id) return@launch
                if (text.isNullOrBlank()) {
                    publish(CompanionPhase.ERROR, message = "recognition")
                } else if (autoCopy()) {
                    if (writeClipboard(text)) publish(CompanionPhase.IDLE)
                    else publish(CompanionPhase.COMPLETED, text, "copy_failed")
                } else {
                    publish(CompanionPhase.INSERTING, text)
                    inserted = insert(text)
                    publish(CompanionPhase.COMPLETED,
                        transcript = if (inserted) null else text,
                        message = if (inserted) "inserted" else "copy")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == id) {
                    // Keep a result if a platform insertion operation failed unexpectedly.
                    mutableState.value = state.value.copy(phase = CompanionPhase.ERROR, message = "recognition")
                }
            } finally {
                // Cancellation already released this generation. A late native completion must
                // never release the lease belonging to a newer dictation session.
                if (generation == id) release()
            }
        }
    }

    private fun writeClipboard(text: String): Boolean = try {
        copyToClipboard(text)
        true
    } catch (_: RuntimeException) {
        false
    }

    fun copyPending(): Boolean {
        if (state.value.busy) return false
        val text = state.value.pendingText ?: return false
        if (!writeClipboard(text)) {
            mutableState.value = state.value.copy(message = "copy_failed")
            return false
        }
        mutableState.value = CompanionStatus()
        return true
    }

    fun retryInsertion() {
        val result = state.value.transcript ?: return
        if (state.value.busy || inserted || state.value.message == "copy_failed") return
        publish(CompanionPhase.INSERTING, result)
        inserted = runCatching { insert(result) }.getOrDefault(false)
        publish(CompanionPhase.COMPLETED,
            if (inserted) null else result, if (inserted) "inserted" else "copy")
    }

    fun cancel() {
        if (!state.value.busy) return
        ++generation
        operation?.cancel()
        abort()
        release()
        publish(CompanionPhase.CANCELLED)
    }

    fun fail() {
        if (!state.value.busy) return
        ++generation
        operation?.cancel()
        abort()
        release()
        publish(CompanionPhase.ERROR, message = "capture")
    }

    fun dismiss() {
        if (state.value.busy) return
        release()
        mutableState.value = CompanionStatus()
    }
}
