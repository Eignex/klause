package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.FloatLpStatus
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.solveLp
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RelaxationTidyTest {

    @Test
    fun `mixed-sign parallel sides retain the tightest supplier on each side`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0, 1), longArrayOf(2, 4), Relation.LE, 10)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 2), Relation.LE, 6)
        builder.addRow(intArrayOf(0, 1), longArrayOf(-3, -6), Relation.LE, -6)
        builder.addRow(intArrayOf(0, 1), longArrayOf(-1, -2), Relation.LE, -1)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertEquals(2, applied.relaxation.model.m)
        assertEquals(2, applied.derivation.stats.applied(RelaxationTidyRule.PARALLEL_SIDE))
        assertEquals(setOf(0, 2), applied.derivation.rowMaps.map { it.sourceRow }.toSet())
        assertEquals(
            mapOf(1 to 0, 3 to 2),
            applied.derivation.removedRows.associate { it.sourceRow to it.supplyingSourceRow },
        )
    }

    @Test
    fun `fixed substitution classifies a tautology and preserves a contradiction`() {
        val builder = LpBuilder()
        builder.addVar(2, 2)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 2)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 1)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertEquals(1, applied.relaxation.model.m)
        assertEquals(0, applied.relaxation.model.csc.colVal.size)
        assertEquals(-1, applied.relaxation.model.rhs.single())
        assertEquals(2, applied.derivation.stats.applied(RelaxationTidyRule.FIXED_SUBSTITUTION))
        assertTrue(applied.derivation.validate())
    }

    @Test
    fun `singleton lattice rounding remains explicit and detects incompatible sides`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 5)
        builder.addRow(intArrayOf(0), longArrayOf(-2), Relation.LE, -5)

        val applied = tidy(builder.build(Sense.MINIMIZE))
        val bounds = applied.derivation.bounds.sortedBy { if (it.columnUpper) 1 else 0 }

        assertEquals(listOf(3L, 2L), bounds.map { it.columnValue })
        assertEquals(listOf(false, true), bounds.map { it.columnUpper })
        assertTrue(bounds.all { it.rounded })
        assertEquals(FloatLpStatus.INDETERMINATE, solveLp(applied.relaxation.model).status)
    }

    @Test
    fun `a singleton bound can prove a later row redundant`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 5)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.LE, 12)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertEquals(1, applied.relaxation.model.m)
        val removed = applied.derivation.removedRows.single()
        assertEquals(RelaxationTidyRemovalReason.REDUNDANT, removed.reason)
        assertEquals(0, removed.boundUses.single().sourceRow)
        assertEquals(2L, applied.derivation.bounds.single().columnValue)
    }

    @Test
    fun `nonintegral singleton equality becomes an exact lattice contradiction`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        builder.addRow(intArrayOf(0), longArrayOf(2), Relation.EQ, 5)

        val applied = tidy(builder.build(Sense.MINIMIZE))

        assertEquals(0, applied.relaxation.model.csc.colVal.size)
        assertEquals(-1, applied.relaxation.model.rhs.single())
        assertTrue(applied.derivation.rowMaps.single().rounding?.infeasibleEquality == true)
        assertTrue(applied.derivation.validate())
    }

    @Test
    fun `singleton division rounds on a signed source lattice`() {
        val builder = LpBuilder()
        builder.addVar(1, 9)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 6)
        val model = builder.build(Sense.MINIMIZE)
        val root = Any()
        val source = CutColumnSource(
            CutSource(CutSourceKind.INTEGER, 0),
            scale = BigFraction.ofLong(-2),
            offset = BigFraction.ofLong(9),
        )
        val expression = source.expression()
        val map = CutSourceMap(
            root,
            0,
            listOf(source),
            setOf(
                CutPremise.Integral(expression),
                CutPremise.Bound(expression, false, BigFraction.ofLong(1)),
                CutPremise.Bound(expression, true, BigFraction.ofLong(9)),
            ),
        )
        val relaxation = LpRelaxation(
            model,
            intArrayOf(0),
            booleanArrayOf(false),
            0,
            intArrayOf(0),
            IntArray(0),
            sourceMap = map,
        )
        val applied = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                relaxation,
                RelaxationTidyScope(root, 0, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        val bound = applied.derivation.bounds.single()

        assertEquals(BigFraction.ofLong(2), bound.sourceValue)
        assertEquals(5, bound.columnValue)
        assertFalse(bound.sourceUpper)
        assertTrue(bound.columnUpper)
        assertTrue(applied.derivation.validate())
    }

    @Test
    fun `strict singleton endpoints round to closed integer bounds`() {
        val upperBuilder = LpBuilder()
        upperBuilder.addVar(0, 10)
        upperBuilder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 3)
        val upper = tidy(upperBuilder.build(Sense.MINIMIZE).withStrictRow())

        val lowerBuilder = LpBuilder()
        lowerBuilder.addVar(0, 10)
        lowerBuilder.addRow(intArrayOf(0), longArrayOf(-1), Relation.LE, -3)
        val lower = tidy(lowerBuilder.build(Sense.MINIMIZE).withStrictRow())

        assertEquals(2, upper.derivation.bounds.single().columnValue)
        assertEquals(4, lower.derivation.bounds.single().columnValue)
        assertTrue(!upper.relaxation.model.rowStrict.single())
        assertTrue(!lower.relaxation.model.rowStrict.single())
        assertTrue(upper.derivation.validate())
        assertTrue(lower.derivation.validate())
    }

    @Test
    fun `fixed substitution preserves the strict empty contradiction zero less than zero`() {
        val builder = LpBuilder()
        builder.addVar(2, 2)
        builder.addRow(intArrayOf(0), longArrayOf(1), Relation.LE, 2)

        val applied = tidy(builder.build(Sense.MINIMIZE).withStrictRow())

        assertEquals(1, applied.relaxation.model.m)
        assertTrue(applied.relaxation.model.rowStrict.single())
        assertEquals(0, applied.relaxation.model.rhs.single())
        assertTrue(applied.derivation.validate())
    }

    @Test
    fun `overflow and cancellation decline without partial publication`() {
        val overflowBuilder = LpBuilder()
        overflowBuilder.addVar(0, 10)
        overflowBuilder.addRow(intArrayOf(0), longArrayOf(Long.MIN_VALUE), Relation.LE, 0)
        val overflow = tidy(overflowBuilder.build(Sense.MINIMIZE))
        assertTrue(overflow.derivation.stats.declined(RelaxationTidyDecline.ARITHMETIC_OVERFLOW) >= 1)
        assertEquals(Long.MIN_VALUE, overflow.relaxation.model.csc.colVal.single())

        val model = overflowBuilder.build(Sense.MINIMIZE)
        val relaxation = relaxation(model)
        val cancelled = RelaxationTidy.apply(
            relaxation,
            RelaxationTidyScope(relaxation.sourceMap!!.model, 0, null, true),
            RelaxationTidyConfig(enabled = true, cancellation = Cancellation { true }),
        )
        assertEquals(RelaxationTidyDecline.CANCELLED, assertIs<RelaxationTidyResult.Declined>(cancelled).reason)
        assertEquals(1, model.m)
    }

    @Test
    fun `opaque auxiliary and real columns decline without invented source bounds`() {
        val auxiliaryBuilder = LpBuilder()
        auxiliaryBuilder.addVar(0, 10)
        auxiliaryBuilder.addRow(intArrayOf(0), longArrayOf(2), Relation.LE, 5)
        val auxiliaryModel = auxiliaryBuilder.build(Sense.MINIMIZE)
        val root = Any()
        val auxiliary = LpRelaxation(
            auxiliaryModel,
            intArrayOf(-1),
            booleanArrayOf(false),
            0,
            intArrayOf(-1),
            IntArray(0),
            sourceMap = CutSourceMap(root, 0, listOf(null)),
        )
        val auxiliaryResult = assertIs<RelaxationTidyResult.Applied>(
            RelaxationTidy.apply(
                auxiliary,
                RelaxationTidyScope(root, 0, null, true),
                RelaxationTidyConfig(enabled = true),
            ),
        )
        assertEquals(2, auxiliaryResult.relaxation.model.csc.colVal.single())
        assertEquals(1, auxiliaryResult.derivation.stats.declined(RelaxationTidyDecline.UNSUPPORTED_COLUMN))
        assertTrue(auxiliaryResult.derivation.bounds.isEmpty())

        val realBuilder = LpBuilder()
        realBuilder.addRealVar(0.0, 10.0)
        realBuilder.addRealRow(intArrayOf(0), doubleArrayOf(1.0), Relation.LE, 5.0)
        val realModel = realBuilder.build(Sense.MINIMIZE)
        val real = LpRelaxation(
            realModel,
            intArrayOf(-1),
            booleanArrayOf(false),
            0,
            intArrayOf(-1),
            IntArray(0),
            sourceMap = CutSourceMap(root, 0, listOf(null)),
        )
        val realResult = RelaxationTidy.apply(
            real,
            RelaxationTidyScope(root, 0, null, true),
            RelaxationTidyConfig(enabled = true),
        )
        assertEquals(
            RelaxationTidyDecline.CONTINUOUS_MODEL,
            assertIs<RelaxationTidyResult.Declined>(realResult).reason,
        )
        assertTrue(real.model === realModel)
    }

    private fun tidy(model: LpModel): RelaxationTidyResult.Applied {
        val relaxation = relaxation(model)
        val result = RelaxationTidy.apply(
            relaxation,
            RelaxationTidyScope(requireNotNull(relaxation.sourceMap).model, 0, null, true),
            RelaxationTidyConfig(enabled = true),
        )
        return assertIs(result)
    }

    private fun relaxation(model: LpModel): LpRelaxation {
        val root = Any()
        val columns = List(model.n) { column ->
            CutColumnSource(CutSource(CutSourceKind.INTEGER, column))
        }
        val globals = buildSet<CutPremise> {
            for (column in columns) {
                val expression = column.expression()
                add(CutPremise.Integral(expression))
                add(CutPremise.Bound(expression, false, BigFraction.ofLong(model.loShift[column.source.id])))
                if (model.hasUpper[column.source.id]) {
                    add(
                        CutPremise.Bound(
                            expression,
                            true,
                            BigFraction.ofLong(model.loShift[column.source.id] + model.upper[column.source.id]),
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
            sourceMap = CutSourceMap(root, 0, columns, globals),
        )
    }

    private fun LpModel.withStrictRow(): LpModel = LpModel(
        n,
        m,
        csc,
        rhs,
        cost,
        upper,
        hasUpper,
        loShift,
        objConstant,
        sense,
        tag,
        rowGlobal,
        booleanArrayOf(true),
        rowPremises,
        flippedRhs,
        probeClampedLo,
        probeClampedHi,
        colContinuous,
    )
}
