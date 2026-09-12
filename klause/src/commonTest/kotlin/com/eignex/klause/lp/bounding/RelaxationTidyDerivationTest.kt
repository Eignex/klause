package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelaxationTidyDerivationTest {

    @Test
    fun `source witness reconstruction preserves shifted objective and rows`() {
        val builder = LpBuilder()
        builder.addVar(2, 2, cost = 7)
        builder.addVar(0, 4, cost = -3)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.LE, 5)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertTrue(applied.derivation.verifySourceWitness(longArrayOf(2, 3)))
        assertFalse(applied.derivation.verifySourceWitness(longArrayOf(2, 4)))
        assertEquals(14, applied.relaxation.model.objConstant)
    }

    @Test
    fun `algebraic lift expands fixed substitutions with signed multipliers`() {
        val builder = LpBuilder()
        builder.addVar(2, 2)
        builder.addVar(0, 4)
        builder.addVar(0, 4)
        builder.addRow(intArrayOf(0, 1, 2), longArrayOf(3, 1, 1), Relation.LE, 10)

        val applied = tidy(builder.build(Sense.MINIMIZE))
        val lift = assertNotNull(applied.derivation.liftRowMultipliers(listOf(BigFraction.ofLong(2))))

        assertEquals(BigFraction.ofLong(2), lift.sourceRows[0])
        assertEquals(BigFraction.ofLong(-6), lift.fixingEqualities[0])

        val rowMap = applied.derivation.rowMaps.single()
        val forgedFixing = rowMap.fixings.single().copy(value = 3)
        val forged = RelaxationTidyDerivation(
            applied.derivation.sourceModel,
            applied.derivation.transformedModel,
            applied.derivation.scope,
            listOf(rowMap.copy(fixings = listOf(forgedFixing))),
            applied.derivation.removedRows,
            applied.derivation.bounds,
            applied.derivation.columnSources,
            applied.derivation.stats,
        )
        assertFalse(forged.validate())
    }

    @Test
    fun `rounded singleton does not masquerade as a real Farkas map`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 5)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertNull(applied.derivation.liftRowMultipliers(listOf(BigFraction.ONE)))
        assertTrue(applied.derivation.bounds.single().rounded)
    }

    @Test
    fun `forged source multiplier is rejected independently`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.LE, 5)
        val applied = tidy(builder.build(Sense.MINIMIZE))
        val original = applied.derivation
        val forged = RelaxationTidyDerivation(
            original.sourceModel,
            original.transformedModel,
            original.scope,
            original.rowMaps.map { it.copy(sourceMultiplier = BigFraction.ofLong(2)) },
            original.removedRows,
            original.bounds,
            original.columnSources,
            original.stats,
        )

        assertFalse(forged.validate())
        assertTrue(original.validate())
    }

    @Test
    fun `assumption cutoff and objective replacement contexts do not alias`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 5)
        val objective = Any()
        val cutoff = CutPremise.ObjectiveCutoff(
            com.eignex.klause.lp.engine.CutExpression(emptyMap()),
            BigFraction.ofLong(4),
        )
        val relaxation = relaxation(builder.build(Sense.MINIMIZE), assumptions = setOf("a"))
        val scope = RelaxationTidyScope(
            assertNotNull(relaxation.sourceMap).model,
            0,
            objective,
            true,
            setOf("a"),
            cutoff,
        )
        val result = RelaxationTidy.apply(
            relaxation,
            scope,
            RelaxationTidyConfig(enabled = true, assumptions = setOf("a"), cutoff = cutoff),
        )
        val applied = assertIs<RelaxationTidyResult.Applied>(result)

        assertTrue(applied.derivation.appliesTo(scope))
        assertFalse(applied.derivation.appliesTo(scope.copy(objective = Any())))
        assertFalse(applied.derivation.appliesTo(scope.copy(epoch = 1)))
        assertFalse(applied.derivation.appliesTo(scope.copy(assumptions = emptySet())))
        assertFalse(applied.derivation.appliesTo(scope.copy(cutoff = null)))
    }

    @Test
    fun `assumption and cutoff fixings remain conditional`() {
        val builder = LpBuilder()
        builder.addVar(2, 2)
        builder.addVar(0, 4)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.LE, 5)
        val model = builder.build(Sense.MINIMIZE)
        val root = Any()
        val columns = List(2) { CutColumnSource(CutSource(CutSourceKind.INTEGER, it)) }
        val fixedExpression = columns[0].expression()
        val active = setOf<CutPremise>(
            CutPremise.Bound(fixedExpression, false, BigFraction.ofLong(2)),
            CutPremise.Bound(fixedExpression, true, BigFraction.ofLong(2)),
        )
        val cutoff = CutPremise.ObjectiveCutoff(
            com.eignex.klause.lp.engine.CutExpression(emptyMap()),
            BigFraction.ofLong(9),
        )

        fun run(assumptions: Set<String>, cutoffScope: CutPremise.ObjectiveCutoff?): RelaxationTidyResult.Applied {
            val map = CutSourceMap(root, 0, columns, activePremises = active, assumptions = assumptions)
            val relaxation = LpRelaxation(
                model,
                intArrayOf(0, 1),
                BooleanArray(2),
                0,
                intArrayOf(0, 1),
                IntArray(0),
                sourceMap = map,
            )
            return assertIs(
                RelaxationTidy.apply(
                    relaxation,
                    RelaxationTidyScope(root, 0, null, true, assumptions, cutoffScope),
                    RelaxationTidyConfig(enabled = true, assumptions = assumptions, cutoff = cutoffScope),
                ),
            )
        }

        val assumed = run(setOf("pin"), null)
        val cutoffBound = run(emptySet(), cutoff)

        assertTrue(assumed.derivation.rowMaps.single().fixings.isNotEmpty())
        assertFalse(assumed.derivation.appliesTo(assumed.derivation.scope.copy(assumptions = emptySet())))
        assertTrue(cutoffBound.derivation.rowMaps.single().fixings.isNotEmpty())
        assertFalse(cutoffBound.derivation.appliesTo(cutoffBound.derivation.scope.copy(cutoff = null)))
    }

    private fun tidy(model: LpModel): RelaxationTidyResult.Applied {
        val relaxation = relaxation(model)
        return assertIs(
            RelaxationTidy.apply(
                relaxation,
                RelaxationTidyScope(assertNotNull(relaxation.sourceMap).model, 0, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
    }

    private fun relaxation(model: LpModel, assumptions: Set<String> = emptySet()): LpRelaxation {
        val root = Any()
        val columns = List(model.n) { CutColumnSource(CutSource(CutSourceKind.INTEGER, it)) }
        val globals = buildSet<CutPremise> {
            for ((column, source) in columns.withIndex()) {
                add(CutPremise.Integral(source.expression()))
                add(CutPremise.Bound(source.expression(), false, BigFraction.ofLong(model.loShift[column])))
                if (model.hasUpper[column]) {
                    add(
                        CutPremise.Bound(
                            source.expression(),
                            true,
                            BigFraction.ofLong(model.loShift[column] + model.upper[column]),
                        ),
                    )
                }
            }
        }
        return LpRelaxation(
            model,
            IntArray(model.n) { it },
            BooleanArray(model.n),
            0,
            IntArray(model.n) { it },
            IntArray(0),
            sourceMap = CutSourceMap(root, 0, columns, globals, assumptions = assumptions),
        )
    }
}
