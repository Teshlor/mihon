package eu.kanade.tachiyomi.ui.reader.translation

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PageTranslationSchedulerTest {

    @Test
    fun `a cancelled ML Kit task is a failure of that job while the coroutine is still active`() = runTest {
        val outcome = attempt<Int> { throw CancellationException("Task was cancelled") }

        outcome.exceptionOrNull().shouldBeInstanceOf<CancellationException>()
    }

    @Test
    fun `a loop of attempts keeps going after a job throws a CancellationException`() = runTest {
        // The shape of recognizeLoop and translateLoop: each job's failure is handled, then the next.
        val queue = Channel<Int>(Channel.UNLIMITED)
        listOf(1, 2, 3).forEach { queue.trySend(it) }
        queue.close()
        val done = mutableListOf<Int>()
        val failed = mutableListOf<Int>()

        val loop = launch {
            for (job in queue) {
                attempt {
                    if (job == 2) throw CancellationException("Task was cancelled")
                    job
                }
                    .onSuccess { done += it }
                    .onFailure { failed += job }
            }
        }
        loop.join()

        done shouldContainExactly listOf(1, 3)
        failed shouldContainExactly listOf(2)
        loop.isCancelled shouldBe false
    }

    @Test
    fun `cancelling the loop's own coroutine still ends it`() = runTest {
        var carriedOn = false
        val loop = launch {
            attempt { awaitCancellation() }
            carriedOn = true
        }
        runCurrent()
        loop.cancel()
        loop.join()

        carriedOn shouldBe false
    }

    @Test
    fun `work failing because its coroutine was cancelled ends the loop instead of counting as a failure`() = runTest {
        // What a closed ML Kit client does: the work throws its own exception on the way out.
        var carriedOn = false
        val loop = launch {
            attempt {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    throw IllegalStateException("Recognizer closed")
                }
            }
            carriedOn = true
        }
        runCurrent()
        loop.cancel()
        loop.join()

        carriedOn shouldBe false
    }
}
