package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveContext
import com.eignex.klause.presolve.PresolvePass
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinearSubSumAggregationTest {
    @Test
    fun `a three column definition should contract its target in both schedules`() {
        for (lane in PresolveLane.entries) {
            val problem = Problem(
                0, 3, Array(3) { IntDomain(0, 10) },
                listOf(sumDef(), Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 7)),
            )
            val config = PresolveConfig.parse("off,+aggregate")

            val reduced = when (lane) {
                PresolveLane.SOURCE -> Presolver.runSource(problem, config).problem
                PresolveLane.FINITE -> Presolver.run(problem.bake(), config).problem
            }

            val target = reduced.factors.filterIsInstance<Linear>().single { it.vars.contentEquals(intArrayOf(0)) }
            assertEquals(7L, target.integerConstants?.bound)
        }
    }

    @Test
    fun `the scheduled pass should honor an already cancelled phase without charging work`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 2) },
            listOf(sumDef(), Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 3)),
        )
        val budget = PresolveBudget(1000)
        val context = PresolveContext(cancellation = budget.orSpent(Cancellation { true }))

        val delta = PresolvePass.AGGREGATE_SUB_SUMS.applySource(problem, context)

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `definition term visits should stop within one poll interval of the phase allowance`() {
        val width = 128
        val problem = Problem(
            0,
            width,
            Array(width) { IntDomain(0, 2) },
            listOf(Linear(IntArray(width) { 2 }, IntArray(width) { it }, LinearOp.EQ, 0)),
        )
        val budget = PresolveBudget(16)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertTrue(budget.spent() in 16L..79L)
    }

    @Test
    fun `dense unsuccessful candidates should consume only linear work and leave cheap reductions available`() {
        val count = 256
        val factors = List(count) { i ->
            Linear(intArrayOf(1, i + 2, i + 3), intArrayOf(i, count, count + 1), LinearOp.EQ, 0)
        } + listOf(
            Linear(intArrayOf(1, -1, -1), intArrayOf(count + 2, count + 3, count + 4), LinearOp.EQ, 0),
            Linear(intArrayOf(1, 1, 1), intArrayOf(count + 3, count + 4, count + 5), LinearOp.LE, 3),
        )
        val problem = Problem(0, count + 6, Array(count + 6) { IntDomain(0, 2) }, factors)
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(factors.lastIndex), delta.droppedIndices.toList())
        assertEquals(setOf(count + 2, count + 5), (delta.addedFactors.single() as Linear).vars.toSet())
        assertTrue(budget.spent() in 1L..(40L * factors.size))
    }

    @Test
    fun `cancellation during candidate scans should retain completed equivalent rewrites`() {
        val targets = List(64) {
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 3)
        }
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, listOf(sumDef()) + targets)
        val fullBudget = PresolveBudget(Long.MAX_VALUE)
        val complete = LinearSubSumAggregation.aggregateSubSums(problem, fullBudget.orSpent(Cancellation.Never))
        val budget = PresolveBudget(fullBudget.spent() - 64)

        val delta = LinearSubSumAggregation.aggregateSubSums(problem, budget.orSpent(Cancellation.Never))

        assertEquals(targets.size, complete.addedFactors.size)
        assertTrue(delta.addedFactors.size in 1 until targets.size)
        assertTrue(budget.spent() in budget.allowance..(budget.allowance + 63))
        assertTrue(0 !in delta.droppedIndices)
        val remaining = problem.factors.filterIndexed { i, _ -> i !in delta.droppedIndices } + delta.addedFactors
        assertEquals(
            feasible(problem.factors, LongArray(4), LongArray(4) { 2 }),
            feasible(remaining.toTypedArray(), LongArray(4), LongArray(4) { 2 }),
        )
    }

    @Test
    fun `a declared sub-sum should fold into a declared target row`() {
        val equality = sumDef()
        val inequality = Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 10)
        val definition = object : Factor by equality, Propagator by equality.propagatorProjection() {}
        val target = object : Factor by inequality, Propagator by inequality.propagatorProjection() {}

        val out = run(definition, target)

        val reduced = theInequality(out)
        assertEquals(setOf(0, 3), reduced.vars.toSet())
        assertEquals(10L, reduced.integerConstants?.bound)
    }

    @Test
    fun `a retained non-unit equality should cancel two target terms without eliminating columns`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val reader = Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0)
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(-5, 5) },
            listOf(equality, target, reader),
            openIntLo = BooleanArray(3) { true },
            openIntHi = BooleanArray(3) { true },
        )
        val context = PresolveContext(
            objectiveIntCoeffs = mapOf(0 to 2L, 1 to -3L, 2 to 5L),
            solutionSetSensitive = true,
        )

        val delta = PresolvePass.SPARSIFY_LINEAR_ROWS.applySource(problem, context)

        assertEquals(listOf(1), delta.droppedIndices.toList())
        val replacement = delta.addedFactors.single() as Linear
        assertEquals(listOf(2), replacement.vars.toList())
        assertEquals(listOf(-3L), replacement.integerConstants?.coeffs?.toList())
        assertEquals(-2L, replacement.integerConstants?.bound)
        assertTrue((0..2).all { !problem.intBounds.hasLower(it) && !problem.intBounds.hasUpper(it) })
        val remaining = arrayOf<Factor>(equality, reader, replacement)
        assertEquals(
            feasible(problem.factors, LongArray(3) { -5 }, LongArray(3) { 5 }),
            feasible(remaining, LongArray(3) { -5 }, LongArray(3) { 5 }),
        )
    }

    @Test
    fun `signed and fractional row ratios should preserve every comparison`() {
        for (op in LinearOp.entries) {
            for (sign in listOf(-1, 1)) {
                val equality = Linear(
                    intArrayOf(4 * sign, 6 * sign, 5 * sign),
                    intArrayOf(0, 1, 2),
                    LinearOp.EQ,
                    7 * sign,
                )
                val target = Linear(intArrayOf(2, 3, 4), intArrayOf(0, 1, 2), op, 5)
                val problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, listOf(equality, target))

                val delta = LinearSubSumAggregation.sparsifyRows(problem)

                assertEquals(listOf(1), delta.droppedIndices.toList())
                val replacement = delta.addedFactors.single() as Linear
                assertEquals(1, replacement.vars.size)
                assertEquals(target.op, replacement.op)
                assertEquals(
                    feasible(problem.factors, LongArray(3) { -2 }, LongArray(3) { 2 }),
                    feasible(arrayOf(equality, replacement), LongArray(3) { -2 }, LongArray(3) { 2 }),
                )
            }
        }
    }

    @Test
    fun `complete declared single rows should support sparsification`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val factors = listOf(equality, target).map { object : Factor by it {} }
        val problem = Problem(0, 3, Array(3) { IntDomain(-5, 5) }, factors)

        val delta = LinearSubSumAggregation.sparsifyRows(problem)

        assertEquals(listOf(1), delta.droppedIndices.toList())
        assertEquals(listOf(2), (delta.addedFactors.single() as Linear).vars.toList())
    }

    @Test
    fun `partial multi-row alternative and activated declarations should not authorize row subtraction`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        for (row in listOf(equality, target)) {
            val activated = LinearRow.ofColumns(row.vars, intArrayOf(), row.constants, row.op, activator = 0)
            for (form in listOf(
                LinearForm.Relaxation(row.linearRows),
                LinearForm.Conjunction(row.linearRows + row.linearRows),
                LinearForm.Disjunction(row.linearRows),
                LinearForm.Conjunction(listOf(activated)),
            )) {
                val wrapper = object : Factor by row {
                    override val linearForm: LinearForm = form
                }
                val factors = if (row === equality) listOf(wrapper, target) else listOf(equality, wrapper)
                val problem = Problem(1, 3, Array(3) { IntDomain(-5, 5) }, factors)

                assertTrue(LinearSubSumAggregation.sparsifyRows(problem).isEmpty)
            }
        }
    }

    @Test
    fun `fill-in and zero terms should not masquerade as a strict nonzero reduction`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        for (target in listOf(
            Linear(intArrayOf(4, 6, 0), intArrayOf(0, 3, 2), LinearOp.LE, 12),
            Linear(intArrayOf(4, 6), intArrayOf(0, 3), LinearOp.LE, 12),
            Linear(intArrayOf(4, 7, 11), intArrayOf(0, 1, 3), LinearOp.LE, 12),
        )) {
            val problem = Problem(0, 4, Array(4) { IntDomain(-5, 5) }, listOf(equality, target))

            assertTrue(LinearSubSumAggregation.sparsifyRows(problem).isEmpty)
        }
    }

    @Test
    fun `wide and real-bearing rows should remain outside integer row sparsification`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        for (row in listOf(equality, target)) {
            val constants = checkNotNull(row.integerConstants)
            val wide = Linear(
                row.vars,
                Array(3) { bigIntOf(constants.coeff(it)) * bigIntOf(Long.MAX_VALUE) },
                row.op,
                bigIntOf(constants.bound),
            )
            val real = Linear(constants.coeffs, row.vars, doubleArrayOf(1.0), intArrayOf(0), row.op, constants.bound)
            for (unsupported in listOf(wide, real)) {
                val factors = if (row === equality) listOf(unsupported, target) else listOf(equality, unsupported)
                val problem = Problem(
                    0,
                    3,
                    Array(3) { IntDomain(-5, 5) },
                    factors,
                    numRealVars = 1,
                    realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                    realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
                )

                assertTrue(LinearSubSumAggregation.sparsifyRows(problem).isEmpty)
            }
        }
    }

    @Test
    fun `unsafe coefficient growth and arithmetic should leave target factors intact`() {
        val cases = listOf(
            longArrayOf(2, 3, 5) to longArrayOf(Long.MIN_VALUE, 6, 7),
            longArrayOf(2, 3, 5) to longArrayOf(Long.MAX_VALUE, 6, 7),
            longArrayOf(2, 3, 100) to longArrayOf(200, 300, 1),
            longArrayOf(2, 3, 999_999_999_999_999) to longArrayOf(4, 6, 7),
            longArrayOf(2, 3, 999_999_999_999_999) to longArrayOf(20_000, 30_000, 7),
        )
        for ((coefficients, targetCoefficients) in cases) {
            val equality = Linear(coefficients, intArrayOf(0, 1, 2), LinearOp.EQ, 7)
            val target = Linear(targetCoefficients, intArrayOf(0, 1, 2), LinearOp.LE, 12)
            val problem = Problem(0, 3, Array(3) { IntDomain(-5, 5) }, listOf(equality, target))

            assertTrue(LinearSubSumAggregation.sparsifyRows(problem).isEmpty)
        }
    }

    @Test
    fun `sparsification should remain opt-in for solution-set-sensitive requests`() {
        val context = PresolveContext(solutionSetSensitive = true)

        assertFalse(PresolveConfig.DEFAULT.resolved(PresolvePass.SPARSIFY_LINEAR_ROWS, context))
        assertTrue(PresolveConfig.parse("off,+sparsify").resolved(PresolvePass.SPARSIFY_LINEAR_ROWS, context))
    }

    @Test
    fun `an explicit sparsification request should use shared source invocation accounting`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val problem = Problem(0, 3, Array(3) { IntDomain(-5, 5) }, listOf(equality, target))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.runSource(
            problem,
            PresolveConfig.parse("off,+sparsify").withEffort(maxRounds = 1),
            PresolveContext(presolveBudget = budget, solutionSetSensitive = true),
            budget.orSpent(Cancellation.Never),
        )

        assertEquals(2, result.problem.numFactors)
        assertEquals(listOf(1, 3), result.problem.factors.map { (it as Linear).vars.size }.sorted())
        val invocation = budget.invocations.single()
        assertEquals("sparsify", invocation.pass)
        assertEquals(PresolveInvocationOutcome.CHANGED, invocation.outcome)
        assertEquals(budget.spent(), invocation.work)
        assertTrue(invocation.work > 0)
    }

    @Test
    fun `complete cancellation should preserve constant comparisons`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        for (op in LinearOp.entries) {
            val target = Linear(intArrayOf(4, 6, 10), intArrayOf(0, 1, 2), op, 12)
            val problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, listOf(equality, target))

            val delta = LinearSubSumAggregation.sparsifyRows(problem)

            val replacement = delta.addedFactors.single() as Linear
            assertTrue(checkNotNull(replacement.integerConstants).coeffs.all { it == 0L })
            assertEquals(
                feasible(problem.factors, LongArray(3) { -2 }, LongArray(3) { 2 }),
                feasible(arrayOf(equality, replacement), LongArray(3) { -2 }, LongArray(3) { 2 }),
            )
        }
    }

    @Test
    fun `bound overflow should discard an otherwise sparse replacement`() {
        val equality = Linear(longArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 999_999_999_999_999)
        val target = Linear(longArrayOf(20_000, 30_000, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, listOf(equality, target))

        assertTrue(LinearSubSumAggregation.sparsifyRows(problem).isEmpty)
    }

    @Test
    fun `sparsification should preserve equality dependencies across repeated passes`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val other = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.EQ, 11)
        for (factors in listOf(listOf(equality, target, other), listOf(other, target, equality))) {
            var problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, factors)
            val expected = feasible(problem.factors, LongArray(3) { -2 }, LongArray(3) { 2 })
            repeat(3) {
                val delta = LinearSubSumAggregation.sparsifyRows(problem)
                val remaining =
                    problem.factors.filterIndexed { i, _ -> i !in delta.droppedIndices } + delta.addedFactors
                problem = problem.withFactors(remaining.toTypedArray())

                assertEquals(expected, feasible(problem.factors, LongArray(3) { -2 }, LongArray(3) { 2 }))
            }
        }
    }

    @Test
    fun `an unsuccessful sparsification call should charge only input visits when no equality exists`() {
        val factors = List(64) { Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12) }
        val problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, factors)
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = LinearSubSumAggregation.sparsifyRows(problem, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertEquals(4L * factors.size, budget.spent())
    }

    @Test
    fun `an already cancelled sparsification call should charge no work`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 2) }, listOf(sumDef()))
        val budget = PresolveBudget(1000)

        val delta = LinearSubSumAggregation.sparsifyRows(problem, budget.orSpent(Cancellation { true }))

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `sparsification input visits should stop within one allowance poll interval`() {
        val width = 128
        val problem = Problem(
            0,
            width,
            Array(width) { IntDomain(0, 2) },
            listOf(Linear(IntArray(width) { 2 }, IntArray(width) { it }, LinearOp.EQ, 0)),
        )
        val budget = PresolveBudget(16)

        val delta = LinearSubSumAggregation.sparsifyRows(problem, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertTrue(budget.spent() in 16L..79L)
    }

    @Test
    fun `sparsification cancellation should publish only completed rows and retain their premise`() {
        val equality = Linear(intArrayOf(2, 3, 5), intArrayOf(0, 1, 2), LinearOp.EQ, 7)
        val target = Linear(intArrayOf(4, 6, 7), intArrayOf(0, 1, 2), LinearOp.LE, 12)
        val problem = Problem(0, 3, Array(3) { IntDomain(-2, 2) }, listOf(equality) + List(16) { target })
        val completeBudget = PresolveBudget(Long.MAX_VALUE)
        val complete = LinearSubSumAggregation.sparsifyRows(problem, completeBudget.orSpent(Cancellation.Never))
        val budget = PresolveBudget(completeBudget.spent() * 3 / 4)

        val delta = LinearSubSumAggregation.sparsifyRows(problem, budget.orSpent(Cancellation.Never))

        assertEquals(16, complete.addedFactors.size)
        assertTrue(delta.addedFactors.size in 1 until 16)
        assertTrue(0 !in delta.droppedIndices)
        assertTrue(budget.spent() in budget.allowance..(budget.allowance + 63))
        val remaining = problem.factors.filterIndexed { i, _ -> i !in delta.droppedIndices } + delta.addedFactors
        assertEquals(
            feasible(problem.factors, LongArray(3) { -2 }, LongArray(3) { 2 }),
            feasible(remaining.toTypedArray(), LongArray(3) { -2 }, LongArray(3) { 2 }),
        )
    }

    @Test
    fun `dense overlap scans should remain bounded and leave independent cheap rewrites available`() {
        val count = 256
        val factors = List(count) { i ->
            Linear(intArrayOf(2, i + 3, i + 4), intArrayOf(i, count, count + 1), LinearOp.EQ, 0)
        } + listOf(
            Linear(intArrayOf(2, 3, 5), intArrayOf(count + 2, count + 3, count + 4), LinearOp.EQ, 7),
            Linear(intArrayOf(4, 6, 7), intArrayOf(count + 2, count + 3, count + 4), LinearOp.LE, 12),
        )
        val problem = Problem(0, count + 5, Array(count + 5) { IntDomain(-2, 2) }, factors)
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = LinearSubSumAggregation.sparsifyRows(problem, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(factors.lastIndex), delta.droppedIndices.toList())
        assertTrue(budget.spent() in 1L..(500L * factors.size))
    }

    private fun sumDef() = Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)

    private fun linears(p: Problem) = p.factors.filterIsInstance<Linear>()

    private fun theInequality(p: Problem): Linear = linears(p).single { it.op == LinearOp.LE }

    private fun run(vararg factors: Factor): Problem {
        val p = Problem(0, 4, Array(4) { IntDomain(0, 8) }, factors.toList())
        return p.bake().withPassDelta(Presolve.aggregateSubSums(p), BakeConfig.NONE)
    }

    @Test
    fun `a partial sub-sum should be left untouched`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(1, 1), intArrayOf(1, 3), LinearOp.LE, 5)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `an unevenly scaled sub-sum should be left untouched`() {
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(sumDef(), Linear(intArrayOf(2, 1, 1), intArrayOf(1, 2, 3), LinearOp.LE, 9)),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `a zero-coefficient partner should not count as a sub-sum partner`() {
        // Multiplier matching must not take `c % 0` on the zero term.
        val p = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 8) },
            listOf(
                Linear(intArrayOf(1, 0, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 5),
            ),
        )
        assertTrue(Presolve.aggregateSubSums(p).isEmpty)
    }

    @Test
    fun `a used definition should stay valid through later dependencies and repeated passes`() {
        val definition = Linear(intArrayOf(1, -1, -1), intArrayOf(1, 2, 3), LinearOp.EQ, 0)
        val target = Linear(intArrayOf(2, 2, 1), intArrayOf(2, 3, 4), LinearOp.LE, 3)
        val dependency = Linear(intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)
        for (factors in listOf(listOf(definition, target, dependency), listOf(dependency, target, definition))) {
            val problem = Problem(0, 5, Array(5) { IntDomain(0, 2) }, factors)
            val expected = feasible(problem.factors, LongArray(5), LongArray(5) { 2 })
            val delta = Presolve.aggregateSubSums(problem)

            var output = problem.bake().withPassDelta(delta, BakeConfig.NONE)

            assertTrue(delta.addedFactors.isNotEmpty())
            assertEquals(expected, feasible(output.factors, LongArray(5), LongArray(5) { 2 }))
            repeat(3) {
                output = output.bake().withPassDelta(Presolve.aggregateSubSums(output), BakeConfig.NONE)
                assertEquals(expected, feasible(output.factors, LongArray(5), LongArray(5) { 2 }))
            }
        }
    }

    @Test
    fun `unrepresentable substitutions should retain their original constraints`() {
        val cases = listOf(
            Linear(longArrayOf(1, Long.MIN_VALUE, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0) to
                Linear(longArrayOf(Long.MIN_VALUE, -1), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(longArrayOf(-1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, Long.MIN_VALUE) to
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 0) to
                Linear(longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 2) to
                Linear(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 1) to
                Linear(longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE), intArrayOf(1, 2), LinearOp.LE, 0),
            sumDef() to Linear(longArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, Long.MIN_VALUE),
            sumDef() to Linear(longArrayOf(Long.MAX_VALUE, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 0),
        )
        for ((definition, target) in cases) {
            val problem = Problem(0, 4, Array(4) { IntDomain(0, 1) }, listOf(definition, target))

            val delta = Presolve.aggregateSubSums(problem)

            assertTrue(delta.isEmpty)
        }
    }

    private fun feasible(factors: Array<Factor>, mins: LongArray, maxs: LongArray): Set<List<Long>> {
        val out = HashSet<List<Long>>()
        val assign = mins.copyOf()
        fun holds(): Boolean = factors.all { f ->
            f as Linear
            var sum = BIG_ZERO
            for (j in f.vars.indices) {
                sum += bigIntOf(checkNotNull(f.integerConstants).coeffs[j]) *
                    bigIntOf(assign[f.vars[j]])
            }
            val bound = bigIntOf(checkNotNull(f.integerConstants).bound)
            when (f.op) {
                LinearOp.LE -> sum <= bound
                LinearOp.EQ -> sum == bound
                LinearOp.NE -> sum != bound
                LinearOp.GE -> sum >= bound
            }
        }
        fun recurse(i: Int) {
            if (i == assign.size) {
                if (holds()) out.add(assign.toList())
                return
            }
            var v = mins[i]
            while (v <= maxs[i]) {
                assign[i] = v
                recurse(i + 1)
                v++
            }
        }
        recurse(0)
        return out
    }
}
