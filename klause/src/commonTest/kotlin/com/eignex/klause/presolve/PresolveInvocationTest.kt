package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolveSizeStats
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolveInvocationTest {
    @Test
    fun `full model passes should stop before analysis when entry charging exhausts the allowance`() {
        for (id in listOf("dup-columns", "fuse-bounds", "subsume", "structural")) {
            for (lane in PresolveLane.entries) {
                var reads = 0
                val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)
                val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                    override val structuralKeyWeight: Int get() = 2
                    override val intVars: IntArray get() {
                        reads++
                        return intArrayOf(0, 1)
                    }
                    override val linearForm: LinearForm get() {
                        reads++
                        return row.linearForm
                    }
                    override fun structuralKey(): StructuralKey {
                        reads++
                        return row.structuralKey()
                    }
                    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
                        reads++
                        return FactorReduction.Unchanged
                    }
                }
                val source = Problem(0, 2, Array(2) { IntDomain(0, 10) }, List(8) { factor })
                val problem = if (lane == PresolveLane.FINITE) source.bake() else source
                reads = 0
                val budget = PresolveBudget(if (lane == PresolveLane.FINITE) 2L else 1L)
                val token = budget.orSpent(Cancellation.Never)
                val context = PresolveContext(presolveBudget = budget)
                val config = PresolveConfig.parse(if (lane == PresolveLane.SOURCE) "off,+$id" else "off,+$id,+bce")

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(assertIs<BakedProblem>(problem), config, context, token)
                }

                assertEquals(0, reads, "$id $lane")
                val call = budget.invocations.first { it.pass == id }
                assertEquals(PresolveInvocationOutcome.INTERRUPTED, call.outcome)
                assertEquals(8L + 24L * PresolvePass.entries.single { it.id == id }.baseWorkWeight, call.work)
            }
        }
    }

    @Test
    fun `full model passes should bound factor reads after a midscan stop in either lane`() {
        val passes = listOf(PresolvePass.MERGE_DUPLICATE_COLUMNS, PresolvePass.FUSE_LINEAR_BOUNDS,
            PresolvePass.REMOVE_REDUNDANT, PresolvePass.REDUCE_STRUCTURAL)
        for (pass in passes) {
            for (lane in PresolveLane.entries) {
                var reads = 0
                val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 10)
                val factor = object : Factor by row, Propagator by row.propagatorProjection() {
                    override val structuralKeyWeight: Int get() = 2
                    override val intVars: IntArray get() {
                        reads++
                        return intArrayOf(0, 1)
                    }
                    override val linearForm: LinearForm get() {
                        reads++
                        return row.linearForm
                    }
                    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
                        reads++
                        return FactorReduction.Unchanged
                    }
                }
                val problem = BakedProblem(
                    0, 2, Array(2) { IntDomain(0, 10) }, List(128) { factor }, alreadyFolded = true,
                )
                reads = 0
                val budget = PresolveBudget(200L)
                val context = PresolveContext(cancellation = budget.orSpent(Cancellation.Never))

                val empty = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                    PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                }

                assertTrue(empty, "$pass $lane")
                assertTrue(reads in 1..127, "$pass $lane: $reads reads")
                assertTrue(budget.spent() in 200L..1_300L, "$pass $lane: ${budget.spent()} work")
            }
        }
    }

    @Test
    fun `row analyses should stop inside a long term scan in either lane`() {
        for (pass in listOf(PresolvePass.MERGE_DUPLICATE_COLUMNS, PresolvePass.FUSE_LINEAR_BOUNDS,
            PresolvePass.REMOVE_REDUNDANT)) {
            for (lane in PresolveLane.entries) {
                var refs = 0
                val linear = Linear(IntArray(256) { 1 }, IntArray(256) { it }, LinearOp.LE, 100)
                val row = object : LinearRow by linear {
                    override fun ref(k: Int): Int {
                        refs++
                        return linear.ref(k)
                    }
                }
                val factor = object : Factor by linear, Propagator by linear.propagatorProjection() {
                    override val structuralKeyWeight: Int get() = 256
                    override val linearForm = LinearForm.Conjunction(listOf(row))
                }
                val problem = BakedProblem(0, 256, Array(256) { IntDomain(0, 2) }, listOf(factor), alreadyFolded = true)
                val allowance = when (pass) {
                    PresolvePass.FUSE_LINEAR_BOUNDS -> 1_500L
                    PresolvePass.MERGE_DUPLICATE_COLUMNS -> if (lane == PresolveLane.FINITE) 6_000L else 3_500L
                    else -> 3_500L
                }
                val budget = PresolveBudget(allowance)
                val context = PresolveContext(cancellation = budget.orSpent(Cancellation.Never))
                refs = 0

                val empty = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                    PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                }

                assertTrue(empty, "$pass $lane")
                assertTrue(refs in 1..255, "$pass $lane: $refs term reads")
                assertTrue(budget.spent() in allowance..allowance + 1_024L, "$pass $lane: ${budget.spent()} work")
            }
        }
    }

    @Test
    fun `row scans should poll a live token once per stride without changing their charged work`() {
        val count = 1_000
        val problem = BakedProblem(0, count, Array(count) { IntDomain(0, 10) },
            List(count) { Linear(intArrayOf(1, 1), intArrayOf(it, (it + 1) % count), LinearOp.LE, 10) },
            alreadyFolded = true)
        for (pass in listOf(PresolvePass.MERGE_DUPLICATE_COLUMNS, PresolvePass.FUSE_LINEAR_BOUNDS,
            PresolvePass.REMOVE_REDUNDANT)) {
            for (lane in PresolveLane.entries) {
                var polls = 0
                val budgets = List(2) { PresolveBudget(Long.MAX_VALUE) }
                val tokens = listOf(budgets[0].orSpent(Cancellation.Never), budgets[1].orSpent(Cancellation {
                    polls++
                    false
                }))

                val empty = tokens.map {
                    val context = PresolveContext(cancellation = it)
                    when (lane) {
                        PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                        PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                    }
                }

                assertEquals(listOf(true, true), empty, "$pass $lane")
                assertEquals(budgets[0].spent(), budgets[1].spent(), "$pass $lane")
                assertTrue(polls <= budgets[1].spent() / 512L + 6, "$pass $lane: $polls polls")
            }
        }
    }

    @Test
    fun `a live stop should end row scans within one poll stride in either lane`() {
        for (pass in listOf(PresolvePass.MERGE_DUPLICATE_COLUMNS, PresolvePass.FUSE_LINEAR_BOUNDS,
            PresolvePass.REMOVE_REDUNDANT)) {
            for (lane in PresolveLane.entries) {
                var reads = 0
                val factors = List(512) {
                    val row = Linear(intArrayOf(1, 1), intArrayOf(it, it + 1), LinearOp.LE, 10)
                    object : Factor by row, Propagator by row.propagatorProjection() {
                        override val linearForm: LinearForm get() {
                            reads++
                            return row.linearForm
                        }
                    }
                }
                val problem = BakedProblem(0, 513, Array(513) { IntDomain(0, 10) }, factors, alreadyFolded = true)
                reads = 0
                val context = PresolveContext(cancellation = Cancellation { reads >= 8 })

                val empty = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                    PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                }

                assertTrue(empty, "$pass $lane")
                assertTrue(reads in 8..72, "$pass $lane: $reads reads")
            }
        }
    }

    @Test
    fun `full model passes should retain productive metered controls in either lane`() {
        val upper = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)
        val cases = listOf(
            PresolvePass.MERGE_DUPLICATE_COLUMNS to BakedProblem(0, 2, Array(2) { IntDomain(0, 2) },
                listOf(upper), openIntHi = booleanArrayOf(true, false), alreadyFolded = true),
            PresolvePass.FUSE_LINEAR_BOUNDS to BakedProblem(0, 2, Array(2) { IntDomain(0, 2) },
                listOf(upper, Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)), alreadyFolded = true),
            PresolvePass.REMOVE_REDUNDANT to BakedProblem(0, 2, Array(2) { IntDomain(0, 2) },
                listOf(upper, upper), alreadyFolded = true),
            PresolvePass.REDUCE_STRUCTURAL to BakedProblem(0, 2, arrayOf(IntDomain(2, 2), IntDomain(0, 100)),
                listOf(Element(idx = 0, result = 1, arr = longArrayOf(10, 20, 30), arrIsVars = false)),
                alreadyFolded = true),
        )
        for ((pass, problem) in cases) {
            for (lane in PresolveLane.entries) {
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(cancellation = budget.orSpent(Cancellation.Never))

                val empty = when (lane) {
                    PresolveLane.SOURCE -> pass.applySource(problem, context).isEmpty
                    PresolveLane.FINITE -> pass.applyFinite(problem, context).isEmpty
                }

                assertFalse(empty, "$pass $lane")
                assertTrue(budget.spent() > 0L, "$pass $lane")
            }
        }
    }

    @Test
    fun `Boolean cover pins should count as fixed columns in source and both finite hosts`() {
        val pairs = 8
        val problem = Problem(2 * pairs, 0, emptyArray(), List(pairs) { Clause(intArrayOf(4 * it, 4 * it + 2)) })
        val costs = (0 until 2 * pairs).associateWith { if (it % 2 == 0) 2L else 1L }
        for (lane in PresolveLane.entries) {
            for (config in listOf("off,+dual-fix", "off,+dual-fix,+bce")) {
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(objectiveBoolCoeffs = costs, presolveBudget = budget)
                val token = budget.orSpent(Cancellation.Never)

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, PresolveConfig.parse(config), context, token)
                    PresolveLane.FINITE -> Presolver.run(problem.bake(), PresolveConfig.parse(config), context, token)
                }

                val call = budget.invocations.first { it.pass == "dual-fix" }
                assertEquals(pairs, call.fixedColumns, "$lane $config")
                assertEquals(0, call.narrowedColumns)
                assertEquals(pairs, call.output.factors - call.input.factors)
                assertEquals(pairs.toLong(), call.output.occurrences!! - call.input.occurrences!!)
            }
        }
    }

    @Test
    fun `Boolean pin accounting should exclude duplicate and previously reported columns`() {
        val pins = PresolveBooleanPins()
        val first = PresolveInvocation("dual-fix", PresolveLane.SOURCE, PresolveSizeStats(0, 3, 0))
        val second = PresolveInvocation("dual-fix", PresolveLane.SOURCE, PresolveSizeStats(0, 3, 0))

        pins.record(first, listOf(Clause(intArrayOf(0)), Clause(intArrayOf(0)), Clause(intArrayOf(2)))) { it == 1 }
        pins.record(second, listOf(Clause(intArrayOf(0)), Clause(intArrayOf(2)), Clause(intArrayOf(4)))) { false }

        assertEquals(1, first.fixedColumns)
        assertEquals(1, second.fixedColumns)
    }

    @Test
    fun `existing source unit pins should retain zero fixing credit`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1))))
        val budget = PresolveBudget(Long.MAX_VALUE)

        Presolver.runSource(problem, PresolveConfig.parse("off,+dual-fix"), PresolveContext(
            objectiveBoolCoeffs = mapOf(0 to 2L, 1 to 1L), presolveBudget = budget,
        ), budget.orSpent(Cancellation.Never))

        assertEquals(0, budget.invocations.sumOf { it.fixedColumns })
        assertEquals(2L, budget.entrySizeVisits)
    }

    @Test
    fun `initial source pins should stay known after their factor revision is replaced`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val pins = PresolveBooleanPins()
        val unit = Clause(intArrayOf(0))
        budget.entryUnits(arrayOf(unit))
        pins.seed(budget.entryBooleanPins)
        budget.entryUnits(emptyArray())
        val invocation = PresolveInvocation("probe", PresolveLane.SOURCE, PresolveSizeStats(0, 1, 0))

        pins.record(invocation, listOf(unit)) { false }

        assertEquals(0, invocation.fixedColumns)
        assertEquals(1L, budget.entrySizeVisits)
    }

    @Test
    fun `finite root fixings should receive no credit when a pass emits their unit again`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Cardinality(intArrayOf(0), min = 1, max = 1), Clause(intArrayOf(2, 4)),
        )).bake()
        for (config in listOf("off,+dual-fix", "off,+dual-fix,+bce")) {
            val budget = PresolveBudget(Long.MAX_VALUE)

            Presolver.run(problem, PresolveConfig.parse(config), PresolveContext(
                objectiveBoolCoeffs = mapOf(1 to 2L, 2 to 1L), presolveBudget = budget,
            ), budget.orSpent(Cancellation.Never))

            val call = budget.invocations.first { it.pass == "dual-fix" }
            assertEquals(2, call.output.factors - call.input.factors)
            assertEquals(1, call.fixedColumns)
        }
    }

    @Test
    fun `interrupted Boolean cover indexing should receive no fixing credit`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2))))
        for (lane in PresolveLane.entries) {
            val setup = PresolveBudget(Long.MAX_VALUE)
            if (lane == PresolveLane.FINITE) {
                PresolveSession(problem.bake(), cancellation = setup.orSpent(Cancellation.Never)).passInput()
            }
            val budget = PresolveBudget(setup.spent() + 32L)
            val context = PresolveContext(objectiveBoolCoeffs = mapOf(0 to 2L, 1 to 1L), presolveBudget = budget)
            val token = budget.orSpent(Cancellation.Never)
            val config = PresolveConfig.parse("off,+dual-fix")

            when (lane) {
                PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
            }

            val call = budget.invocations.single()
            assertEquals(PresolveInvocationOutcome.INTERRUPTED, call.outcome)
            assertEquals(0, call.fixedColumns)
            assertEquals(call.input.factors, call.output.factors)
        }
    }

    @Test
    fun `unsupported clause analyses should skip before entry accounting in either lane`() {
        val problem = Problem(3, 0, emptyArray(), List(8) { Clause(intArrayOf(0, 2, 4)) })
        for (lane in PresolveLane.entries) {
            val budget = PresolveBudget(Long.MAX_VALUE)
            val token = budget.orSpent(Cancellation.Never)
            val config = PresolveConfig.parse("off,+probe-open,+fuse-bounds,+dependent-rows,+sparsify")
            val context = PresolveContext(presolveBudget = budget)

            when (lane) {
                PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
            }

            assertEquals(if (lane == PresolveLane.SOURCE) 4 else 3, budget.invocations.size)
            assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.ELIGIBILITY_SKIP })
            assertTrue(budget.invocations.all { it.work == 0L && it.input.occurrences == null })
            assertEquals(0L, budget.entrySizeVisits)
        }
    }

    @Test
    fun `closed integer source probing should skip before entry accounting`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)))
        val budget = PresolveBudget(Long.MAX_VALUE)

        Presolver.runSource(problem, PresolveConfig.parse("off,+probe-open"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.single().outcome)
        assertEquals(0L, budget.spent())
        assertEquals(0L, budget.entrySizeVisits)
    }

    @Test
    fun `comparison folding without integers should skip entry work in either lane`() {
        for (reals in 0..1) {
            val source = Problem(
                3, 0, emptyArray(), List(8) { Clause(intArrayOf(0, 2, 4)) },
                numRealVars = reals,
                realLower = DoubleArray(reals) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(reals) { Double.POSITIVE_INFINITY },
            )
            for (lane in PresolveLane.entries) {
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val token = budget.orSpent(Cancellation.Never)
                val config = PresolveConfig.parse("off,+comparison-clause")

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(source, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(source.bake(), config, context, token)
                }

                val invocation = budget.invocations.single()
                assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, invocation.outcome, "$lane $reals")
                assertEquals(0L, invocation.work)
                assertEquals(0L, invocation.rebuildWork)
                assertEquals(0L, invocation.nativeWork)
                assertEquals(null, invocation.input.occurrences)
                assertEquals(null, invocation.output.occurrences)
                assertEquals(0L, budget.entrySizeVisits)
            }
        }
    }

    @Test
    fun `integer comparison folding should remain productive beside real and open columns in either lane`() {
        for (reals in 0..1) {
            val integers = 2 + reals
            val source = Problem(
                2, integers, Array(integers) { IntDomain(0, 3) },
                listOf(
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(1), LinearOp.GE, 2),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                ),
                numRealVars = reals,
                realLower = DoubleArray(reals) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(reals) { Double.POSITIVE_INFINITY },
                openIntHi = BooleanArray(integers) { it == 2 },
            )
            for (lane in PresolveLane.entries) {
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val token = budget.orSpent(Cancellation.Never)
                val config = PresolveConfig.parse("off,+comparison-clause")

                val reduced = when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(source, config, context, token).problem
                    PresolveLane.FINITE -> Presolver.run(source.bake(), config, context, token).problem
                }

                val invocation = budget.invocations.first()
                assertEquals(PresolveInvocationOutcome.CHANGED, invocation.outcome, "$lane $reals")
                assertTrue(invocation.work > 0L)
                val folded = assertIs<ComparisonClause>(reduced.factors.single())
                assertContentEquals(intArrayOf(0, 1), folded.vars)
                assertContentEquals(arrayOf(LinearOp.LE, LinearOp.LE), folded.ops)
                assertContentEquals(longArrayOf(1, 1), folded.consts)
            }
        }
    }

    @Test
    fun `integer row analyses should retain productive calls in either lane`() {
        val equality = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
        val cases = listOf(
            "fuse-bounds" to listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
            "dependent-rows" to listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 3),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.EQ, 4),
                Linear(intArrayOf(1, 2, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 7),
            ),
            "sparsify" to listOf(equality,
                Linear(intArrayOf(2, 2, 3), intArrayOf(0, 1, 2), LinearOp.LE, 7)),
        )
        for ((pass, factors) in cases) {
            for (lane in PresolveLane.entries) {
                val problem = Problem(0, 3, Array(3) { IntDomain(0, 10) }, factors)
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val token = budget.orSpent(Cancellation.Never)
                val config = PresolveConfig.parse("off,+$pass")

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
                }

                assertEquals(PresolveInvocationOutcome.CHANGED, budget.invocations.first().outcome, "$pass $lane")
                assertTrue(budget.invocations.first().work > 0L)
            }
        }
    }

    @Test
    fun `probing without integer columns should retain constant row refutation`() {
        val problem = Problem(0, 0, emptyArray(), listOf(
            ExactLinear.of(intArrayOf(), emptyList(), LinearOp.LE, BigFraction.ONE.negated()),
        ))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.runSource(problem, PresolveConfig.parse("off,+probe"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertTrue(result.infeasible)
        assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.single().outcome)
    }

    @Test
    fun `sparsification admission should follow the live factor count before parking`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyList())
        val pass = PresolvePass.SPARSIFY_LINEAR_ROWS

        assertFalse(pass.namespaceEligible(problem, 200_001))
        assertTrue(pass.namespaceEligible(problem, 200_000))
    }

    @Test
    fun `clause subsumption eligibility should follow the supplied live factor count at its cap`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2, 4))))
        val pass = PresolvePass.SUBSUME_CLAUSES

        assertFalse(pass.namespaceEligible(problem, 200_001))
        assertTrue(pass.namespaceEligible(problem, 200_000))
    }

    @Test
    fun `finite clause subsumption should read clauses retained by earlier reductions`() {
        val shorter = Clause(intArrayOf(0, 2))
        val problem = Problem(3, 0, emptyArray(),
            listOf(shorter, Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2, 4))))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.run(problem.bake(), PresolveConfig.parse("off,+subsume,+clause-subsume"),
            PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never))

        assertEquals(1, result.problem.factors.size)
        val call = budget.invocations.first { it.pass == "clause-subsume" }
        assertEquals(2, call.input.factors)
        assertEquals(PresolveInvocationOutcome.CHANGED, call.outcome)
    }

    @Test
    fun `aggregation without a pivot and two integer partners should skip entry analysis`() {
        for (numInts in 0..2) {
            for (lane in PresolveLane.entries) {
                val problem = Problem(
                    3, numInts, Array(numInts) { IntDomain(0, 10) },
                    listOf(Clause(IntArray(3) { Lit.make(it, true) })),
                )
                val budget = PresolveBudget(Long.MAX_VALUE)
                val context = PresolveContext(presolveBudget = budget)
                val config = PresolveConfig.parse("off,+aggregate")
                val token = budget.orSpent(Cancellation.Never)

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(problem.bake(), config, context, token)
                }

                val invocation = budget.invocations.single()
                assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, invocation.outcome)
                assertEquals(0L, invocation.work)
                assertEquals(0L, budget.entrySizeVisits)
            }
        }
    }

    @Test
    fun `Boolean namespace skips should avoid whole model entry work`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(2_048) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )
        val budget = PresolveBudget(1L)
        val token = budget.orSpent(Cancellation.Never)

        val result = Presolver.runSource(
            problem, PresolveConfig.parse("off,+bve,+bce,+impl-graph"),
            PresolveContext(presolveBudget = budget), token,
        )

        assertSame(problem, result.problem)
        assertEquals(3, budget.invocations.size)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.ELIGIBILITY_SKIP })
        assertTrue(budget.invocations.all { it.input.occurrences == null })
        assertEquals(0L, budget.entrySizeVisits)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `SAT passes should skip database reads when entry accounting exhausts the allowance`() {
        for (id in listOf("bve", "bce")) {
            for (lane in PresolveLane.entries) {
                var reads = 0
                val clause = Clause(intArrayOf(Lit.make(0, true)))
                val factor = object : Factor by clause, Propagator by clause.propagatorProjection() {
                    override val structuralKeyWeight: Int get() = 1
                    override val boolVars: IntArray
                        get() {
                            reads++
                            return intArrayOf(0)
                        }
                }
                val source = Problem(1, 0, emptyArray(), List(4) { factor })
                val problem = if (lane == PresolveLane.FINITE) source.bake() else source
                reads = 0
                val budget = PresolveBudget(1L)
                val context = PresolveContext(presolveBudget = budget)
                val config = PresolveConfig.parse("off,+$id")
                val token = budget.orSpent(Cancellation.Never)

                when (lane) {
                    PresolveLane.SOURCE -> Presolver.runSource(problem, config, context, token)
                    PresolveLane.FINITE -> Presolver.run(assertIs<BakedProblem>(problem), config, context, token)
                }

                assertEquals(0, reads, "$id $lane")
                val invocation = budget.invocations.single()
                assertEquals(PresolveInvocationOutcome.INTERRUPTED, invocation.outcome)
                assertEquals(84L, budget.spent())
                assertEquals(budget.spent(), invocation.work)
            }
        }
    }

    @Test
    fun `unchanged source calls should reuse aggregate size and retain complete misses`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(8) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val context = PresolveContext(presolveBudget = budget)
        val config = PresolveConfig.parse("off,+strengthen")
        val token = budget.orSpent(Cancellation.Never)

        repeat(2) { Presolver.runSource(problem, config, context, token) }

        assertEquals(8L, budget.entrySizeVisits)
        assertEquals(2, budget.invocations.size)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.COMPLETE_MISS })
        assertTrue(budget.invocations.all { it.lane == PresolveLane.SOURCE && it.work > 0L })
        assertEquals(budget.spent(), budget.invocations.sumOf { it.work })
        assertEquals(budget.invocations[0].work - 8L, budget.invocations[1].work)
    }

    @Test
    fun `budget interruption should retain an empty analysis and its charge`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val budget = PresolveBudget(1L)

        Presolver.runSource(
            problem, PresolveConfig.parse("off,+strengthen"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val invocation = budget.invocations.single()
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, invocation.outcome)
        assertEquals(invocation.input.factors, invocation.output.factors)
        assertEquals(budget.spent(), invocation.work)
    }

    @Test
    fun `source refutations should retain their invocation outcome`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)

        val result = Presolver.runSource(
            problem, PresolveConfig.parse("off,+strengthen"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        assertTrue(result.infeasible)
        assertEquals(PresolveInvocationOutcome.REFUTED, budget.invocations.single().outcome)
    }

    @Test
    fun `construction tiers should retain namespace skips and probe cap interruptions`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), emptyList())
            .bake(budget.orSpent(Cancellation.Never))

        RootBaker.bake(problem, BakeConfig(
            probeFailedLiterals = true, probeIntBounds = true, probeIntHoles = true, probeTotalBudget = 0,
        ))

        assertEquals(listOf(
            PresolveInvocationOutcome.ELIGIBILITY_SKIP,
            PresolveInvocationOutcome.INTERRUPTED,
            PresolveInvocationOutcome.INTERRUPTED,
        ), budget.invocations.map { it.outcome })
        assertEquals(0L, budget.probeCalls)
    }

    @Test
    fun `empty external stages should survive the pipeline fixpoint exit`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(0, 0, emptyArray(), emptyList())

        PresolvePipeline.run(
            problem, null, PresolveConfig.parse("off,+binary-columns,+lp-harvest,+difference-system"),
            false, presolveBudget = budget,
        )

        assertEquals(listOf("binary-columns", "lp-harvest", "difference-system"), budget.invocations.map { it.pass })
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.first().outcome)
        assertEquals(PresolveInvocationOutcome.ELIGIBILITY_SKIP, budget.invocations.last().outcome)
        assertTrue(budget.invocations.all { it.input.factors == it.output.factors })
    }

    @Test
    fun `nested construction charges should reconcile without billing the parent twice`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )

        val result = PresolvePipeline.run(
            problem, null, PresolveConfig.parse("off,+binary-columns,+probe-failed-literals"),
            false, presolveBudget = budget,
        )

        val substitution = budget.invocations.single { it.pass == "binary-columns" }
        assertEquals(PresolveInvocationOutcome.CHANGED, substitution.outcome)
        assertEquals(2, substitution.output.booleans - substitution.input.booleans)
        assertEquals(2, substitution.fixedColumns)
        assertTrue(substitution.rebuildWork > 0L)
        assertTrue(budget.invocations.any { it.pass == "probe-failed-literals" && it.rebuildWork > 0L })
        assertTrue(budget.invocations.all { it.rebuildWork <= it.work })
        assertEquals(
            budget.spent(), budget.invocations.sumOf { it.work } + budget.preparationStages.sumOf { it.work },
        )
        assertTrue(result.changed)
    }

    @Test
    fun `unchanged finite schedules should reuse the source factor aggregate`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        ).bake()
        val context = PresolveContext(objectiveIntCoeffs = mapOf(0 to 1L, 1 to 1L), presolveBudget = budget)
        val config = PresolveConfig.parse("off,+dup-columns")
        val token = budget.orSpent(Cancellation.Never)

        repeat(2) { Presolver.run(problem, config, context, token) }

        assertEquals(1L, budget.entrySizeVisits)
        assertTrue(budget.invocations.all { it.outcome == PresolveInvocationOutcome.COMPLETE_MISS })
        assertEquals(2, budget.invocations.size)
    }

    @Test
    fun `source reductions should report factor and occurrence yield from their delta`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            List(2) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7) },
        )

        Presolver.runSource(
            problem, PresolveConfig.parse("off,+subsume"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val changed = budget.invocations.first { it.pass == "subsume" }
        assertEquals(PresolveInvocationOutcome.CHANGED, changed.outcome)
        assertEquals(2, changed.input.factors)
        assertEquals(1, changed.output.factors)
        assertEquals(2L, changed.input.occurrences)
        assertEquals(1L, changed.output.occurrences)
    }

    @Test
    fun `an affine rerun without touched equality shapes should charge only candidate visits`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            List(2) { Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3) },
        ).bake()

        Presolver.run(
            problem, PresolveConfig.parse("off,+affine,+subsume"), PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        val calls = budget.invocations.filter { it.pass == "affine" }
        assertEquals(listOf(
            PresolveInvocationOutcome.COMPLETE_MISS,
            PresolveInvocationOutcome.ELIGIBILITY_SKIP,
        ), calls.map { it.outcome })
        assertEquals(2L, calls.last().work)
        assertEquals(null, calls.last().input.occurrences)
        assertEquals(2L, budget.entrySizeVisits)
    }

    @Test
    fun `effort snapshots should reconcile invocation work with shared construction`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val config = PresolveConfig.parse("off,+binary-columns,+probe-failed-literals")
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 1) },
            listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)),
        )

        PresolvePipeline.run(problem, null, config, false, presolveBudget = budget)
        val effort = config.effortStats(budget, kotlin.time.Duration.ZERO)
        val work = effort.invocations.sumOf { it.work }

        assertEquals(
            budget.spent(), work + effort.preparationStages.sumOf { it.work } + requireNotNull(effort.unattributedWork),
        )
        assertEquals(0L, effort.unattributedWork)
        assertEquals(budget.entrySizeVisits, effort.entrySizeVisits)
        assertEquals(budget.invocations.size, effort.invocations.size)
    }

    @Test
    fun `materializing a certified refutation should retain the phase construction meter`() {
        val budget = PresolveBudget(Long.MAX_VALUE)
        val problem = Problem(1, 0, emptyArray(), emptyList()).bake(budget.orSpent(Cancellation.Never))
        val construction = budget.rootConstructionEntries

        RootBaker.provenInfeasible(problem, BakeConfig.NONE)

        assertTrue(budget.rootConstructionEntries > construction)
    }

    @Test
    fun `a complete miss that spends the final unit should remain complete`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 10)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)),
        )
        val config = PresolveConfig.parse("off,+strengthen")
        val reference = PresolveBudget(Long.MAX_VALUE)
        Presolver.runSource(
            problem, config, PresolveContext(presolveBudget = reference), reference.orSpent(Cancellation.Never),
        )
        val budget = PresolveBudget(reference.spent())

        Presolver.runSource(
            problem, config, PresolveContext(presolveBudget = budget), budget.orSpent(Cancellation.Never),
        )

        assertEquals(0L, budget.remaining())
        assertEquals(PresolveInvocationOutcome.COMPLETE_MISS, budget.invocations.single().outcome)
    }
}
