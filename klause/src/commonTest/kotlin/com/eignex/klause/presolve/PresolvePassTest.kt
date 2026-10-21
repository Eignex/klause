package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresolvePassTest {
    @Test
    fun `finite passes should poll the supplied cancellation before making reductions`() {
        val probing = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ),
        ).bake()
        val implications = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ),
        ).bake()
        val precedence = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2))),
        ).bake()

        for ((pass, problem) in listOf(
            PresolvePass.PROBE to probing,
            PresolvePass.IMPLICATION_GRAPH to implications,
            PresolvePass.VALUE_PRECEDENCE to precedence,
        )) {
            assertFalse(pass.applyFinite(problem, PresolveContext.EMPTY).isEmpty, pass.id)
            var polls = 0
            val context = PresolveContext(cancellation = Cancellation { polls++; true })

            val delta = pass.applyFinite(problem, context)

            assertTrue(delta.isEmpty, pass.id)
            assertTrue(polls > 0, pass.id)
        }
    }

    @Test
    fun `finite probing passes should bill native construction and propagation to the supplied budget`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
            ),
        ).bake()

        for (pass in listOf(PresolvePass.PROBE, PresolvePass.IMPLICATION_GRAPH)) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val context = PresolveContext(cancellation = budget.orSpent(Cancellation.Never))

            pass.applyFinite(problem, context)

            assertTrue(budget.rootConstructionEntries > 0, pass.id)
            assertTrue(budget.rootPropagationWork > 0, pass.id)
            assertTrue(budget.spent() >= 53 * (budget.rootConstructionEntries + budget.rootPropagationWork), pass.id)
        }
    }

    @Test
    fun `value precedence should stop when orbit discovery spends the supplied budget`() {
        val problem = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2))),
        ).bake()
        val budget = PresolveBudget(3)

        val delta = PresolvePass.VALUE_PRECEDENCE.applyFinite(
            problem, PresolveContext(cancellation = budget.orSpent(Cancellation.Never)),
        )

        assertTrue(delta.isEmpty)
        assertEquals(3L, budget.spent())
    }

    @Test
    fun `native work exhaustion should stop finite probing before the next candidate`() {
        val problem = Problem(
            3, 0, emptyArray(),
            listOf(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))),
        ).bake()

        for (pass in listOf(PresolvePass.PROBE, PresolvePass.IMPLICATION_GRAPH)) {
            val limited = PresolveBudget(1)
            val unlimited = PresolveBudget(Long.MAX_VALUE)

            pass.applyFinite(problem, PresolveContext(cancellation = limited.orSpent(Cancellation.Never)))
            pass.applyFinite(problem, PresolveContext(cancellation = unlimited.orSpent(Cancellation.Never)))

            assertTrue(limited.remaining() == 0L, pass.id)
            assertEquals(0L, limited.rootConstructionEntries, pass.id)
            assertEquals(10L, limited.spent(), pass.id)
            assertTrue(limited.rootConstructionEntries < unlimited.rootConstructionEntries, pass.id)
        }
    }

    @Test
    fun `SAT passes should retain typed reconstruction under the same source and finite currency`() {
        val source = Problem(
            2, 0, emptyArray(),
            listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 3))),
        )
        val finite = source.bake()
        for (pass in listOf(PresolvePass.ELIMINATE_BOOL_VARS, PresolvePass.ELIMINATE_BLOCKED_CLAUSES)) {
            val sourceBudget = PresolveBudget(10_000L)
            val finiteBudget = PresolveBudget(10_000L)

            val sourceDelta = pass.applySource(
                source, PresolveContext(cancellation = sourceBudget.orSpent(Cancellation.Never)),
            )
            val finiteDelta = pass.applyFinite(
                finite, PresolveContext(cancellation = finiteBudget.orSpent(Cancellation.Never)),
            )

            assertFalse(sourceDelta.isEmpty)
            assertFalse(sourceDelta.rebuild.isEmpty)
            assertFalse(finiteDelta.isEmpty)
            assertFalse(finiteDelta.rebuild.isEmpty)
            assertEquals(sourceBudget.spent(), finiteBudget.spent())
            assertTrue(sourceBudget.spent() in 1L..999L)
            for (mask in 0 until 4) {
                val point = BooleanArray(2) { mask and (1 shl it) != 0 }
                for (rebuild in listOf(sourceDelta.rebuild, finiteDelta.rebuild)) {
                    val lifted = point.copyOf()
                    rebuild.rebuildInto(lifted)
                    assertTrue(source.factors.filterIsInstance<Clause>().all { clause ->
                        clause.literals.any { Lit.evaluate(it, lifted[Lit.variable(it)]) }
                    })
                }
            }
        }
    }

    @Test
    fun `source probing should derive Boolean units beside real rows with no open integer`() {
        for (integers in 0..1) {
            val problem = Problem(
                2, integers, Array(integers) { IntDomain(0, 2) },
                listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                    Linear(
                        intVars = intArrayOf(), intCoeffs = doubleArrayOf(),
                        realVars = intArrayOf(0), realCoeffs = doubleArrayOf(1.0),
                        op = LinearOp.GE, bound = 0.0,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            )

            val prepared = Presolver.runSource(problem, PresolveConfig.parse("off,+probe-open"))

            assertEquals(listOf(PresolvePass.PROBE_OPEN_RANGES), prepared.passesFired)
            assertTrue(prepared.problem.factors.filterIsInstance<Clause>().any {
                it.literals.contentEquals(intArrayOf(Lit.make(0, true)))
            })
        }
    }

    @Test
    fun `source symmetry should order Booleans beside real rows with no open integer`() {
        for (integers in 0..1) {
            val problem = Problem(
                2, integers, Array(integers) { IntDomain(0, 2) },
                listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Linear(
                        intVars = intArrayOf(), intCoeffs = doubleArrayOf(),
                        realVars = intArrayOf(0), realCoeffs = doubleArrayOf(1.0),
                        op = LinearOp.GE, bound = 0.0,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
            )

            val prepared = Presolver.runSource(problem, PresolveConfig.parse("off,+symmetry"))

            assertEquals(listOf(PresolvePass.BREAK_SYMMETRIES), prepared.passesFired)
            assertTrue(prepared.problem.factors.filterIsInstance<Clause>().any {
                it.literals.contentEquals(intArrayOf(Lit.make(0, false), Lit.make(1, true)))
            })

            val sensitive = Presolver.runSource(
                problem, PresolveConfig.parse("off,+symmetry"), PresolveContext(solutionSetSensitive = true),
            )
            assertTrue(sensitive.passesFired.isEmpty())
        }
    }
}
