package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrivateRebuildAliasTest {
    @Test
    fun `signed substitution should eliminate a column with a source lift`() {
        for (replacement in listOf(0, 1)) {
            val premises = arrayOf(
                Clause(intArrayOf(replacement, 3)), Clause(intArrayOf(Lit.negate(replacement), 2)),
            )
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 4)), Clause(intArrayOf(2, 6)), premises[0], premises[1],
            ))

            val delta = PrivateRebuildAlias.reduce(source, premises, Cancellation.Never)
            val reduced = source.withSourcePassDelta(delta)
            val lift = assertNotNull(delta.rebuild.asSampleLift())

            assertTrue(reduced.factors.all { (it as Clause).literals.none { literal -> Lit.variable(literal) == 1 } })
            for (mask in 0 until 16) {
                val point = Sample(BooleanArray(4) { mask and (1 shl it) != 0 }, longArrayOf())
                if (reduced.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, point.boolValue(Lit.variable(it))) }
                    }) {
                    val original = lift(point)
                    assertTrue(source.factors.all { factor ->
                        (factor as Clause).literals.any { Lit.evaluate(it, original.boolValue(Lit.variable(it))) }
                    })
                }
            }
        }
    }

    @Test
    fun `untouched clauses should remain shared in the alias view`() {
        val unchanged = Clause(intArrayOf(4, 6))
        val premises = arrayOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3)))
        val source = Problem(4, 0, emptyArray(), arrayOf(unchanged, premises[0], premises[1]))

        val reduced = source.withSourcePassDelta(PrivateRebuildAlias.reduce(source, premises, Cancellation.Never))

        assertSame(unchanged, reduced.factors.single())
    }

    @Test
    fun `matching values should not bind another premise namespace`() {
        val premises = arrayOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3)))
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3))))

        val delta = PrivateRebuildAlias.reduce(source, premises, Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `partial substitution should withhold the changed view`() {
        for (allowance in listOf(0L, 8L, 16L, 32L)) {
            val premises = arrayOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 3)))
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(2, 4)), premises[0], premises[1],
            ))
            var spent = 0L
            val meter = WorkMeter { spent += it }
            val cancellation = object : Cancellation {
                override fun isCancelled(): Boolean = spent >= allowance
                override fun workMeter(): WorkMeter = meter
            }

            val delta = PrivateRebuildAlias.reduce(source, premises, cancellation)

            assertTrue(delta.isEmpty)
        }
    }
}
