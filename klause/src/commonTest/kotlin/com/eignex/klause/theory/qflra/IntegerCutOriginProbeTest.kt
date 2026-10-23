package com.eignex.klause.theory.qflra

import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpEntry
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpRow
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IntegerCutOriginProbeTest {
    @Test
    fun `finite structural origins should ignore continuous unbounded logical columns`() {
        val columns = listOf(
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(Long.MIN_VALUE)))),
            ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(Long.MAX_VALUE)))),
            ExactLpColumn(
                ExactLpBounds(ExactLpSide(ExactLpNumber.of(Long.MAX_VALUE))),
                origin = ExactLpNumber.of(Long.MIN_VALUE),
            ),
            ExactLpColumn(ExactLpBounds(), integral = false),
        )
        val model = ExactLpModel(
            List(3) { emptyList<ExactLpEntry>() },
            listOf(ExactLpNumber.of(0L)),
            columns,
            listOf(ExactLpRow()),
            ExactLpObjective(List(columns.size) { ExactLpNumber.of(0L) }),
        )

        val outcome = IntegerCutOriginProbe.inspect(model, Cancellation.Never)

        assertEquals(IntegerCutOriginProbe.Outcome.FINITE_LONG_ORIGINS, outcome)
    }

    @Test
    fun `an empty structural scope should not establish finite origins`() {
        val model = ExactLpModel(emptyList(), emptyList(), emptyList(), emptyList(), ExactLpObjective(emptyList()))

        val outcome = IntegerCutOriginProbe.inspect(model, Cancellation.Never)

        assertEquals(IntegerCutOriginProbe.Outcome.NO_STRUCTURAL_COLUMNS, outcome)
    }

    @Test
    fun `an open structural lower side should withhold finite origin qualification`() {
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(3L))))),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )

        val outcome = IntegerCutOriginProbe.inspect(model, Cancellation.Never)

        assertEquals(IntegerCutOriginProbe.Outcome.OPEN_LOWER, outcome)
    }

    @Test
    fun `a continuous structural column should withhold integer origin qualification`() {
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L))), integral = false)),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(0L))),
        )

        val outcome = IntegerCutOriginProbe.inspect(model, Cancellation.Never)

        assertEquals(IntegerCutOriginProbe.Outcome.NONINTEGRAL_COLUMN, outcome)
    }

    @Test
    fun `unsupported exact origins should preserve fractional wide and overflowing distinctions`() {
        val zero = ExactLpNumber.of(0L)
        val half = ExactLpNumber.of(BigFraction.of(bigIntOf(1L), bigIntOf(2L)))
        val wide = ExactLpNumber.of(BigFraction.ofLong(Long.MAX_VALUE) + BigFraction.ONE)
        for ((lower, origin, expected) in listOf(
            Triple(half, zero, IntegerCutOriginProbe.Outcome.NON_LONG_LOWER),
            Triple(wide, zero, IntegerCutOriginProbe.Outcome.NON_LONG_LOWER),
            Triple(zero, half, IntegerCutOriginProbe.Outcome.NON_LONG_ORIGIN),
            Triple(zero, wide, IntegerCutOriginProbe.Outcome.NON_LONG_ORIGIN),
            Triple(ExactLpNumber.of(Long.MAX_VALUE), ExactLpNumber.of(1L),
                IntegerCutOriginProbe.Outcome.ORIGIN_SHIFT_OVERFLOW),
            Triple(ExactLpNumber.of(Long.MIN_VALUE), ExactLpNumber.of(-1L),
                IntegerCutOriginProbe.Outcome.ORIGIN_SHIFT_OVERFLOW),
        )) {
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(lower)), origin = origin)),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            )

            val outcome = IntegerCutOriginProbe.inspect(model, Cancellation.Never)

            assertEquals(expected, outcome)
        }
    }

    @Test
    fun `cancellation should withhold classification at entry during scanning and before publication`() {
        val columnCount = 130
        val model = ExactLpModel(
            List(columnCount) { emptyList<ExactLpEntry>() },
            emptyList(),
            List(columnCount) { ExactLpColumn(ExactLpBounds(ExactLpSide(ExactLpNumber.of(0L)))) },
            emptyList(),
            ExactLpObjective(List(columnCount) { ExactLpNumber.of(0L) }),
        )
        for ((stopAfter, expectedCharge) in listOf(0L to 0L, 2L to 65L, 130L to 130L)) {
            var charged = 0L
            val stop = object : Cancellation {
                override fun isCancelled(): Boolean = charged >= stopAfter
                override fun workMeter(): WorkMeter = WorkMeter { charged += it }
            }

            val outcome = IntegerCutOriginProbe.inspect(model, stop)

            assertNull(outcome)
            assertEquals(expectedCharge, charged)
        }
    }
}
