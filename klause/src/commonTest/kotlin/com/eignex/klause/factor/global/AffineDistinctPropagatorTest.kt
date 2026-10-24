package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.intdomain.intDomainFromSurvivors
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AffineDistinctPropagatorTest {
    @Test
    fun `matching should distinguish values beyond both signed word endpoints`() {
        for (offset in listOf("9223372036854775808", "-9223372036854775809")) {
            val factor = Distinct(
                (0..2).map { i ->
                Distinct.Operand(
                    intArrayOf(Term.ofIntVar(i)),
                    listOf(BigFraction.ONE),
                    BigFraction.of(parseBigInt(offset), BIG_ONE),
                )
            }
            )
            val problem = Problem(0, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf(factor))

            val session = PropagationSession(problem)

            assertEquals(2L, session.intDomain(2).min, offset)
        }
    }

    @Test
    fun `a wide value universe should preserve exact matching collisions`() {
        val factor = Distinct(
            (0..2).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE))
        }
        )
        val domain = intDomainFromSurvivors(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE))

        val session = PropagationSession(Problem(0, 3, Array(3) { domain }, arrayOf(factor)))

        assertTrue(session.isUnsatAtRoot)
    }

    @Test
    fun `bounds filtering should translate a Hall interval into source bounds`() {
        val factor = Distinct(
            (0..2).map { i ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(i.toLong()))
        }
        )
        val problem = Problem(0, 3, arrayOf(IntDomain(1, 2), IntDomain(0, 1), IntDomain(-1, 1000000)), arrayOf(factor))

        val session = PropagationSession(problem)

        assertFalse(-1L in session.intDomain(2))
        assertFalse(0L in session.intDomain(2))
    }

    @Test
    fun `matching reasons should exclude later bounds and holes`() {
        val factor = Distinct(
            listOf(0L, 1L, -1L, 20L).mapIndexed { i, offset ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(offset))
        }
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 4) }, arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.tightenIntMin(0, 1)
        state.tightenIntMax(0, 3)
        state.excludeIntValue(0, 2)
        state.tightenIntMax(1, 2)
        state.excludeIntValue(1, 1)
        state.currentFactor = 0
        assertTrue(state.factorAt(0).propagate(state, 0))
        val recorded = state.holeReasonFor(2, 2)
        state.currentFactor = -1
        state.currentLevel = 2
        state.tightenIntMax(0, 1)
        state.excludeIntValue(3, 2)

        val cited = requireNotNull(state.reasonOf(recorded)).map { lit ->
            val atom = Lit.variable(lit)
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }.toSet()

        assertEquals(
            setOf(
            Triple(0, AtomKind.GE, 1L),
            Triple(0, AtomKind.LE, 3L),
            Triple(0, AtomKind.EQ, 2L),
            Triple(1, AtomKind.LE, 2L),
            Triple(1, AtomKind.EQ, 1L),
        ),
            cited
        )
    }

    @Test
    fun `historical Hall reasons should ignore later holes in a source operand`() {
        val factor = Distinct(
            listOf(0L, 1L, -1L, 0L).mapIndexed { i, offset ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(offset))
        }
        )
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 4) }, arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.tightenIntMin(0, 1)
        state.tightenIntMax(0, 3)
        state.tightenIntMax(1, 2)
        state.tightenIntMin(2, 2)
        state.currentFactor = 0
        assertTrue(state.factorAt(0).propagate(state, 0))
        val recorded = state.holeReasonFor(3, 2)
        state.currentFactor = -1
        state.currentLevel = 2
        state.excludeIntValue(0, 2)
        state.tightenIntMax(1, 1)

        val cited = requireNotNull(state.reasonOf(recorded)).map { lit ->
            val atom = Lit.variable(lit)
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }.toSet()

        assertEquals(
            setOf(
            Triple(0, AtomKind.GE, 1L),
            Triple(0, AtomKind.LE, 3L),
            Triple(1, AtomKind.LE, 2L),
            Triple(2, AtomKind.GE, 2L),
        ),
            cited
        )
    }

    @Test
    fun `affine Hall reasons should imply each removed source value`() {
        val factor = Distinct(
            listOf(0L, 1L, -1L).mapIndexed { i, offset ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(offset))
        }
        )
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, arrayOf(factor))

        PropagationReasonOracle.assertReasonsImply(problem, "source Hall holes") { state ->
            state.tightenIntMin(0, 1) && state.excludeIntValue(0, 2) &&
                state.tightenIntMax(1, 2) && state.excludeIntValue(1, 1)
        }
    }

    @Test
    fun `Hall explanations should lift source bounds across other occupied values`() {
        val factor = Distinct(listOf(0L, 1L, 0L, 0L).mapIndexed { i, offset ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(offset))
        })
        val domains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4), IntDomain(-1, 4))
        val problem = Problem(0, 4, domains, arrayOf(factor))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        state.tightenIntMax(0, 1)
        state.tightenIntMax(1, 1)
        state.tightenIntMax(2, 2)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))
        val cited = requireNotNull(state.reasonOf(state.holeReasonFor(3, 0))).map { lit ->
            val atom = Lit.variable(lit)
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }.toSet()

        assertEquals(setOf(Triple(0, AtomKind.LE, 2L), Triple(1, AtomKind.LE, 1L), Triple(2, AtomKind.LE, 2L)), cited)
    }

    @Test
    fun `a Hall conflict should be implied by its source bounds and holes`() {
        val factor = Distinct(listOf(0L, 1L, -1L, 10L).mapIndexed { i, offset ->
            Distinct.Operand(intArrayOf(Term.ofIntVar(i)), listOf(BigFraction.ONE), BigFraction.ofLong(offset))
        })
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 3) }, arrayOf(factor))

        PropagationReasonOracle.assertReasonsImply(problem, "source Hall conflict") { state ->
            state.tightenIntMax(0, 1) && state.tightenIntMax(1, 0) &&
                state.tightenIntMin(2, 1) && state.tightenIntMax(2, 2)
        }
    }

    @Test
    fun `aliased affine operands should preserve their satisfying assignments`() {
        val factor = Distinct(
            listOf(
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE)),
            Distinct.Operand(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE), BigFraction.ONE),
            Distinct.Operand(intArrayOf(Term.ofIntVar(1)), listOf(BigFraction.ONE)),
        )
        )
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 3) }, arrayOf(factor))

        PropagationReasonOracle.assertReasonsImply(problem, "aliased source Hall") { state ->
            state.tightenIntMax(0, 1)
        }
    }
}
