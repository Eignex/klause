package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.observe
import com.eignex.klause.presolve.presolveSize
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingletonInequalityProjectionTest {
    @Test
    fun `pre-cancelled dispatch should skip occurrence and row reads in both lanes`() {
        for (lane in PresolveLane.entries) {
            var reads = 0
            var work = 0L
            val row = Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)
            val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                override val intVars: IntArray get() {
                    reads++
                    return row.intVars
                }
                override val linearForm: LinearForm get() {
                    reads++
                    return row.linearForm
                }
            }
            val problem = BakedProblem(0, 2, Array(2) { IntDomain(0, 10) }, listOf(factor), alreadyFolded = true)
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = true
                override fun workMeter(): WorkMeter = WorkMeter { work += it }
            }
            val context = PresolveContext(cancellation = token)
            reads = 0

            val empty = when (lane) {
                PresolveLane.SOURCE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applySource(problem, context).isEmpty
                PresolveLane.FINITE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applyFinite(problem, context).isEmpty
            }

            assertTrue(empty, "$lane")
            assertEquals(0, reads, "$lane")
            assertEquals(0L, work, "$lane")
        }
    }

    @Test
    fun `interrupted occurrences should publish no projection in either lane`() {
        for (lane in PresolveLane.entries) {
            var memberships = 0
            var rows = 0
            var work = 0L
            val row = Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)
            val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                override val intVars: IntArray get() {
                    memberships++
                    return row.intVars
                }
                override val linearForm: LinearForm get() {
                    rows++
                    return row.linearForm
                }
            }
            val problem = BakedProblem(0, 2, Array(2) { IntDomain(0, 10) }, List(128) { factor }, alreadyFolded = true)
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = memberships >= 8
                override fun workMeter(): WorkMeter = WorkMeter { work += it }
            }
            val context = PresolveContext(cancellation = token)
            memberships = 0
            rows = 0

            val empty = when (lane) {
                PresolveLane.SOURCE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applySource(problem, context).isEmpty
                PresolveLane.FINITE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applyFinite(problem, context).isEmpty
            }

            assertTrue(empty, "$lane")
            assertTrue(memberships in 8..30, "$lane: $memberships memberships")
            assertEquals(0, rows, "$lane")
            assertTrue(work in 240L..880L, "$lane: $work units")
        }
    }

    @Test
    fun `row dispatch should stop within one stride of a live cancellation at any model size`() {
        val pass = PresolvePass.PROJECT_SINGLETON_INEQUALITIES
        for (lane in PresolveLane.entries) {
            for (count in listOf(100, 1_000)) {
                var reads = 0
                var work = 0L
                val row = Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)
                val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                    override val linearForm: LinearForm get() {
                        reads++
                        return row.linearForm
                    }
                }
                val problem = BakedProblem(
                    0,
                    2,
                    Array(2) { IntDomain(0, 10) },
                    List(count) { factor },
                    alreadyFolded = true,
                )
                val containing = object : Cancellation {
                    override fun isCancelled(): Boolean = reads >= 32
                    override fun workMeter(): WorkMeter = WorkMeter { work += it }
                }
                val budget = PresolveBudget(0L)
                val context = PresolveContext(cancellation = budget.preparationCancellation(containing))
                reads = 0

                val empty = budget.observe(pass, lane, { problem.presolveSize() }) {
                    when (lane) {
                        PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                        PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                    }
                }

                assertTrue(empty, "$lane $count")
                assertTrue(reads in 32..96, "$lane $count: $reads reads")
                assertTrue(work in count * 30L + 320L..count * 30L + 5_440L, "$lane $count: $work units")
                assertEquals(work, budget.invocations.single().nativeWork)
                assertEquals(PresolveInvocationOutcome.INTERRUPTED, budget.invocations.single().outcome)
                assertEquals(0L, budget.spent())
            }
        }
    }

    @Test
    fun `linear specialization should stop within a long term scan in both lanes`() {
        for (lane in PresolveLane.entries) {
            var refs = 0
            val linear = Linear(LongArray(512) { 1 }, IntArray(512) { it }, LinearOp.LE, 100)
            val row = object : LinearRow by linear {
                override fun ref(k: Int): Int {
                    refs++
                    return linear.ref(k)
                }
            }
            val factor = object : Factor by linear, Propagator by linear.propagatorProjection() {
                override val linearForm = LinearForm.Conjunction(listOf(row))
            }
            val problem = BakedProblem(0, 512, Array(512) { IntDomain(0, 10) }, listOf(factor), alreadyFolded = true)
            val context = PresolveContext(cancellation = Cancellation { refs >= 32 })
            refs = 0

            val empty = when (lane) {
                PresolveLane.SOURCE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applySource(problem, context).isEmpty
                PresolveLane.FINITE -> PresolvePass.PROJECT_SINGLETON_INEQUALITIES.applyFinite(problem, context).isEmpty
            }

            assertTrue(empty, "$lane")
            assertTrue(refs in 32..96, "$lane: $refs refs")
        }
    }

    @Test
    fun `work exhaustion should interrupt occurrence candidate and rewrite scans in both lanes`() {
        val pass = PresolvePass.PROJECT_SINGLETON_INEQUALITIES
        val width = 512
        val row = Linear(LongArray(width) { 1 }, IntArray(width) { it }, LinearOp.LE, 100)
        val problem = BakedProblem(0, width, Array(width) { IntDomain(0, 10) }, listOf(row), alreadyFolded = true)
        for (lane in PresolveLane.entries) {
            for (phase in listOf("occurrences", "candidates", "rewrite")) {
                val occurrenceWork = 10L * (1 + width)
                val validationWork = if (lane == PresolveLane.SOURCE) 10L * width else 0L
                val allowance = when (phase) {
                    "occurrences" -> 320L
                    "candidates" -> occurrenceWork + validationWork + 320L
                    else -> occurrenceWork + validationWork + 10L + 10L + 320L
                }
                val budget = PresolveBudget(allowance)
                val objective = if (phase == "candidates") {
                    (0 until width - 1).associateWith { 1L }
                } else {
                    emptyMap()
                }
                val context = PresolveContext(
                    objectiveIntCoeffs = objective,
                    cancellation = budget.orSpent(Cancellation.Never),
                )

                val empty = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                    PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                }

                assertTrue(empty, "$lane $phase")
                assertTrue(budget.spent() in allowance..allowance + 640L, "$lane $phase: ${budget.spent()} units")
            }
        }
    }

    @Test
    fun `productive dispatch should forward native work once without spending the elective allowance`() {
        val pass = PresolvePass.PROJECT_SINGLETON_INEQUALITIES
        for (lane in PresolveLane.entries) {
            for (openBelow in listOf(false, true)) {
                val problem = BakedProblem(
                    0,
                    2,
                    arrayOf(IntDomain(2, 9), IntDomain(0, 20)),
                    listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)),
                    openIntLo = booleanArrayOf(openBelow, false),
                    alreadyFolded = true,
                )
                var work = 0L
                val containing = object : Cancellation {
                    override fun isCancelled(): Boolean = false
                    override fun workMeter(): WorkMeter = WorkMeter { work += it }
                }
                val budget = PresolveBudget(0L)
                val context = PresolveContext(
                    objectiveIntCoeffs = mapOf(1 to 1L),
                    cancellation = budget.preparationCancellation(containing),
                )

                val delta = budget.observe(pass, lane, { problem.presolveSize() }) {
                    when (lane) {
                        PresolveLane.SOURCE -> pass.applySource(problem, context).asPassDelta(problem.rootIntDomains())
                        PresolveLane.FINITE -> pass.applyFinite(problem, context)
                    }
                }

                val free = lane == PresolveLane.SOURCE && openBelow
                val ints = longArrayOf(99, if (free) 15 else 8)
                delta.rebuild.rebuildInto(BooleanArray(0), ints)
                assertEquals(listOf(0), delta.droppedIndices.toList(), "$lane $openBelow")
                assertEquals(if (free) -5L else 2L, ints[0], "$lane $openBelow")
                assertEquals(if (free) 0 else 1, delta.addedFactors.size, "$lane $openBelow")
                val expectedWork = when {
                    lane == PresolveLane.FINITE -> 70L
                    free -> 100L
                    else -> 90L
                }
                assertEquals(expectedWork, work)
                assertEquals(work, budget.invocations.single().nativeWork)
                assertEquals(0L, budget.spent())
            }
        }
    }

    @Test
    fun `cancellation before publication should retain only completed rows and their reconstruction`() {
        val pass = PresolvePass.PROJECT_SINGLETON_INEQUALITIES
        for (lane in PresolveLane.entries) {
            for (openBelow in listOf(false, true)) {
                var reads = 0
                val factors = List(2) { index ->
                    val row = Linear(longArrayOf(1, 1), intArrayOf(index, 2), LinearOp.LE, 10)
                    object : Factor by row, Propagator by row.propagatorProjection() {
                        override val linearForm: LinearForm get() {
                            reads++
                            return row.linearForm
                        }
                    }
                }
                val problem = BakedProblem(
                    0,
                    3,
                    arrayOf(IntDomain(2, 9), IntDomain(2, 9), IntDomain(0, 20)),
                    factors,
                    openIntLo = booleanArrayOf(openBelow, openBelow, false),
                    alreadyFolded = true,
                )
                val context = PresolveContext(cancellation = Cancellation { reads >= 2 })
                reads = 0

                val delta = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).asPassDelta(problem.rootIntDomains())
                    PresolveLane.FINITE -> pass.applyFinite(problem, context)
                }

                val free = lane == PresolveLane.SOURCE && openBelow
                val ints = longArrayOf(99, 99, if (free) 15 else 8)
                delta.rebuild.rebuildInto(BooleanArray(0), ints)
                assertEquals(listOf(0), delta.droppedIndices.toList(), "$lane $openBelow")
                assertEquals(if (free) 0 else 1, delta.addedFactors.size, "$lane $openBelow")
                assertEquals(if (free) -5L else 2L, ints[0], "$lane $openBelow")
                assertEquals(99L, ints[1], "$lane $openBelow")
            }
        }
    }

    private fun sourceModel(openBelow: Boolean) = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(2, 9), IntDomain(0, 20)),
        factors = listOf(Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)),
        openIntLo = booleanArrayOf(openBelow, false),
    )

    @Test
    fun `source projection should pin a column whose relaxing side is closed`() {
        val delta = SingletonInequalityProjection.projectSource(sourceModel(openBelow = false), emptySet())
        val ints = longArrayOf(0, 8)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertEquals(8L, (delta.addedFactors.single() as Linear).integerConstants!!.bound, "y <= 10 - 2")
        assertEquals(2L, ints[0])
    }

    @Test
    fun `source projection should drop the row of a column free on its relaxing side`() {
        val delta = SingletonInequalityProjection.projectSource(sourceModel(openBelow = true), emptySet())
        val ints = longArrayOf(0, 15)

        delta.rebuild.rebuildInto(BooleanArray(0), ints)

        assertEquals(listOf(0), delta.droppedIndices.toList())
        assertTrue(delta.addedFactors.isEmpty())
        assertEquals(-5L, ints[0], "x + 15 <= 10")
    }

    @Test
    fun `a declared singleton inequality should reconstruct its projected variable`() {
        val source = Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 10)
        val factor = object : Factor by source, Propagator by source.propagatorProjection() {}
        val model = problem(arrayOf(IntDomain(0, 5), IntDomain(0, 20)), factor)

        val delta = SingletonInequalityProjection.project(model, objectiveIntVars = setOf(1))

        assertEquals(1, delta.droppedIndices.size)
        val sample = delta.reconstruct!!(Sample(bools = booleanArrayOf(), ints = longArrayOf(99, 5)))
        assertEquals(0L, sample.ints[0])
        assertTrue(isFeasible(model, sample))
    }

    private fun problem(domains: Array<IntDomain>, vararg factors: Factor) =
        Problem(numBoolVars = 0, numIntVars = domains.size, intDomains = domains, factors = factors.toList()).bake()

    private fun isFeasible(p: Problem, s: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until p.numIntVars) a = a.withInt(v, s.ints[v])
        return p.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `projection should leave objective variables in place`() {
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20)),
            Linear(longArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 10),
        )
        assertEquals(0, SingletonInequalityProjection.project(p, objectiveIntVars = setOf(0, 1)).droppedIndices.size)
    }

    @Test
    fun `a variable used by another constraint should not be projected`() {
        val p = problem(
            arrayOf(IntDomain(0, 5), IntDomain(0, 20), IntDomain(0, 20)),
            Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10),
            Linear(longArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 10),
        )
        val d = SingletonInequalityProjection.project(p, emptySet())
        assertEquals(listOf(listOf(0), listOf(0)), d.addedFactors.map { (it as Linear).vars.toList() })
        val rebuilt = d.reconstruct!!(Sample(bools = BooleanArray(0), ints = longArrayOf(4, 6, 6)))
        assertEquals(4L, rebuilt.ints[0])
    }
}
