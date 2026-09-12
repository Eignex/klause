package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutInputRow
import com.eignex.klause.lp.engine.CutRowTransform
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutProvenance
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEpochProofTest {
    @Test
    fun `cancellation interrupts fixed substitution within a wide row`() {
        val size = 64
        val problem = Problem(
            0, size, Array(size) { IntDomain(0, 1) },
            arrayOf(
            Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
        )
        )
        val session = PropagationSession(problem)
        repeat(size) { session.implyIntAtMost(it, 0) }
        val plain = CpToLpRelaxation(problem, null).build(session)
        var checks = 0

        val result = RelaxationTidy.apply(
            plain,
            RelaxationTidyScope(
                problem,
                assertNotNull(plain.sourceMap).epoch,
                null,
                true,
                searchRoot = assertNotNull(LpEpochRoot.capture(session)),
            ),
            RelaxationTidyConfig(enabled = true, cancellation = Cancellation { ++checks >= 16 }),
        )

        assertEquals(RelaxationTidyDecline.CANCELLED, assertIs<RelaxationTidyResult.Declined>(result).reason)
        assertEquals(0, result.stats.applied(RelaxationTidyRule.FIXED_SUBSTITUTION))
        assertNull(plain.tidyProof)
    }

    @Test
    fun `cancellation during validation leaves a checked proof reusable`() {
        val size = 32
        val problem = Problem(
            0, size, Array(size) { IntDomain(0, 1) },
            arrayOf(
            Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
        )
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
            plain,
            RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
            RelaxationTidyConfig(enabled = true),
        )
        )
        var checks = 0

        val proof = LpEpochProof.create(
            tidy.derivation,
            assertNotNull(tidy.relaxation.sourceMap),
            Cancellation { ++checks >= 16 },
        )

        assertNull(proof)
        assertEquals(16, checks)
        assertTrue(tidy.derivation.validate())
        assertNotNull(tidy.relaxation.tidyProof?.rowProof(0))
    }

    @Test
    fun `cancellation interrupts basis remapping without consuming the donor basis`() {
        val size = 32
        val problem = Problem(
            0, size, Array(size) { IntDomain(0, 1) },
            arrayOf(
            Linear(IntArray(size) { 1 }, IntArray(size) { it }, LinearOp.LE, size / 2),
        )
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
            plain,
            RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
            RelaxationTidyConfig(enabled = true),
        )
        ).relaxation
        val basis = Basis(intArrayOf(size), Array(size + 1) { if (it == size) VarStatus.BASIC else VarStatus.AT_LOWER })
        var checks = 0

        val mapped = LpEpochState.remapBasis(tidy, tidy, basis, Cancellation { ++checks >= 16 })

        assertNull(mapped)
        assertEquals(16, checks)
        assertEquals(size, basis.basicVars.single())
        assertNotNull(LpEpochState.remapBasis(tidy, tidy, basis))
    }

    @Test
    fun `conditional substitution retains its premises after exact recentering`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(-3, 5), IntDomain(-2, 4)),
            arrayOf(Linear(intArrayOf(3, 2, 3), intArrayOf(0, 1, 2), LinearOp.LE, 13)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 2, 0))
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        val root = assertNotNull(LpEpochRoot.capture(session))
        val plain = CpToLpRelaxation(problem, objective).build(session)
        val result = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, objective, true, searchRoot = root),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        assertTrue(result.derivation.rowMaps.any { it.fixings.any { fixing -> !fixing.global } })
        session.implyIntAtLeast(1, -1)
        val rebound = LpEngine(problem, objective, LpParams(), SolveStatsSink(backend = "epoch-proof")).use {
            assertNotNull(it.cpAdapter.relaxation(result.relaxation, session))
        }
        assertSame(result.derivation, rebound.tidyDerivation)
        assertNotNull(rebound.tidyProof)
        val literals = IntArrayList()
        assertTrue(
            LpExplanation.addRowPremiseLits(
                literals,
                IntHashSet(),
                rebound,
                IntArray(rebound.model.m) { it },
                session,
            ),
        )
        assertTrue(session.boundGeLit(0, 1, positive = false) in literals.toIntArray())
        assertTrue(session.boundLeLit(0, 1, positive = false) in literals.toIntArray())
        assertFalse(assertNotNull(rebound.tidyProof.rowProof(0)).global)
        assertEquals(1, assertNotNull(rebound.tidyProof.rowProof(0)).transformations.size)
        assertFalse(rebound.tidyProof.active(PropagationSession(problem)))
    }

    @Test
    fun `a transformed model cannot lose its proof on incompatible replacement`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 5) },
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 8)),
        )
        val session = PropagationSession(problem)
        val plain = CpToLpRelaxation(problem, null).build(session)
        val result = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        val foreign = CpToLpRelaxation(
            Problem(0, 2, Array(2) { IntDomain(-3, 5) }, problem.factors),
            null,
        ).build(session)
        assertFailsWith<IllegalArgumentException> { result.relaxation.withModel(foreign.model, foreign.sourceMap) }
    }

    @Test
    fun `a lattice cut retains substitution and rounding through source remapping`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 1), IntDomain(0, 5)),
            arrayOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.LE, 7)),
        )
        val plain = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(problem, assertNotNull(plain.sourceMap).epoch, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val row = 0
        val columns = (0 until tidy.model.n).filter { column ->
            var present = false
            tidy.model.forEachInColumn(column) { index, value -> if (index == row && value != 0L) present = true }
            present
        }.toIntArray()
        val coefficients = LongArray(columns.size) { k ->
            var coefficient = 0L
            tidy.model.forEachInColumn(columns[k]) { index, value -> if (index == row) coefficient = value }
            coefficient
        }
        val cut = Cut(
            columns,
            coefficients,
            Relation.LE,
            tidy.model.flippedRhs[row],
            global = true,
            tableau = TableauCutProvenance(
                tidy.model,
                emptyList(),
                listOf(
                    CutInputRow(
                        row,
                        true,
                        1L,
                        BigFraction.ofLong(tidy.model.flippedRhs[row]),
                        Relation.LE,
                        columns,
                        coefficients,
                        null,
                    ),
                ),
                1L,
                false,
            ),
        )

        val portable = assertNotNull(SourceCut.fromCut(cut, tidy).orNull())
        val restored = assertNotNull(portable.toCut(assertNotNull(plain.sourceMap)).orNull())

        val algebraic = assertIs<CutRowTransform.Algebraic>(portable.provenance.transformations[0])
        val lattice = assertIs<CutRowTransform.Lattice>(portable.provenance.transformations[1])
        assertEquals(algebraic.conclusion, lattice.input)
        assertEquals(1, algebraic.fixings.size)
        assertEquals(portable.provenance.transformations, assertNotNull(restored.provenance).transformations)
        for (y in 0L..5L) {
            if (2L + 3L * y > 7L) continue
            val assignment = longArrayOf(1L, y)
            val original = algebraic.input.expression.value { BigFraction.ofLong(assignment[it.id]) }
            val fixed = algebraic.conclusion.expression.value { BigFraction.ofLong(assignment[it.id]) }
            val rounded = lattice.conclusion.expression.value { BigFraction.ofLong(assignment[it.id]) }
            assertTrue(original <= algebraic.input.rhs)
            assertTrue(fixed <= algebraic.conclusion.rhs)
            assertTrue(rounded <= lattice.conclusion.rhs)
        }
    }

    @Test
    fun `target exclusion preserves both conditional fixing sides used by a row`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 3), IntDomain(0, 5), IntDomain(0, 20)),
            arrayOf(Linear(intArrayOf(2, 3, -1), intArrayOf(0, 1, 2), LinearOp.LE, 10)),
        )
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 1))
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        session.implyIntAtMost(2, 10)
        val plain = CpToLpRelaxation(problem, objective).build(session)
        val tidy = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                plain,
                RelaxationTidyScope(
                    problem,
                    assertNotNull(plain.sourceMap).epoch,
                    objective,
                    true,
                    searchRoot = assertNotNull(LpEpochRoot.capture(session)),
                ),
                RelaxationTidyConfig(enabled = true),
            ),
        ).relaxation
        val certificate = assertNotNull(integerCertify(tidy.model, DoubleArray(tidy.model.m) { -1.0 }, scaleBits = 0))

        val reason = assertNotNull(
            reducedCostFixingReasons(tidy, certificate, session, 2, 10),
        ).reasonFor(tidy.intColOf[0])

        assertTrue(session.boundGeLit(0, 1, positive = false) in reason)
        assertTrue(session.boundLeLit(0, 1, positive = false) in reason)
        assertTrue(session.boundLeLit(2, 10, positive = false) in reason)
    }

    @Test
    fun `aliased source columns retain the other certificate endpoint`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 1)
        session.implyIntAtMost(0, 1)
        val builder = LpBuilder()
        builder.addVar(1L, 1L)
        builder.addVar(1L, 1L)
        builder.addVar(2L, 10L, cost = 1L)
        builder.addRow(intArrayOf(0, 1, 2), longArrayOf(1L, 1L, -1L), Relation.LE, 0L)
        val model = builder.build(Sense.MINIMIZE)
        val relaxation = LpRelaxation(model, intArrayOf(0, 0, 1), BooleanArray(3), 0L, intArrayOf(0, 2), intArrayOf())
        val certificate = assertNotNull(integerCertify(model, doubleArrayOf(-1.0), scaleBits = 0))

        val reasons = assertNotNull(reducedCostFixingReasons(relaxation, certificate, session, 1, 10))

        assertTrue(session.boundGeLit(0, 1, positive = false) in reasons.reasonFor(0))
        assertTrue(session.boundGeLit(0, 1, positive = false) in reasons.reasonFor(1))
    }
}
