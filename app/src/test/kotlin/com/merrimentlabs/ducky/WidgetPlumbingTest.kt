package com.merrimentlabs.ducky

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.merrimentlabs.ducky.widget.BubblesWidget
import com.merrimentlabs.ducky.widget.BubblesWidgetReceiver
import com.merrimentlabs.ducky.widget.EggTimerWidget
import com.merrimentlabs.ducky.widget.EggTimerWidgetReceiver
import com.merrimentlabs.ducky.widget.PondWidget
import com.merrimentlabs.ducky.widget.PondWidgetReceiver
import com.merrimentlabs.ducky.widget.TimerWidget
import com.merrimentlabs.ducky.widget.TimerWidgetReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The provider plumbing: one receiver per widget class (Glance finds widgets by class, and R8 once
 * merged the look-only subclasses so every widget drew lily pads), and `refreshAll`, which must give
 * every provider its turn even when one throws or hangs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetPlumbingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Coroutines' debug stack-trace recovery may rethrow a copy whose cause is the original. */
    private fun Throwable.unrecovered(): Throwable = generateSequence(this) { it.cause }.last()

    /** What attemptEach added as suppressed, on whichever copy in the chain it landed. */
    private fun Throwable.suppressedMessages(): List<String?> =
        generateSequence(this) { it.cause }.flatMap { it.suppressed.asSequence() }.map { it.message }.toList()

    @Test
    fun `each receiver serves its own widget class`() {
        assertEquals(TimerWidget::class, TimerWidgetReceiver().glanceAppWidget::class)
        assertEquals(PondWidget::class, PondWidgetReceiver().glanceAppWidget::class)
        assertEquals(BubblesWidget::class, BubblesWidgetReceiver().glanceAppWidget::class)
        assertEquals(EggTimerWidget::class, EggTimerWidgetReceiver().glanceAppWidget::class)
    }

    @Test
    fun `refreshAll covers the four providers, the tank first`() {
        assertEquals(
            listOf(TimerWidget::class, PondWidget::class, BubblesWidget::class, EggTimerWidget::class),
            TimerWidget.all().map { it::class },
        )
    }

    @Test
    fun `refreshAll with no widgets placed completes`() = runBlocking {
        withTimeout(10_000L) { TimerWidget.refreshAll(context) }
    }

    @Test
    fun `a hung provider times out and the next one still gets its turn`() = runBlocking {
        val ran = mutableListOf<Int>()
        try {
            TimerWidget.attemptEach(
                listOf({ ran += 0; awaitCancellation() }, { ran += 1 }),
                perAttemptMs = 50L,
            )
            fail("the timeout should be reported")
        } catch (e: IllegalStateException) {
            assertEquals("widget update timed out", e.message)
        }
        assertEquals(listOf(0, 1), ran)
    }

    @Test
    fun `the first failure is thrown with the later ones suppressed, after every attempt ran`() = runBlocking {
        val first = IllegalArgumentException("tank")
        val ran = mutableListOf<Int>()
        try {
            TimerWidget.attemptEach(
                listOf(
                    { ran += 0; throw first },
                    { ran += 1 },
                    { ran += 2; throw IllegalStateException("eggs") },
                    { ran += 3; awaitCancellation() },
                ),
                perAttemptMs = 50L,
            )
            fail("the failure should be rethrown")
        } catch (e: IllegalArgumentException) {
            assertSame(first, e.unrecovered())
            assertEquals(listOf("eggs", "widget update timed out"), e.suppressedMessages())
        }
        assertEquals(listOf(0, 1, 2, 3), ran)
    }

    @Test
    fun `a timeout first, then a failure, reports the timeout with the failure suppressed`() = runBlocking {
        try {
            TimerWidget.attemptEach(
                listOf({ awaitCancellation() }, { throw IllegalStateException("pond") }),
                perAttemptMs = 50L,
            )
            fail("should throw")
        } catch (e: IllegalStateException) {
            assertEquals("widget update timed out", e.message)
            assertEquals(listOf("pond"), e.suppressedMessages())
        }
    }

    @Test
    fun `cancellation is not swallowed as a provider failure`() = runBlocking {
        val ran = mutableListOf<Int>()
        try {
            TimerWidget.attemptEach(listOf({ ran += 0; throw CancellationException("caller gone") }, { ran += 1 }))
            fail("cancellation should propagate")
        } catch (e: CancellationException) {
            assertEquals("caller gone", e.message)
        }
        // It stops at once: the caller was canceled, so nobody wants the rest.
        assertEquals(listOf(0), ran)
    }

    @Test
    fun `all attempts succeeding throws nothing`() = runBlocking {
        val ran = mutableListOf<Int>()
        TimerWidget.attemptEach(listOf({ ran += 0 }, { ran += 1 }))
        assertTrue(ran == listOf(0, 1))
    }
}
