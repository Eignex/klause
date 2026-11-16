package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenBooleanCandidateGatesTest {
    @Test
    fun `nested nary signed gates should preserve every leaf truth combination`() {
        for (positive in listOf(false, true)) {
            for (bits in 0 until 32) {
                val inner = Lit.make(1, false)
                val target = Lit.make(0, positive)
                val definitions = listOf(
                    inner to intArrayOf(Lit.make(2, true), Lit.make(3, false), Lit.make(4, true)),
                    target to intArrayOf(inner, Lit.make(5, false), Lit.make(6, true)),
                )
                val clauses = ArrayList<Clause>()
                for ((gate, operands) in definitions) {
                    for (operand in operands) clauses += Clause(intArrayOf(Lit.negate(gate), operand))
                    clauses += Clause(intArrayOf(gate, *operands.map { Lit.negate(it) }.toIntArray()))
                }
                val hints = assertNotNull(
                    SourceConjunctionHints.create(
                        clauses,
                        7,
                        intArrayOf(2, 3, 4, 5, 6),
                        SearchCandidateHints { variable ->
                            if (variable in 2..6) bits and (1 shl (variable - 2)) != 0 else null
                        },
                        ExecutionControl.Never,
                    ),
                )

                val innerTruth = (bits and 1 != 0) && (bits and 2 == 0) && (bits and 4 != 0)
                val outerTruth = innerTruth && (bits and 8 == 0) && (bits and 16 != 0)
                assertEquals(!innerTruth, hints.preferredBool(1))
                assertEquals(outerTruth == positive, hints.preferredBool(0))
            }
        }
    }

    @Test
    fun `nary gate preferences should require the reverse clause and every implication pair`() {
        for (missing in 0..3) {
            val clauses = listOf(
                Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(1, 4)),
                Clause(intArrayOf(1, 6)),
                Clause(intArrayOf(0, 3, 5, 7)),
            )
            val hints = assertNotNull(
                SourceConjunctionHints.create(
                    clauses.filterIndexed { index, _ -> index != missing },
                    4,
                    intArrayOf(1, 2, 3),
                    SearchCandidateHints { variable -> if (variable in 1..3) true else null },
                    ExecutionControl.Never,
                ),
            )

            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `repeated identical literals should preserve the canonical full gate preference`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(0, 3, 3, 5)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                3,
                intArrayOf(1, 2),
                SearchCandidateHints { variable ->
                    if (variable in 1..2) true else null
                },
                ExecutionControl.Never,
            ),
        )

        assertEquals(true, hints.preferredBool(0))
    }

    @Test
    fun `opposite polarity duplicate ids should leave a tautological gate reverse clause declined`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(1, 3)),
            Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(0, 3, 2, 5)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                3,
                intArrayOf(1, 2),
                SearchCandidateHints { variable ->
                    if (variable in 1..2) true else null
                },
                ExecutionControl.Never,
            ),
        )

        assertNull(hints.preferredBool(0))
    }

    @Test
    fun `primitive leaves should keep authority over a matching structural definition`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(1, 6)),
            Clause(intArrayOf(0, 5, 7)),
            Clause(intArrayOf(3, 0)),
            Clause(intArrayOf(3, 8)),
            Clause(intArrayOf(2, 1, 9)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                5,
                intArrayOf(0, 2, 3, 4),
                SearchCandidateHints { variable ->
                    when (variable) {
                        0 -> false
                        in 2..4 -> true
                        else -> null
                    }
                },
                ExecutionControl.Never,
            ),
        )

        assertEquals(false, hints.preferredBool(0))
        assertEquals(false, hints.preferredBool(1))
    }

    @Test
    fun `nested gates should decline missing definitions or primitive preferences`() {
        for (missingDefinition in listOf(false, true)) {
            val clauses = listOf(
                Clause(intArrayOf(3, 4)),
                Clause(intArrayOf(3, 6)),
                Clause(intArrayOf(2, 5, 7)),
                Clause(intArrayOf(1, 2)),
                Clause(intArrayOf(1, 8)),
                Clause(intArrayOf(0, 3, 9)),
            )
            val primitive = SearchCandidateHints { variable ->
                if (variable == 2 || variable == 4 || (missingDefinition && variable == 3)) true else null
            }
            val hints = assertNotNull(
                SourceConjunctionHints.create(
                    if (missingDefinition) clauses.drop(1) else clauses,
                    5,
                    intArrayOf(2, 3, 4),
                    primitive,
                    ExecutionControl.Never,
                ),
            )

            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `a nested dependency on an ambiguous gate should supply no preference`() {
        val clauses = listOf(
            Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(3, 5)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 4, 7)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 8)), Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                5,
                intArrayOf(2, 3, 4),
                SearchCandidateHints { variable ->
                    if (variable in 2..4) true else null
                },
                ExecutionControl.Never,
            ),
        )

        assertNull(hints.preferredBool(0))
    }

    @Test
    fun `nested evaluation should charge the opening meter and withhold a cancelled proposal`() {
        var work = 0L
        var limit = Long.MAX_VALUE
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = work >= limit
            override val accounting: WorkMeter get() = WorkMeter { work += it }
        }
        val clauses = listOf(
            Clause(intArrayOf(3, 4)),
            Clause(intArrayOf(3, 6)),
            Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(1, 8)),
            Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                5,
                intArrayOf(2, 3, 4),
                SearchCandidateHints { variable ->
                    if (variable in 2..4) true else null
                },
                stop,
            ),
        )
        val preparation = work
        limit = preparation + 4L

        assertNull(hints.preferredBool(0))
        assertTrue(work >= limit)
        limit = Long.MAX_VALUE
        assertEquals(true, hints.preferredBool(0))
        assertTrue(work > preparation + 4L)
    }

    @Test
    fun `nested consultations should read live leaves and closing should release the index`() {
        var value: Boolean? = true
        val clauses = listOf(
            Clause(intArrayOf(3, 4)),
            Clause(intArrayOf(3, 6)),
            Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(1, 2)),
            Clause(intArrayOf(1, 8)),
            Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(
            SourceConjunctionHints.create(
                clauses,
                5,
                intArrayOf(2, 3, 4),
                SearchCandidateHints { variable ->
                    if (variable in 2..4) value else null
                },
                ExecutionControl.Never,
            ),
        )

        assertEquals(true, hints.preferredBool(0))
        value = false
        assertEquals(false, hints.preferredBool(0))
        value = null
        assertNull(hints.preferredBool(0))
        value = true
        hints.close()
        assertNull(hints.preferredBool(0))
        assertNull(hints.preferredBool(2))
    }
}
