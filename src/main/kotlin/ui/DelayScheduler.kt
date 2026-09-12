package io.github.nemf1s.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun interface CancelHandle {
    fun cancel()
}

fun interface DelayScheduler {
    fun schedule(delayMillis: Long, block: () -> Unit): CancelHandle
}

class CoroutineDelayScheduler(
    private val scope: CoroutineScope,
) : DelayScheduler {
    override fun schedule(delayMillis: Long, block: () -> Unit): CancelHandle {
        val job = scope.launch {
            delay(delayMillis)
            block()
        }
        return CancelHandle { job.cancel() }
    }
}
