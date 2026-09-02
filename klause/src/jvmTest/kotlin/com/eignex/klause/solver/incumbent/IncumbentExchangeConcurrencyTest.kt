package com.eignex.klause.solver.incumbent

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The exchange under the parallel executor's concurrent publishers: every arm offers its own improving
 * series, and the exchange must hand exactly one publisher each version while keeping assignment and
 * objective paired.
 */
class IncumbentExchangeConcurrencyTest {

    private val arms = 4
    private val perArm = 200

    @Test
    fun `concurrent publishers each install a distinct version`() {
        val exchange = IncumbentExchange.minimizing<Int>()
        val installed = ConcurrentLinkedQueue<Long>()
        runArms { arm -> publishDescending(exchange, arm) { installed += it.incumbent.version } }
        assertEquals(installed.size, installed.toSet().size, "no version is installed twice")
        assertEquals(installed.max(), exchange.current()?.version, "the last version installed is the one standing")
    }

    @Test
    fun `concurrent publishers converge on the best offer`() {
        val exchange = IncumbentExchange.minimizing<Int>()
        runArms { arm -> publishDescending(exchange, arm) {} }
        assertEquals(1.0, exchange.current()?.objective, "the lowest objective offered by any arm stands")
    }

    @Test
    fun `an incumbent's assignment always matches its objective`() {
        val exchange = IncumbentExchange.minimizing<Int>()
        val mismatched = ConcurrentLinkedQueue<String>()
        runArms { arm ->
            publishDescending(exchange, arm) {}
            val standing = exchange.current()
            if (standing != null && standing.assignment.toDouble() != standing.objective) {
                mismatched += "${standing.assignment}/${standing.objective}"
            }
        }
        assertTrue(mismatched.isEmpty(), "assignment and objective must swap together, saw $mismatched")
    }

    /** Offer a strictly descending series ending at 1.0, tagging each assignment with its own objective. */
    private fun publishDescending(
        exchange: IncumbentExchange<Int, Double>,
        arm: Int,
        onInstalled: (Publication.Installed<Int, Double>) -> Unit,
    ) {
        for (step in perArm downTo 1) {
            val value = arm * perArm + step
            val publication = exchange.offer(value, value.toDouble())
            if (publication is Publication.Installed) onInstalled(publication)
        }
        val last = exchange.offer(1, 1.0)
        if (last is Publication.Installed) onInstalled(last)
    }

    /** Run [body] on [arms] threads released together, and join them. */
    private fun runArms(body: (Int) -> Unit) {
        val barrier = CyclicBarrier(arms)
        val threads = List(arms) { arm ->
            Thread {
                barrier.await()
                body(arm)
            }.apply { start() }
        }
        threads.forEach { it.join() }
    }
}
