package com.eignex.klause.lp.cut

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.engine.integerTableauCuts
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.cpCutSources
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceCutTest {
    private val x = CutSource(CutSourceKind.INTEGER, 0)
    private val y = CutSource(CutSourceKind.REAL, 0)
    private val term = CutSource(CutSourceKind.TERM, 7)
    private val modelToken = Any()

    @Test
    fun `slack expansion preserves shifted source inequality with repeated columns`() {
        val builder = LpBuilder()
        builder.addVar(-3, 5)
        builder.addVar(2, 8)
        builder.addRow(mapOf(0 to 2L, 1 to -1L), Relation.GE, -4)
        val model = builder.build(Sense.MINIMIZE)
        val sourceMap = CutSourceMap(modelToken, 1, listOf(CutColumnSource(x), CutColumnSource(y)))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0, -1),
            booleanArrayOf(false, false),
            0,
            intArrayOf(0),
            intArrayOf(),
            sourceMap = sourceMap,
        )
        val cut = Cut(intArrayOf(0, 0, model.slackCol(0)), longArrayOf(3, -2, 2), Relation.LE, 9, global = true)

        val source = assertNotNull(SourceCut.fromCut(cut, relaxation).orNull())

        for (xi in -3L..5L) {
            for (yi in 2L..8L) {
                val sourceLhs = source.expression.value { BigFraction.ofLong(if (it == x) xi else yi) }
                val slack = 4 + 2 * xi - yi
                assertEquals(BigFraction.ofLong(xi + 2 * slack), sourceLhs)
                assertEquals(xi + 2 * slack <= 9, sourceLhs <= source.rhs)
            }
        }
    }

    @Test
    fun `rational real and term coordinates remap exactly into source units`() {
        val half = BigFraction.of(BIG_ONE, bigIntOf(2))
        val proof = CutProvenance(modelToken, 3, emptyList())
        val source = SourceCut(
            CutExpression(mapOf(y to half, term to BigFraction.ofLong(3))),
            Relation.LE,
            BigFraction.ofLong(5),
            proof,
        )
        val map = CutSourceMap(
            modelToken,
            9,
            listOf(
                CutColumnSource(term, BigFraction.ofLong(-2), BigFraction.ONE),
                CutColumnSource(y, BigFraction.ofLong(2), BigFraction.ofLong(-3)),
            ),
        )

        val mapped = assertNotNull(source.toCut(map).orNull())

        for (real in -2L..2L) {
            for (t in -2L..2L) {
                val point = longArrayOf(-2 * t + 1, 2 * real - 3)
                val lhs = mapped.cols.indices.sumOf { mapped.coeffs[it] * point[mapped.cols[it]] }
                val original = half * BigFraction.ofLong(real) + BigFraction.ofLong(3 * t)
                assertEquals(original <= BigFraction.ofLong(5), lhs <= mapped.rhs)
            }
        }
        assertTrue(mapped.global)
    }

    @Test
    fun `local entries survive pop and remap without exposing stale columns`() {
        val guard = CutPremise.Bound(CutExpression(mapOf(x to BigFraction.ONE)), false, BigFraction.ofLong(2))
        val proof = CutProvenance(modelToken, 2, listOf(CutProofFact(guard, false)))
        val source = SourceCut(CutExpression(mapOf(x to BigFraction.ONE)), Relation.LE, BigFraction.ofLong(3), proof)
        val active = CutSourceMap(modelToken, 2, listOf(CutColumnSource(x)), activePremises = setOf(guard))
        val popped = CutSourceMap(modelToken, 3, listOf(CutColumnSource(y), CutColumnSource(x)))
        val sibling = CutSourceMap(modelToken, 4, popped.columns, activePremises = setOf(guard))
        val pool = CutPool()
        assertTrue(pool.add(source, active))

        assertEquals(mapOf(CutMappingDecline.INACTIVE_GUARD to 1), pool.remap(popped))
        assertTrue(pool.cuts().isEmpty())
        assertTrue(pool.select(doubleArrayOf(20.0, 20.0), doubleArrayOf(1.0, 1.0), 1).isEmpty())
        assertEquals(1, pool.size)
        assertTrue(pool.exportGlobalCuts().isEmpty())
        assertTrue(pool.remap(sibling).isEmpty())
        assertEquals(1, pool.cuts().single().cols.single())
        assertFalse(pool.cuts().single().global)
    }

    @Test
    fun `fixed substitution retains equality and declines when either bound is lost`() {
        val equality = CutPremise.Fixed(y, BigFraction.ofLong(2))
        val source = SourceCut(
            CutExpression(mapOf(x to BigFraction.ONE, y to BigFraction.ofLong(3))),
            Relation.LE,
            BigFraction.ofLong(9),
            CutProvenance(modelToken, 0, emptyList()),
        )
        val fixed = CutSourceMap(
            modelToken,
            1,
            listOf(CutColumnSource(x)),
            activePremises = setOf(equality),
            fixed = mapOf(y to BigFraction.ofLong(2)),
        )

        val mapped = assertNotNull(source.toCut(fixed).orNull())
        assertEquals(3L, mapped.rhs)
        assertFalse(mapped.global)
        assertTrue(checkNotNull(mapped.provenance).facts.contains(CutProofFact(equality, false)))
        for (upper in listOf(false, true)) {
            val oneSide = CutPremise.Bound(CutExpression(mapOf(y to BigFraction.ONE)), upper, BigFraction.ofLong(2))
            val lost = CutSourceMap(modelToken, 2, fixed.columns, activePremises = setOf(oneSide), fixed = fixed.fixed)
            assertEquals(CutMapping.Declined(CutMappingDecline.INACTIVE_GUARD), source.toCut(lost))
        }
    }

    @Test
    fun `objective cutoff guards prevent use after objective replacement or cutoff relaxation`() {
        val objective = CutExpression(mapOf(y to BigFraction.ONE))
        val cutoff = CutPremise.ObjectiveCutoff(objective, BigFraction.ofLong(4))
        val source = SourceCut(
            CutExpression(mapOf(y to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ofLong(4),
            CutProvenance(modelToken, 0, listOf(CutProofFact(cutoff, false))),
        )
        val columns = listOf(CutColumnSource(y))
        val active = CutSourceMap(modelToken, 1, columns, activePremises = setOf(cutoff))
        val changed = listOf(
            emptySet(),
            setOf(CutPremise.ObjectiveCutoff(objective, BigFraction.ofLong(5))),
            setOf(CutPremise.ObjectiveCutoff(CutExpression(mapOf(x to BigFraction.ONE)), BigFraction.ofLong(4))),
        )

        assertNotNull(source.toCut(active).orNull())
        for (premises in changed) {
            assertEquals(
                CutMapping.Declined(CutMappingDecline.INACTIVE_GUARD),
                source.toCut(CutSourceMap(modelToken, 2, columns, activePremises = premises)),
            )
        }
    }

    @Test
    fun `different guarded derivations of one inequality remain separate`() {
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val lower = CutPremise.Bound(expression, false, BigFraction.ZERO)
        val upper = CutPremise.Bound(expression, true, BigFraction.ofLong(4))
        val pool = CutPool()
        val map = CutSourceMap(modelToken, 0, listOf(CutColumnSource(x)), activePremises = setOf(lower))
        for (guard in listOf(lower, upper)) {
            val proof = CutProvenance(modelToken, 0, listOf(CutProofFact(guard, false)))
            assertTrue(pool.add(SourceCut(expression, Relation.LE, BigFraction.ofLong(3), proof), map))
        }

        assertEquals(2, pool.size)
        assertEquals(1, pool.cuts().size)
        assertTrue(pool.exportGlobalCuts().isEmpty())
    }

    @Test
    fun `proof and expression snapshots resist mutation through producers and readers`() {
        val terms = mutableMapOf(x to BigFraction.ONE)
        val expression = CutExpression(terms)
        val facts = mutableListOf(CutProofFact(CutPremise.Bound(expression, false, BigFraction.ZERO), false))
        val assumptions = mutableSetOf("region")
        val proof = CutProvenance(modelToken, 0, facts, assumptions)
        val source = SourceCut(expression, Relation.LE, BigFraction.ONE, proof)
        terms.clear()
        facts.clear()
        assumptions.clear()
        runCatching { (proof.facts as? MutableList)?.clear() }
        runCatching { (expression.terms as? MutableMap)?.clear() }

        assertEquals(mapOf(x to BigFraction.ONE), source.expression.terms)
        assertEquals(1, proof.facts.size)
        assertEquals(setOf("region"), proof.assumptions)
        assertFalse(proof.global)
    }

    @Test
    fun `rational scaling and proof expansion budgets decline with typed reasons`() {
        val huge = BigFraction.of(BIG_ONE, BIG_ONE.shl(80))
        val proof = CutProvenance(modelToken, 0, emptyList())
        val source = SourceCut(
            CutExpression(mapOf(x to huge, y to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ONE,
            proof,
        )
        val map = CutSourceMap(modelToken, 0, listOf(CutColumnSource(x), CutColumnSource(y)))

        assertEquals(CutMapping.Declined(CutMappingDecline.LONG_RANGE), source.toCut(map))
        assertEquals(
            CutMapping.Declined(CutMappingDecline.ARITHMETIC_LIMIT),
            source.toCut(map, CutMappingLimits(bits = 32)),
        )
    }

    @Test
    fun `guarded parent row stays guarded through real assembly and tableau generation`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf<Factor>(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0)),
        )
        val relaxer = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(-1, 0)))
        val session = PropagationSession(problem)
        session.implyIntAtMost(1, 3)
        val base = relaxer.build(session)
        val other = CutSource(CutSourceKind.INTEGER, 1)
        val guard = CutPremise.Bound(CutExpression(mapOf(other to BigFraction.ONE)), true, BigFraction.ofLong(3))
        val row = CutPremise.Row(
            CutExpression(mapOf(x to BigFraction.ofLong(2), other to BigFraction.MINUS_ONE)),
            Relation.LE,
            BigFraction.ZERO,
        )
        val parentProof = CutProvenance(problem, 0, listOf(CutProofFact(row, true), CutProofFact(guard, false)))
        val parent = Cut(intArrayOf(base.intColOf[0]), longArrayOf(2), Relation.LE, 3, provenance = parentProof)
        val relaxation = relaxer.build(session, listOf(parent))
        val model = relaxation.model
        val heads = IntArray(model.m) { model.slackCol(it) }
        heads[model.m - 1] = 0
        val basis = Basis(heads, Array(model.numVars) { VarStatus.AT_LOWER })

        val child = integerTableauCuts(model, basis, doubleArrayOf(1.5, 3.0), 1, mir = false).single()
        val portable = assertNotNull(SourceCut.fromCut(child, relaxation).orNull())

        assertFalse(portable.provenance.global)
        assertTrue(portable.provenance.facts.contains(CutProofFact(guard, false)))
        assertFalse(other in portable.expression.terms)
        assertNull(SharedCut.fromCut(child, relaxation))
        assertTrue(portable.provenance.rules.isNotEmpty())
        for (xi in 0L..5L) {
            for (yi in 0L..3L) {
                if (2 * xi <= yi) {
                    assertTrue(
                        portable.expression.value { BigFraction.ofLong(if (it == x) xi else yi) } >= portable.rhs,
                    )
                }
            }
        }
        assertTrue(portable.expression.value { BigFraction.ofLong(if (it == x) 2 else 4) } < portable.rhs)
        val pool = CutPool()
        pool.add(portable, assertNotNull(relaxation.sourceMap))
        val premises = portable.provenance.facts.map { it.premise }
        val popped = CutSourceMap(problem, 1, listOf(CutColumnSource(other), CutColumnSource(x)))
        assertEquals(mapOf(CutMappingDecline.INACTIVE_GUARD to 1), pool.remap(popped))
        assertTrue(pool.cuts().isEmpty())
        val restored = CutSourceMap(problem, 2, popped.columns, activePremises = premises.toSet())
        assertTrue(pool.remap(restored).isEmpty())
        assertEquals(1, pool.cuts().single().cols.single())
        assertTrue(pool.exportGlobalCuts().isEmpty())
    }

    @Test
    fun `unsupported auxiliary and missing local provenance decline explicitly`() {
        val builder = LpBuilder()
        builder.addVar(0, 3)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 3, global = false)
        val model = builder.build(Sense.MINIMIZE)
        val cut = integerTableauCuts(
            model,
            Basis(intArrayOf(0), Array(2) { VarStatus.AT_LOWER }),
            doubleArrayOf(1.5),
            1,
            false,
        ).single()
        val expression = CutExpression(mapOf(x to BigFraction.ONE))
        val map = CutSourceMap(
            modelToken,
            0,
            listOf(CutColumnSource(x)),
            globalPremises = setOf(
                CutPremise.Integral(expression),
                CutPremise.Bound(expression, false, BigFraction.ZERO),
            ),
        )
        val rel = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            0,
            intArrayOf(0),
            intArrayOf(),
            sourceMap = map,
        )
        val unmapped = LpRelaxation(
            model,
            intArrayOf(-1),
            booleanArrayOf(false),
            0,
            intArrayOf(),
            intArrayOf(),
            sourceMap = CutSourceMap(modelToken, 0, listOf(null)),
        )

        assertEquals(CutMapping.Declined(CutMappingDecline.MISSING_PROVENANCE), SourceCut.fromCut(cut, rel))
        assertEquals(CutMapping.Declined(CutMappingDecline.MISSING_SOURCE), SourceCut.fromCut(cut, unmapped))
    }

    @Test
    fun `zero coefficient cancellation does not erase fixed substitution provenance`() {
        val fixed = CutPremise.Fixed(x, BigFraction.ofLong(2))
        val source = SourceCut(
            CutExpression(mapOf(x to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ofLong(3),
            CutProvenance(modelToken, 0, emptyList()),
        )
        val map = CutSourceMap(
            modelToken,
            1,
            emptyList(),
            activePremises = setOf(fixed),
            fixed = mapOf(x to BigFraction.ofLong(2)),
        )

        val mapped = assertNotNull(source.toCut(map).orNull())

        assertTrue(mapped.cols.isEmpty())
        assertEquals(1L, mapped.rhs)
        assertFalse(mapped.global)
        assertEquals(listOf(CutProofFact(fixed, false)), assertNotNull(mapped.provenance).facts)
    }

    @Test
    fun `globally justified tableau cuts export with their exact rounding proof`() {
        val p = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 5)),
            arrayOf<Factor>(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 3)),
        )
        val r = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(-1))).build(PropagationSession(p))
        val cut = integerTableauCuts(
            r.model,
            Basis(intArrayOf(0), Array(2) { VarStatus.AT_LOWER }),
            doubleArrayOf(1.5),
            1,
            false,
        ).single()

        val exported = assertNotNull(SharedCut.fromCut(cut, r))
        val imported = assertNotNull(exported.toCut(r))

        assertTrue(imported.global)
        assertTrue(exported.source.provenance.rules.single().divisor > 1)
        assertTrue(exported.source.provenance.facts.any { it.premise is CutPremise.Integral })
        for (point in 0L..1L) assertTrue(imported.coeffs.single() * point >= imported.rhs)
        assertTrue(imported.coeffs.single() * 1.5 < imported.rhs)
    }

    @Test
    fun `split real components decline instead of masquerading as direct real identities`() {
        val p = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val builder = LpBuilder()
        repeat(2) { builder.addRealVar(0.0, null) }
        val model = builder.build(Sense.MINIMIZE)
        val map = cpCutSources(
            model,
            p,
            intArrayOf(-1, -1),
            booleanArrayOf(false, false),
            intArrayOf(0, 0),
            intArrayOf(1, -1),
            emptyMap(),
        )
        val r = LpRelaxation(
            model,
            intArrayOf(-1, -1),
            booleanArrayOf(false, false),
            0,
            intArrayOf(),
            intArrayOf(),
            sourceMap = map,
        )

        assertEquals(
            CutMapping.Declined(CutMappingDecline.MISSING_SOURCE),
            SourceCut.fromCut(Cut(intArrayOf(0), longArrayOf(1), Relation.LE, 2, global = true), r),
        )
    }

    @Test
    fun `foreign provenance is not retained through the raw cut fallback`() {
        val builder = LpBuilder()
        builder.addVar(0, 3)
        val model = builder.build(Sense.MINIMIZE)
        val map = CutSourceMap(modelToken, 0, listOf(CutColumnSource(x)))
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            0,
            intArrayOf(0),
            intArrayOf(),
            sourceMap = map,
        )
        val cut = Cut(
            intArrayOf(0),
            longArrayOf(1),
            Relation.LE,
            1,
            global = true,
            provenance = CutProvenance(Any(), 0, emptyList()),
        )
        val pool = CutPool()

        assertFalse(pool.add(cut, relaxation))
        assertEquals(0, pool.size)
    }
}
