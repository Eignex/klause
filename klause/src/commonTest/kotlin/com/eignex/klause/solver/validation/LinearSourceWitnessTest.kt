package com.eignex.klause.solver.validation

import com.eignex.klause.ir.LinearSourceIndicator
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinearSourceWitnessTest {
    @Test
    fun `absolute tolerance should use original rational row units`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(
                listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                lower = SemanticRational.of(BIG_ONE, bigIntOf(3)),
            )),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.of(BIG_ONE, bigIntOf(1_000_000)),
            )
            ).resolve(),
        )
        val value = third - BigFraction.ofLong(2_000_000).reciprocal()

        val result = checkLinearSourceWitness(source, listOf(value), Cancellation.Never)

        val witness = assertIs<LinearSourceCheck.Accepted>(result).witness
        assertEquals(listOf(value), witness.values)
        assertFalse(witness.exact)
        assertSame(source, witness.source)
    }

    @Test
    fun `relative tolerance should retain canceling original term magnitudes`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(
                LinearSourceRow(
                    listOf(
                LinearSourceTerm(0, SemanticRational.ONE),
                LinearSourceTerm(0, SemanticRational.ofLong(-1)),
            ),
                lower = SemanticRational.ONE
                )
            ),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                relative = SemanticRational.of(BIG_ONE, bigIntOf(10)),
            )
            ).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(10)), Cancellation.Never)

        assertFalse(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `tolerance should not relax integer integrality`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            semantics = SourceSemantics(
                SourceFormat.MPS,
                feasibilityTolerance = SourceTolerance(
                absolute = SemanticRational.ONE,
            )
            ).resolve(),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(2).reciprocal()), Cancellation.Never)

        assertIs<LinearSourceCheck.Rejected>(result)
    }

    @Test
    fun `inactive integer indicators should leave their row unconstrained`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("guard", true), LinearSourceVariable("x", false)),
            listOf(
                LinearSourceRow(
                listOf(LinearSourceTerm(1, SemanticRational.ONE)),
                lower = SemanticRational.ONE,
                indicator = LinearSourceIndicator(0, true),
            )
            ),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO, BigFraction.ZERO), Cancellation.Never)

        assertTrue(assertIs<LinearSourceCheck.Accepted>(result).witness.exact)
    }

    @Test
    fun `maximization should retain exact source coefficient and constant units`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true)),
            emptyList(),
            LinearSourceObjective(
                listOf(LinearSourceTerm(0, SemanticRational.of(BIG_ONE, bigIntOf(3)))),
                SemanticRational.of(BIG_ONE, bigIntOf(10)),
                ObjectiveSense.MAXIMIZE,
            ),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(3)), Cancellation.Never)

        assertEquals(BigFraction.ofLong(11) * BigFraction.ofLong(10).reciprocal(),
            assertIs<LinearSourceCheck.Accepted>(result).witness.objective)
    }

    @Test
    fun `a stopped point check should decline without rejecting the source`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList())

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO), Cancellation { true })

        assertIs<LinearSourceCheck.Declined>(result)
    }

    @Test
    fun `unmetered point checks should observe cancellation within a bounded source batch`() {
        val terms = List(128) { LinearSourceTerm(0, SemanticRational.ONE) }
        val sources = listOf(
            LinearSourceProblem(List(128) { LinearSourceVariable("x$it", false) }, emptyList()),
            LinearSourceProblem(
                listOf(LinearSourceVariable("x", false)),
                listOf(LinearSourceRow(terms, lower = SemanticRational.ZERO)),
            ),
            LinearSourceProblem(
                listOf(LinearSourceVariable("x", false)), emptyList(), LinearSourceObjective(terms),
            ),
        )
        for (source in sources) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun charge(units: Long) { charged += units }
                override fun isCancelled(): Boolean = charged > 0L
            }

            val result = checkLinearSourceWitness(source, List(source.variableValues.size) { BigFraction.ZERO }, stop)

            assertIs<LinearSourceCheck.Declined>(result)
            assertTrue(charged in 1L..64L)
        }
    }

    @Test
    fun `metered point checks should stop at the first exhausted source visit`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(List(128) { LinearSourceTerm(0, SemanticRational.ONE) })),
        )
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun workMeter(): WorkMeter = meter
            override fun isCancelled(): Boolean = charged >= 1L
        }

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO), stop)

        assertIs<LinearSourceCheck.Declined>(result)
        assertEquals(1L, charged)
    }

    @Test
    fun `a cancelled final short batch should not publish a point`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", false)), emptyList())
        var cancelled = false
        val stop = object : Cancellation {
            override fun charge(units: Long) { cancelled = true }
            override fun isCancelled(): Boolean = cancelled
        }

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO), stop)

        assertIs<LinearSourceCheck.Declined>(result)
    }

    @Test
    fun `cancellation should precede rejecting a point from a short batch`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("x", true)), emptyList())
        var cancelled = false
        val stop = object : Cancellation {
            override fun charge(units: Long) { cancelled = true }
            override fun isCancelled(): Boolean = cancelled
        }

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ofLong(2).reciprocal()), stop)

        assertIs<LinearSourceCheck.Declined>(result)
    }

    @Test
    fun `unmetered point checks should retain every original source work charge`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(List(128) { LinearSourceTerm(0, SemanticRational.ONE) })),
            LinearSourceObjective(List(2) { LinearSourceTerm(0, SemanticRational.ONE) }),
        )
        var charged = 0L
        val stop = object : Cancellation {
            override fun charge(units: Long) { charged += units }
            override fun isCancelled(): Boolean = false
        }

        checkLinearSourceWitness(source, listOf(BigFraction.ZERO), stop)

        assertEquals(132L, charged)
    }

    @Test
    fun `unmetered point checks should batch external predicate polls`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(List(128) { LinearSourceTerm(0, SemanticRational.ONE) })),
        )
        var polls = 0
        val stop = Cancellation { polls++; false }

        checkLinearSourceWitness(source, listOf(BigFraction.ZERO), stop)

        assertTrue(polls in 2..8)
    }

    @Test
    fun `unmetered point checks should reject a violation beyond the first batch`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false)),
            listOf(LinearSourceRow(
                List(128) { LinearSourceTerm(0, SemanticRational.ONE) }, lower = SemanticRational.ONE,
            )),
        )

        val result = checkLinearSourceWitness(source, listOf(BigFraction.ZERO), Cancellation.Never)

        assertIs<LinearSourceCheck.Rejected>(result)
    }
}
