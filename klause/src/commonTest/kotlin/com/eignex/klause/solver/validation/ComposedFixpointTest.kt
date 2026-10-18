package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.NoPropagator
import com.eignex.klause.propagation.PropagationProjection
import com.eignex.klause.propagation.PropagationProjectionProvider
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComposedFixpointTest {
    @Test
    fun `a complete point satisfying the relation should be accepted`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), longArrayOf(3, 7)), Unit)

        val result = composedFixpoint(model, candidate, Cancellation.Never)

        assertEquals(candidate, assertIs<Verification.Accepted<Sample, Unit>>(result).candidate)
    }

    @Test
    fun `a complete point violating the relation should be rejected`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), longArrayOf(3, 6)), Unit)

        val result = composedFixpoint(model, candidate, Cancellation.Never)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `a complete point should retain caller integer pins`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), longArrayOf(3, 7)), Unit)
        val assumptions = Assumptions.None.withInt(0, 4)

        val result = composedFixpoint(model, candidate, Cancellation.Never, assumptions)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `a complete point should retain caller Boolean pins`() {
        val model = Problem(1, 0, emptyArray(), emptyArray()).bake()
        val candidate = Candidate(Sample(booleanArrayOf(true), longArrayOf()), Unit)
        val assumptions = Assumptions.None.withBool(0, false)

        val result = composedFixpoint(model, candidate, Cancellation.Never, assumptions)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `a point outside a root domain should be rejected without factors`() {
        val model = Problem(0, 1, arrayOf(IntDomain(0, 0)), emptyArray()).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), longArrayOf(1)), Unit)

        val result = composedFixpoint(model, candidate, Cancellation.Never)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `checking one point should not condition the next check`() {
        val model = Problem(
            0, 2, Array(2) { IntDomain(0, 10) },
            arrayOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 10)),
        ).bake()
        val points = listOf(longArrayOf(3, 7), longArrayOf(8, 2))

        val results = points.map {
            composedFixpoint(model, Candidate(Sample(booleanArrayOf(), it), Unit), Cancellation.Never)
        }

        for (result in results) assertIs<Verification.Accepted<*, *>>(result)
        assertEquals(IntDomain(0, 10), model.rootIntDomain(0))
        assertEquals(IntDomain(0, 10), model.rootIntDomain(1))
    }

    @Test
    fun `selected inert propagation should retain required original checks`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2))))
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.INERT,
                    AssignmentCheckCapability.SOURCE_CERTIFICATION,
                    "Original source certification enforces the clause.",
                ) { NoPropagator }
            },
        )
        val model = source.bake(providers)
        val candidate = Candidate(Sample(booleanArrayOf(false, false), longArrayOf()), Unit)

        val result = composedFixpoint(model, candidate, Cancellation.Never)

        assertIs<Verification.Rejected>(result)
    }

    @Test
    fun `cancelled point preparation should report zero work once`() {
        val model = Problem(0, 64, Array(64) { IntDomain(0, 1) }, emptyArray()).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), LongArray(64)), Unit)
        var polls = 0
        var reports = 0
        var work = -1L
        val cancellation = Cancellation { ++polls == 17 }

        val result = composedFixpoint(model, candidate, cancellation, onWork = {
            reports++
            work = it
        })

        assertIs<Verification.Indeterminate>(result)
        assertEquals(1, reports)
        assertEquals(0L, work)
    }

    @Test
    fun `cancellation during propagation should leave the point indeterminate`() {
        var cancelled = false
        val source = Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(0))))
        val providers = FactorProjectionProviders(
            propagation = PropagationProjectionProvider {
                PropagationProjection(
                    PropagationCapability.SOUND_FILTERING,
                    AssignmentCheckCapability.EXACT,
                ) {
                    object : Propagator {
                        override fun propagate(state: PropagationState, factorId: Int): Boolean {
                            cancelled = true
                            return true
                        }
                    }
                }
            },
        )
        val model = source.bake(providers)
        cancelled = false
        val candidate = Candidate(Sample(booleanArrayOf(true), longArrayOf()), Unit)

        val result = composedFixpoint(model, candidate, Cancellation { cancelled })

        assertIs<Verification.Indeterminate>(result)
    }

    @Test
    fun `checking a complete wide row should use linear propagation work`() {
        val columns = 512
        val source = Problem(
            0, columns, Array(columns) { IntDomain(0, 1) },
            arrayOf(Linear(IntArray(columns) { 1 }, IntArray(columns) { it }, LinearOp.EQ, columns / 2)),
        ).bake()
        val candidate = Candidate(Sample(booleanArrayOf(), LongArray(columns) { (it % 2).toLong() }), Unit)
        var work = -1L

        val result = composedFixpoint(source, candidate, Cancellation.Never, onWork = { work = it })

        assertIs<Verification.Accepted<*, *>>(result)
        assertTrue(work in 1L until 32L * columns, "propagation work was $work for $columns columns")
    }
}
