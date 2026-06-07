package com.eignex.klause.portfolio

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.MinimizeResult.Optimal
import com.eignex.klause.solver.MinimizeResult.WithSample
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.IndomainMax
import com.eignex.klause.solver.backtrack.IndomainMiddle
import com.eignex.klause.solver.backtrack.InputOrder
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.TimeSource

@OptIn(ExperimentalAtomicApi::class)
class PortfolioTest {

    @Test
    fun `portfolio solve on satisfiable problem returns sat`() = runTest {
        val problem = exactlyOneOver(4)
        val workers = List(4) { i ->
            PortfolioWorker.of("bt#$i", BacktrackSolver(problem).session(), BacktrackParams(randomSeed = i.toLong()))
        }
        Portfolio(workers).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "expected Sat, got $r")
        }
    }

    @Test
    fun `portfolio solve on unsat problem returns unsat`() = runTest {
        // x ∧ ¬x → trivially unsat
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val workers = List(2) { i ->
            PortfolioWorker.of("bt#$i", BacktrackSolver(problem).session(), BacktrackParams(randomSeed = 0L))
        }
        Portfolio(workers).use { p ->
            val r = p.solve()
            assertIs<SolveResult.Unsat>(r)
            Unit
        }
    }

    @Test
    fun `portfolio samples fans in from all workers and respects collector cancellation`() = runTest {
        val problem = exactlyOneOver(5)
        val workers = List(4) { i ->
            PortfolioWorker.of(
                "ls#$i",
                LocalSearchSolver(problem).session(),
                LocalSearchParams(maxFlips = Long.MAX_VALUE, randomSeed = i.toLong()),
            )
        }
        Portfolio(workers).use { p ->
            // take(20) cancels the upstream flow after 20 samples — every worker's
            // sequence must terminate promptly when the collector stops.
            val started = TimeSource.Monotonic.markNow()
            val samples = p.samples()
                .take(20)
                .toList()
            val elapsed = started.elapsedNow().inWholeMilliseconds
            assertEquals(20, samples.size)
            assertTrue(elapsed < 30_000, "take(20) should be quick on a small problem; took ${elapsed}ms")
            // Every sample is a valid exactly-one configuration.
            for (s in samples) {
                assertEquals(1, s.bools.count { it }, "exactly-one violated by $s")
            }
        }
    }

    @Test
    fun `portfolio with one worker behaves like the underlying session`() = runTest {
        val problem = exactlyOneOver(3)
        val solo = PortfolioWorker.of(
            "ls",
            LocalSearchSolver(problem).session(),
            LocalSearchParams(maxFlips = 5_000, randomSeed = 0L),
        )
        Portfolio(listOf(solo)).use { p ->
            val samples = p.samples()
                .take(5).toList()
            assertEquals(5, samples.size)
            for (s in samples) assertEquals(1, s.bools.count { it })
        }
    }

    @Test
    fun `portfolio minimize returns the global best across workers`() = runTest {
        // minimize x + 2y subject to x + y >= 3, x ∈ [0..5], y ∈ [0..5]. Optimum = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(
                IntDomain(0, 5),
                IntDomain(0, 5),
            ),
            factors = arrayOf<Factor>(
                Linear(
                    coeffs = intArrayOf(1, 1),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.GE,
                    bound = 3,
                ),
            ),
        )
        val obj = LinearObjective(
            intCoefficients = doubleArrayOf(1.0, 2.0),
        )
        val workers = List(3) { i ->
            PortfolioWorker.of(
                "bt#$i",
                BacktrackSolver(problem).session(),
                BacktrackParams(randomSeed = 0L),
                objective = obj,
            ) { params, supplier ->
                params.copy(objectiveBoundSupplier = supplier)
            }
        }
        Portfolio(workers).use { p ->
            val r = p.minimize()
            val optimal = assertIs<Optimal>(r)
            assertEquals(3.0, optimal.objectiveValue)
        }
    }

    @Test
    fun `budget-capped workers never upgrade the incumbent to a false optimal`() = runTest {
        // minimize x over x ∈ [0..1000] with no constraints: the optimum is 0, but every
        // worker is decision-capped so none can exhaust its space. A pool of BestFound
        // verdicts under BudgetExhausted proves nothing about better solutions — the fold
        // must report BestFound, not Optimal (a portfolio over budget-capped workers
        // previously manufactured an optimality proof from timed-out incumbents).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1000)),
            factors = arrayOf<Factor>(),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0))
        val workers = List(2) { i ->
            PortfolioWorker.of(
                "bt#$i",
                BacktrackSolver(problem).session(),
                // Worker 0 walks down from 1000, worker 1 from the domain middle, so both
                // hold real incumbents when the 50-decision cap lands — and neither is
                // anywhere near reaching (or proving) the optimum at 0. A pool of capped
                // BestFound verdicts is the exact shape that was upgraded falsely.
                BacktrackParams(
                    randomSeed = i.toLong(),
                    maxDecisions = 50,
                    variableHeuristic = InputOrder,
                    valueHeuristic = if (i == 0) IndomainMax else IndomainMiddle,
                ),
                objective = obj,
            ) { params, supplier ->
                params.copy(objectiveBoundSupplier = supplier)
            }
        }
        Portfolio(workers).use { p ->
            // The middle-bisecting worker may legitimately stumble onto the optimum value,
            // but with the max-descending worker budget-capped the pool has not covered the
            // space — the verdict must stay BestFound; claiming Optimal here is a
            // manufactured proof.
            assertIs<MinimizeResult.BestFound>(p.minimize())
            Unit
        }
    }

    @Test
    fun `minimize returns a sample consistent with the reported bound`() = runTest {
        // #81 regression: the reported objectiveValue and the returned sample must agree. The fix
        // swaps (bound, sample) in one CAS so they can never desync under a worker race; here we
        // race several workers on a problem with multiple improving steps and verify the returned
        // sample actually realises the reported bound (x + 2y, optimum 3 at x=3,y=0).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0))
        val workers = List(6) { i ->
            PortfolioWorker.of(
                "bt#$i",
                BacktrackSolver(problem).session(),
                BacktrackParams(randomSeed = i.toLong()),
                objective = obj,
            ) { params, supplier ->
                params.copy(objectiveBoundSupplier = supplier)
            }
        }
        Portfolio(workers).use { p ->
            val r = p.minimize()
            val ws = assertIs<WithSample>(r)
            val realised = ws.sample.ints[0] * 1.0 + ws.sample.ints[1] * 2.0
            assertEquals(
                ws.objectiveValue,
                realised,
                "reported bound ${ws.objectiveValue} must match the sample's objective $realised",
            )
        }
    }

    @Test
    fun `builder minimize wires a per-worker objective across a mixed pool`() = runTest {
        // minimize x + 2y subject to x + y >= 3 — same as above, but built through
        // PortfolioBuilder with BOTH a per-worker LS objective and a backtrack (linear) objective
        // (#63). LS workers descend lsObjective, backtrack workers bound linearObjective; the
        // shared scalar bound stays comparable, so the mixed pool still proves the optimum (3).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0))
        // The LS workers are unbudgeted and shared-bound demotes backtrack to BestFound, so
        // nothing self-cancels the pool: stop once any worker reports an incumbent at the known
        // optimum (cancelled workers still yield their best — the anytime invariant). The
        // fallback only bounds a regression.
        val sawOptimum = AtomicBoolean(false)
        PortfolioBuilder.build(
            problem,
            PortfolioSpec(localSearchWorkers = 2, backtrackWorkers = 2, seed = 1L),
            lsObjective = obj,
            linearObjective = obj,
            onEvent = { _, e ->
                if (e is SearchEvent.Incumbent && e.objective <= 3.0) sawOptimum.store(true)
            },
        ).use { p ->
            val fallback = TimeSource.Monotonic.markNow() + Duration.parse("30s")
            val r = p.minimize(cancellation = { sawOptimum.load() || fallback.hasPassedNow() })
            assertEquals(3.0, assertIs<WithSample>(r).objectiveValue)
            // Worker stats fold into the verdict: a mixed pool degrades the backend tag.
            assertEquals("mixed", r.stats.backend)
            assertTrue(r.stats.wallMs >= 0L)
        }
    }

    @Test
    fun `builder threads labeled onEvent through to workers`() = runTest {
        // Same mixed-pool minimize as above, with the SearchEvent seam attached: each worker's
        // incumbent improvements arrive tagged with its label. Workers run concurrently, so the
        // collection is locked.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = doubleArrayOf(1.0, 2.0))
        val events = AtomicReference<List<Pair<String, SearchEvent>>>(emptyList())
        PortfolioBuilder.build(
            problem,
            PortfolioSpec(localSearchWorkers = 1, backtrackWorkers = 1, seed = 1L),
            lsObjective = obj,
            linearObjective = obj,
            onEvent = { worker, e ->
                while (true) {
                    val cur = events.load()
                    if (events.compareAndSet(cur, cur + (worker to e))) break
                }
            },
        ).use { p ->
            // Stop once the first labeled incumbent is collected — all the assertions need.
            val fallback = TimeSource.Monotonic.markNow() + Duration.parse("30s")
            p.minimize(cancellation = {
                events.load().any { it.second is SearchEvent.Incumbent } || fallback.hasPassedNow()
            })
        }
        val incumbents = events.load().filter { it.second is SearchEvent.Incumbent }
        assertTrue(incumbents.isNotEmpty(), "expected labeled incumbent events, got ${events.load()}")
        val labels = incumbents.map { it.first }.toSet()
        assertTrue(labels.all { it.startsWith("ls/") || it.startsWith("backtrack#") }, "labels: $labels")
    }

    @Test
    fun `exhaustive strategy runs every worker to budget`() = runTest {
        val problem = exactlyOneOver(3)
        val workers = List(2) { i ->
            PortfolioWorker.of("bt#$i", BacktrackSolver(problem).session(), BacktrackParams(randomSeed = 0L))
        }
        Portfolio(workers, strategy = PortfolioStrategy.Exhaustive).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "expected Sat from exhaustive run; got $r")
        }
    }

    @Test
    fun `builder makes a mixed LS plus backtrack portfolio that solves`() = runTest {
        val problem = exactlyOneOver(4)
        PortfolioBuilder.build(
            problem,
            PortfolioSpec(localSearchWorkers = 2, backtrackWorkers = 2, seed = 1L),
        ).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "mixed LS+backtrack portfolio should solve exactly-one; got $r")
        }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })
        return Problem(n, 0, emptyArray(), listOf(factor))
    }
}
