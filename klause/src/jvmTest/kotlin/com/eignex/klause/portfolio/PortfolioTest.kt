package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.lp.LpConfig
import com.eignex.klause.backtrack.lp.LpEmphasis
import com.eignex.klause.backtrack.lp.LpTechnique
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMiddle
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.MinimizeResult.Optimal
import com.eignex.klause.solver.result.MinimizeResult.WithSample
import com.eignex.klause.solver.result.SearchEvent
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
    fun `cop backtrack palette spreads lp-intensity arms and csp has none`() {
        val cop = BacktrackWorkerConfig.ranked(Kind.COP).map { it.label }
        // The spectrum: AGGRESSIVE, DEFAULT and CONSERVATIVE LP arms plus a best-bound
        // dive arm, hedged by the OFF (no-LP) arms.
        assertTrue("lp-aggressive" in cop && "lp-default" in cop, "COP palette must carry the LP arms, got $cop")
        assertTrue("lp-conservative" in cop && "lp-lbtree" in cop, "COP palette must spread LP emphasis, got $cop")
        val aggressive = BacktrackWorkerConfig.ranked(Kind.COP).first { it.label == "lp-aggressive" }
        assertEquals(LpEmphasis.AGGRESSIVE, aggressive.build(1L, null).lpConfig?.emphasis)
        val default = BacktrackWorkerConfig.ranked(Kind.COP).first { it.label == "lp-default" }
        assertEquals(LpEmphasis.DEFAULT, default.build(1L, null).lpConfig?.emphasis)
        // The best-bound dive arm carries the lb_tree_search flag on its base plan.
        val lbtree = BacktrackWorkerConfig.ranked(Kind.COP).first { it.label == "lp-lbtree" }
        assertTrue(lbtree.build(1L, null).lpPlan.lbTreeSearch, "the lp-lbtree arm must enable lb_tree_search")
        // The #117 guard stays at slot 0.
        assertEquals("satOptimized", cop.first())

        val csp = BacktrackWorkerConfig.ranked(Kind.CSP).map { it.label }
        assertTrue(csp.none { it.startsWith("lp-") }, "the LP machinery lives on the minimisation path; CSP skips it")
    }

    @Test
    fun `an injected btPool overrides the curated backtrack arms`() {
        val templates = listOf(
            BacktrackParams(randomSeed = 0L),
            BacktrackParams(randomSeed = 0L, lubyRestartBase = 256L),
        )
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 3,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            btPool = templates,
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals(3, labels.size, "the pool wraps to fill the requested arm count")
        assertTrue(
            labels.all { it.startsWith("bt-pool#") },
            "injected btPool must replace the curated arms, got $labels",
        )
    }

    @Test
    fun `the annotation arm takes the last backtrack slot and keeps the guard`() {
        val annotation = BacktrackParams(randomSeed = 0L, lubyRestartBase = 512L)
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 3,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            annotationArm = annotation,
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals("satOptimized", labels.first(), "the #117 guard keeps slot 0")
        assertEquals("annotation", labels.last(), "the annotation arm takes the last slot")
    }

    @Test
    fun `a lone backtrack slot keeps the guard over the annotation arm`() {
        val scenario = PortfolioScenario(
            cores = 1,
            arms = 1,
            kind = Kind.CSP,
            engine = EngineMix.BACKTRACK,
            annotationArm = BacktrackParams(randomSeed = 0L),
        )
        val labels = PortfolioComposition.compose(scenario).map { it.label }
        assertEquals(listOf("satOptimized"), labels, "one slot stays the guard, not the annotation arm")
    }

    @Test
    fun `the latent-axis heuristic arms are ranked and build`() {
        val cop = BacktrackWorkerConfig.labels(Kind.COP)
        for (label in listOf("domwdeg", "first-fail", "activity")) {
            assertTrue(label in cop, "$label must be a ranked COP arm")
            assertEquals(label, BacktrackWorkerConfig.byLabel(label).label, "$label must build by label")
        }
    }

    @Test
    fun `the selector-switch arm wires a fresh restart-switching portfolio per worker`() {
        val arm = BacktrackWorkerConfig.byLabel("selector-switch")
        val a = arm.build(1L, null)
        val b = arm.build(2L, null)
        assertEquals(100L, a.lubyRestartBase, "Luby restarts drive the bandit's arm switching")
        assertTrue(a.variableSelector !== b.variableSelector, "each worker gets its own switching state")
    }

    @Test
    fun `lp ceiling caps and toggles the portfolio arms`() {
        fun lpConfigs(ceiling: LpConfig): List<LpConfig?> =
            BacktrackWorkerConfig.diverse(Kind.COP, count = 6, lpCeiling = ceiling).map { it.build(1L, null).lpConfig }

        // --lp off disables LP across the pool.
        val off = lpConfigs(LpConfig(LpEmphasis.OFF))
        assertTrue(off.all { (it?.emphasis ?: LpEmphasis.OFF) == LpEmphasis.OFF }, "OFF ceiling leaves no arm on LP")
        // --lp default caps the AGGRESSIVE arm down to DEFAULT.
        val capped = lpConfigs(LpConfig(LpEmphasis.DEFAULT)).mapNotNull { it?.emphasis }
        assertTrue(capped.all { it.ordinal <= LpEmphasis.DEFAULT.ordinal }, "no arm may exceed the ceiling")
        // --lp aggressive,-cuts forces cuts off on every LP arm while keeping the spread.
        val noCuts = lpConfigs(LpConfig.parse("aggressive,-cuts")).filterNotNull()
        assertTrue(noCuts.all { !it.resolved(LpTechnique.CUTS) }, "the -cuts ceiling override must reach every LP arm")
    }

    @Test
    fun `portfolio solve on satisfiable problem returns sat`() {
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
    fun `portfolio solve on unsat problem returns unsat`() {
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
    fun `portfolio samples fans in from all workers and respects collector cancellation`() {
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
    fun `portfolio with one worker behaves like the underlying session`() {
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
    fun `portfolio minimize returns the global best across workers`() {
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
            intCoefficients = longArrayOf(1L, 2L),
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
    fun `budget-capped workers never upgrade the incumbent to a false optimal`() {
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
        val obj = LinearObjective(intCoefficients = longArrayOf(1L))
        val workers = List(2) { i ->
            PortfolioWorker.of(
                "bt#$i",
                BacktrackSolver(problem).session(),
                // Worker 0 walks down from 1000, worker 1 from the domain middle, so both hold real
                // incumbents when the tiny decision cap lands — but neither can bisect all the way
                // to (and prove) the optimum at 0 in so few decisions (which, with real-thread
                // parallelism, would be a *legitimate* Optimal). A pool of capped BestFound verdicts
                // is the exact shape that was upgraded falsely.
                BacktrackParams(
                    randomSeed = i.toLong(),
                    maxDecisions = 4,
                    variableSelector = InputOrder,
                    valueSelector = if (i == 0) IndomainMax else IndomainMiddle,
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
    fun `minimize returns a sample consistent with the reported bound`() {
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
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
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
    fun `builder minimize wires a per-worker objective across a mixed pool`() {
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
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        // The LS workers are unbudgeted and shared-bound demotes backtrack to BestFound, so
        // nothing self-cancels the pool: stop once any worker reports an incumbent at the known
        // optimum (cancelled workers still yield their best — the anytime invariant). The
        // fallback only bounds a regression.
        val sawOptimum = AtomicBoolean(false)
        Portfolio(
            PortfolioBuilder.build(
                problem,
                PortfolioScenario.parallel(cores = 4, kind = Kind.COP, engine = EngineMix.MIXED, seed = 1L),
                objective = obj,
                onEvent = { _, e ->
                    if (e is SearchEvent.Incumbent && e.objective <= 3.0) sawOptimum.store(true)
                },
            ),
        ).use { p ->
            val fallback = TimeSource.Monotonic.markNow() + Duration.parse("30s")
            val r = p.minimize(cancellation = { sawOptimum.load() || fallback.hasPassedNow() })
            assertEquals(3.0, assertIs<WithSample>(r).objectiveValue)
            // Worker stats fold into the verdict: a mixed pool degrades the backend tag.
            assertEquals("mixed", r.stats.run.backend)
            assertTrue(r.stats.run.wallMs >= 0L)
        }
    }

    @Test
    fun `builder threads labeled onEvent through to workers`() {
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
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val events = AtomicReference<List<Pair<String, SearchEvent>>>(emptyList())
        Portfolio(
            PortfolioBuilder.build(
                problem,
                PortfolioScenario.parallel(cores = 2, kind = Kind.COP, engine = EngineMix.MIXED, seed = 1L),
                objective = obj,
                onEvent = { worker, e ->
                    while (true) {
                        val cur = events.load()
                        if (events.compareAndSet(cur, cur + (worker to e))) break
                    }
                },
            ),
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
    fun `exhaustive strategy runs every worker to budget`() {
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
    fun `builder makes a mixed LS plus backtrack portfolio that solves`() {
        val problem = exactlyOneOver(4)
        Portfolio(
            PortfolioBuilder.build(
                problem,
                PortfolioScenario.parallel(cores = 4, kind = Kind.CSP, engine = EngineMix.MIXED, seed = 1L),
            ),
        ).use { p ->
            val r = p.solve()
            assertTrue(r is SolveResult.Sat, "mixed LS+backtrack portfolio should solve exactly-one; got $r")
        }
    }

    @Test
    fun `builder backtrack pool includes the sat-optimized worker and solves`() {
        // A single backtrack worker (i % 3 == 0) must be the SAT-optimized config; confirm the
        // built pool both surfaces that worker and solves a conflict-heavy UNSAT instance.
        val problem = pigeonhole(pigeons = 4, holes = 3)
        Portfolio(
            PortfolioBuilder.buildExplicit(problem, emptyList(), backtrackWorkers = 1, kind = Kind.CSP),
        ).use { p ->
            assertTrue(p.workers.any { it.label == "backtrack#0" }, "expected a backtrack worker")
            assertIs<SolveResult.Unsat>(p.solve())
        }
        // With three backtrack workers the pool cycles through all three complete configs.
        Portfolio(
            PortfolioBuilder.buildExplicit(exactlyOneOver(5), emptyList(), backtrackWorkers = 3, kind = Kind.CSP),
        ).use { p ->
            assertEquals(3, p.workers.count { it.label.startsWith("backtrack#") })
            assertIs<SolveResult.Sat>(p.solve())
        }
    }

    private fun exactlyOneOver(n: Int): Problem {
        val factor = Cardinality.exactlyOne(IntArray(n) { Lit.make(it, true) })
        return Problem(n, 0, emptyArray(), listOf(factor))
    }

    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    factors.add(Clause(intArrayOf(Lit.make(v(p1, h), false), Lit.make(v(p2, h), false))))
                }
            }
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors.toTypedArray())
    }
}
