package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolverTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    @Test
    fun `parse handles aliases and comma-lists`() {
        val ctx = PresolveContext.EMPTY
        val autoProblem = listOf(
            PresolvePass.STRENGTHEN_COEFFICIENTS,
            PresolvePass.REDUCE_DIOPHANTINE,
            PresolvePass.DERIVE_XOR_UNITS,
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS,
            PresolvePass.REMOVE_REDUNDANT,
            PresolvePass.REDUCE_STRUCTURAL,
            PresolvePass.MERGE_DUPLICATE_COLUMNS,
            PresolvePass.BREAK_SYMMETRIES,
            PresolvePass.DUAL_FIX,
        )
        assertEquals(autoProblem, PresolveConfig.parse(null).problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("default").problemPasses(ctx))
        assertEquals(autoProblem, PresolveConfig.parse("auto").problemPasses(ctx))
        assertEquals(emptyList(), PresolveConfig.parse("none").problemPasses(ctx))
        assertEquals(
            listOf(PresolvePass.STRENGTHEN_COEFFICIENTS, PresolvePass.ELIMINATE_AFFINE_SINGLETONS),
            PresolveConfig.parse("affine, strengthen").problemPasses(ctx),
        )
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("bogus") }
    }

    @Test
    fun `parse emphasis plus deltas toggles a single pass`() {
        val ctx = PresolveContext.EMPTY
        val noSymmetry = PresolveConfig.parse("default,-symmetry")
        assertEquals(PresolveEmphasis.DEFAULT, noSymmetry.emphasis)
        assertEquals(false, noSymmetry.resolved(PresolvePass.BREAK_SYMMETRIES, ctx))
        assertTrue(noSymmetry.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, ctx))
        val justSymmetry = PresolveConfig.parse("off,+symmetry")
        assertEquals(PresolveEmphasis.OFF, justSymmetry.emphasis)
        assertEquals(true, justSymmetry.resolved(PresolvePass.BREAK_SYMMETRIES, ctx))
        assertEquals(false, justSymmetry.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, ctx))
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("default,symmetry") }
        assertFailsWith<IllegalStateException> { PresolveConfig.parse("default,+bogus") }
    }

    @Test
    fun `lp-harvest is an aggressive-tier pass off at the default level`() {
        // EXHAUSTIVE-tier, so the aggressive level turns it on and the default level leaves it off; an
        // explicit delta toggles it either way. (Its own size self-limit lives in the harvest, not here.)
        val ctx = PresolveContext.EMPTY
        assertTrue(PresolveConfig.parse("aggressive").resolved(PresolvePass.LP_HARVEST, ctx))
        assertEquals(false, PresolveConfig.parse("default").resolved(PresolvePass.LP_HARVEST, ctx))
        assertTrue(PresolveConfig.parse("default,+lp-harvest").resolved(PresolvePass.LP_HARVEST, ctx))
        assertEquals(false, PresolveConfig.parse("aggressive,-lp-harvest").resolved(PresolvePass.LP_HARVEST, ctx))
    }

    @Test
    fun `auto resolution is intent-aware and SAC is opt-in`() {
        val auto = PresolveConfig.AUTO
        // Symmetry breaking is solution-set-altering: auto-on for solve, auto-off when the query
        // needs the full solution set (enumeration / counting / sampling).
        assertTrue(PresolvePass.BREAK_SYMMETRIES in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.BREAK_SYMMETRIES !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
        // Construction-time SAC probes are expensive → auto-off; explicit `all` turns them on.
        assertEquals(false, auto.resolved(PresolvePass.PROBE_INT_BOUNDS, PresolveContext.EMPTY))
        assertEquals(true, PresolveConfig.parse("all").resolved(PresolvePass.PROBE_INT_HOLES, PresolveContext.EMPTY))
        // forLocalSearch forces every solution-set-altering pass off, even under a non-sensitive
        // query — here the default emphasis plus an explicit value-precedence override.
        val ls = PresolveConfig(PresolveConfig.AUTO.emphasis, mapOf(PresolvePass.VALUE_PRECEDENCE to true))
            .forLocalSearch()
        val lsPasses = ls.problemPasses(PresolveContext.EMPTY)
        assertTrue(PresolvePass.BREAK_SYMMETRIES !in lsPasses)
        assertTrue(PresolvePass.VALUE_PRECEDENCE !in lsPasses)
        // …but the cheap solution-preserving reductions stay on.
        assertTrue(PresolvePass.STRENGTHEN_COEFFICIENTS in lsPasses)
    }

    @Test
    fun `auto symmetry breaking defers to a model that already breaks symmetry`() {
        val auto = PresolveConfig.AUTO
        val breaks = PresolveContext(modelBreaksSymmetry = true)
        // Default case: the model's own symmetry_breaking_constraint turns klause's pass off…
        assertEquals(false, auto.resolved(PresolvePass.BREAK_SYMMETRIES, breaks))
        // …without disturbing the other auto passes.
        assertTrue(auto.resolved(PresolvePass.STRENGTHEN_COEFFICIENTS, breaks))
        // Explicit overrides win regardless of the model: +symmetry forces it on, -symmetry off.
        assertEquals(true, PresolveConfig.parse("default,+symmetry").resolved(PresolvePass.BREAK_SYMMETRIES, breaks))
        assertEquals(false, PresolveConfig.parse("default,-symmetry").resolved(PresolvePass.BREAK_SYMMETRIES, breaks))
    }

    @Test
    fun `every pass self-registers and is dispatchable`() {
        // The enum is the registry: ids must be unique and round-trip through fromId, and every
        // problem-stage pass must apply cleanly (no unhandled entry) — a malformed addition fails loudly.
        val ids = PresolvePass.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "pass ids must be unique")
        for (p in PresolvePass.entries) {
            assertSame(p, PresolvePass.fromId(p.id), "id `${p.id}` must round-trip through fromId")
        }
        // Every problem-stage pass applies cleanly to a trivial problem — no unhandled entry.
        val trivial = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 2)),
            listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)),
        )
        for (p in PresolvePass.entries.filter { it.stage == PresolvePass.Stage.PROBLEM }) {
            val applied = trivial.withPassDelta(p.apply(trivial, PresolveContext.EMPTY), BakeConfig.NONE)
            assertEquals(trivial.numIntVars, applied.numIntVars, "${p.id} returned a malformed problem")
        }
    }

    @Test
    fun `emphasis levels select cost tiers`() {
        val ctx = PresolveContext.EMPTY
        assertEquals(emptyList(), PresolveConfig.parse("off").problemPasses(ctx))
        // conservative → FAST tier only (strengthen + xor-units + affine + subsume + structural +
        // dup-columns), no symmetry.
        assertEquals(
            listOf(
                PresolvePass.STRENGTHEN_COEFFICIENTS,
                PresolvePass.REDUCE_DIOPHANTINE,
                PresolvePass.DERIVE_XOR_UNITS,
                PresolvePass.ELIMINATE_AFFINE_SINGLETONS,
                PresolvePass.REMOVE_REDUNDANT,
                PresolvePass.REDUCE_STRUCTURAL,
                PresolvePass.MERGE_DUPLICATE_COLUMNS,
            ),
            PresolveConfig.parse("conservative").problemPasses(ctx),
        )
        assertEquals(1, PresolveConfig.parse("conservative").emphasis.maxRounds)
        // default → adds symmetry (MEDIUM) and iterates; SAC probes (EXHAUSTIVE) stay off.
        assertTrue(PresolvePass.BREAK_SYMMETRIES in PresolveConfig.parse("default").problemPasses(ctx))
        assertEquals(false, PresolveConfig.parse("default").resolved(PresolvePass.PROBE_INT_HOLES, ctx))
        assertTrue(PresolveConfig.parse("default").emphasis.maxRounds > 1)
        // aggressive → also enables the EXHAUSTIVE SAC probes (the compilers read these via resolved).
        assertEquals(true, PresolveConfig.parse("aggressive").resolved(PresolvePass.PROBE_INT_HOLES, ctx))
        assertEquals(true, PresolveConfig.parse("aggressive").resolved(PresolvePass.PROBE_FAILED_LITERALS, ctx))
        // value precedence stays opt-in even at aggressive (it interacts with variable symmetry).
        assertEquals(false, PresolveConfig.parse("aggressive").resolved(PresolvePass.VALUE_PRECEDENCE, ctx))
        assertTrue(PresolvePass.VALUE_PRECEDENCE in PresolveConfig.parse("value-precede").problemPasses(ctx))
    }

    @Test
    fun `emphasis surfaces a probe budget and aggressive gets a larger one`() {
        // Each level exposes a finite SAC probe budget (no level leaves it unbounded), and the
        // aggressive level — the only one that auto-runs the EXHAUSTIVE probes — gets a larger one.
        val capped = PresolveConfig.parse("default")
        val aggressive = PresolveConfig.parse("aggressive")
        assertTrue(capped.probeTotalBudget() < Int.MAX_VALUE)
        assertTrue(capped.probeBudgetPerVar() < Int.MAX_VALUE)
        assertTrue(aggressive.probeTotalBudget() > capped.probeTotalBudget())
        assertTrue(aggressive.probeBudgetPerVar() > capped.probeBudgetPerVar())
        // A non-aggressive level that turns the probe on via an override inherits the capped budget,
        // so the EXHAUSTIVE work can't dominate.
        val overridden = PresolveConfig.parse("default,+probe-int-holes")
        assertEquals(capped.probeTotalBudget(), overridden.probeTotalBudget())
        val custom = PresolveConfig(
            PresolveEmphasis.AGGRESSIVE,
            probeTotalBudgetOverride = 7,
            probeBudgetPerVarOverride = 3,
        )
        assertEquals(7, custom.probeTotalBudget())
        assertEquals(3, custom.probeBudgetPerVar())
        assertEquals(7, custom.forLocalSearch().probeTotalBudget())
    }

    @Test
    fun `the round engine iterates to a fixpoint`() {
        // affine x=2y+1 substituted into 2x+4y<=10 leaves a row strengthen reduces — which only
        // happens on a second round (strengthen runs before affine in the first). Re-presolving the
        // result must then change nothing: the single run already reached the fixpoint.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.LE, 10),
            ),
        )
        val once = Presolver.run(problem, PresolveConfig.AUTO).problem
        assertTrue(once !== problem, "expected the engine to transform the problem")
        assertSame(once, Presolver.run(once, PresolveConfig.AUTO).problem, "re-presolving a fixpoint must be a no-op")
    }

    @Test
    fun `the full pipeline turns an indivisible equality into a detected infeasibility`() {
        // 2x + 4y = 5 is parity-infeasible; strengthen replaces it with a contradiction the later
        // rounds (affine, redundancy) must carry through to a clean Unsat without looping or crashing.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 9)),
            listOf(Linear(intArrayOf(2, 4), intArrayOf(0, 1), LinearOp.EQ, 5)),
        )
        val pre = Presolver.run(problem, PresolveConfig.AUTO)
        assertTrue(pre.problem.propagate(Assumptions.None) is PropagationResult.Unsat, "infeasibility must be detected")
        assertSame(
            pre.problem,
            Presolver.run(pre.problem, PresolveConfig.AUTO).problem,
            "the infeasible residue is itself a fixpoint",
        )
    }

    @Test
    fun `context extracts nonzero objective coefficients`() {
        val obj = LinearObjective(
            boolWeights = longArrayOf(0, 3, 0),
            intCoefficients = longArrayOf(5, 0, 2),
        )
        val ctx = PresolveContext.of(obj)
        assertEquals(setOf(0, 2), ctx.objectiveIntVars)
        assertEquals(setOf(1), ctx.objectiveBoolVars)
        assertTrue(PresolveContext.of(null).objectiveIntVars.isEmpty())
    }

    @Test
    fun `empty config is the identity`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 3)),
            listOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 4)),
        )
        val pre = Presolver.run(problem, PresolveConfig.NONE)
        assertSame(problem, pre.problem)
        val s = Sample(BooleanArray(0), longArrayOf(2))
        assertSame(s, pre.reconstruct(s))
    }

    @Test
    fun `default pipeline composes and reconstructs to a feasible original solution`() {
        // var 0: affine singleton (x = 2y+1 via x - 2y = 1), only here.
        // vars 1,2: GCD-reducible sum 2y+2z<=4 with y,z interchangeable (equal coeff) -> symmetry.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 2), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.DEFAULT)
        assertTrue(pre.problem !== problem, "expected the pipeline to transform the problem")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(2 * full.ints[1] + 1, full.ints[0], "affine var not reconstructed: x should be 2y+1")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `xor-units pass emits implied unit clauses and is idempotent`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), targetParity = 0), // x0 = x1
                Xor(intArrayOf(Lit.make(1, true)), targetParity = 1), // x1 = true
            ),
        )
        val config = PresolveConfig.parse("xor-units")
        val pre = Presolver.run(problem, config)
        val units = pre.problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }
        assertEquals(setOf(Lit.make(0, true), Lit.make(1, true)), units.map { it.literals[0] }.toSet())
        // Re-running on the transformed problem should be a no-op (no duplicate unit clauses).
        assertSame(pre.problem, Presolver.run(pre.problem, config).problem)
    }

    @Test
    fun `xor-units pass turns a contradictory xor core into contradictory units`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 1),
                Xor(intArrayOf(Lit.make(0, true)), targetParity = 0),
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.parse("xor-units"))
        val units = pre.problem.factors.filterIsInstance<Clause>().filter { it.literals.size == 1 }
        assertTrue(units.any { it.literals[0] == Lit.make(0, true) })
        assertTrue(units.any { it.literals[0] == Lit.make(0, false) })
    }

    @Test
    fun `an already-fired cancellation makes presolve a no-op`() {
        // Same problem the default pipeline transforms above; with the deadline already past, the round
        // engine must not run a single pass and must return the input verbatim. The transforms are
        // individually sound, so returning early is safe — this guards that the exit check is honoured.
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3), IntDomain(0, 3)),
            listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(2, 2), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.DEFAULT, cancellation = { true })
        assertSame(problem, pre.problem, "a fired cancellation must skip every pass and return the input")
        val s = Sample(BooleanArray(0), longArrayOf(3, 1, 0))
        assertSame(s, pre.reconstruct(s), "no pass ran, so reconstruct is the identity")
    }

    @Test
    fun `value-precede pass posts a precedence chain and stays satisfiable`() {
        // AllDifferent over {0,1,2}: value-anonymous, so the opt-in value-precedence pass posts a
        // value_precede_chain (native ValuePrecede factors, #432) collapsing the 6 permutations to the
        // single canonical 0,1,2. Same variable space — reconstruct is the identity.
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            listOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        val pre = Presolver.run(problem, PresolveConfig.parse("value-precede"))
        assertTrue(pre.problem !== problem, "value precedence should add precedence factors")
        assertEquals(problem.numIntVars, pre.problem.numIntVars, "no auxiliary variables are added")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(listOf(0L, 1L, 2L), full.ints.toList(), "the single canonical permutation")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `dropping a vacuous global frees an implied variable for elimination`() {
        // x0 = x1 + 1 (a unit-pivot definition) and x0 also sits in AllDifferent(x0, x2, x4). With
        // dom(x0)=[1,2], dom(x2)=[5,6], dom(x4)=[9,10] pairwise disjoint that all-different is vacuous,
        // so subsumption drops it (#553); x0 is then contained in just its defining equality, and the
        // affine pass projects it out — implied-free elimination the global previously blocked. x1 sits
        // in a *real* AllDifferent(x1, x3, x5) over [0,2], which stays. Three-variable all-differents
        // are used so the structural-reduction pass (two-var → binary disequality) leaves them alone.
        val problem = Problem(
            0,
            6,
            arrayOf(
                IntDomain(1, 2),
                IntDomain(0, 1),
                IntDomain(5, 6),
                IntDomain(0, 2),
                IntDomain(9, 10),
                IntDomain(0, 2),
            ),
            listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                AllDifferent(intArrayOf(0, 2, 4), domainMin = 0, domainSize = 11), // vacuous (disjoint)
                AllDifferent(intArrayOf(1, 3, 5), domainMin = 0, domainSize = 3), // real (overlapping)
            ),
        )
        val pre = Presolver.run(problem, PresolveConfig.DEFAULT)
        assertEquals(1, pre.problem.factors.count { it is AllDifferent }, "the vacuous all-different is dropped")
        assertTrue(pre.problem.factors.none { 0 in it.intVars }, "x0 is eliminated — present in no factor")
        val result = BacktrackSolver(pre.problem).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat, "presolved problem should be SAT, got $result")
        val full = pre.reconstruct(result.assignment)
        assertEquals(full.ints[1] + 1, full.ints[0], "x0 reconstructed as x1 + 1")
        assertTrue(isFeasible(problem, full), "reconstructed sample infeasible in the original problem")
    }

    @Test
    fun `affine elimination is gated off for solution-set-sensitive queries`() {
        // Affine elimination leaves the eliminated variable unconstrained in the reduced problem
        // (its value is rebuilt from its partner on the way back). That is fine for solve/optimize, but
        // a complete enumerator would branch over the freed variable's whole domain and yield each real
        // solution once per spurious value (#507). So it must NOT run when the caller needs the exact
        // solution set / count.
        val auto = PresolveConfig.AUTO
        assertTrue(PresolvePass.ELIMINATE_AFFINE_SINGLETONS in auto.problemPasses(PresolveContext.EMPTY))
        assertTrue(
            PresolvePass.ELIMINATE_AFFINE_SINGLETONS !in
                auto.problemPasses(PresolveContext(solutionSetSensitive = true)),
        )
        // ...but local search (which never enumerates) keeps it on — it only shrinks the problem there.
        val lsPasses = auto.forLocalSearch().problemPasses(PresolveContext.EMPTY)
        assertTrue(PresolvePass.ELIMINATE_AFFINE_SINGLETONS in lsPasses)
    }

    @Test
    fun `presolve preserves the model count for a channeled circuit under enumeration`() {
        // Mirrors `emitCircuit`'s 1-based -> 0-based channeling: succ values in 1..4 are linked to
        // 0-based aux vars via `src - aux = 1`, and the Circuit factor reasons over the aux vars. The
        // affine pass eliminates the aux vars by folding the channel away; if it runs under `-a` the
        // freed aux vars get enumerated independently, inflating circuit(4)'s 6 solutions (#507).
        val n = 4
        val domains = Array(2 * n) { v -> if (v < n) IntDomain(1, n.toLong()) else IntDomain(0, (n - 1).toLong()) }
        val factors = ArrayList<Factor>()
        for (i in 0 until n) {
            factors.add(Linear(intArrayOf(1, -1), intArrayOf(i, n + i), LinearOp.EQ, 1))
        }
        factors.add(Circuit(succ = IntArray(n) { n + it }))
        val problem = Problem(0, 2 * n, domains, factors)

        fun count(config: PresolveConfig, sensitive: Boolean): Int {
            val pre = Presolver.run(problem, config, PresolveContext(solutionSetSensitive = sensitive))
            return BacktrackSolver(pre.problem).enumerate(BacktrackParams(randomSeed = 0L)).count()
        }

        val unpresolved = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 0L)).count()
        assertEquals(6, unpresolved, "circuit(4) has exactly 6 Hamiltonian cycles")
        // Sensitive query (enumeration / counting): the affine gate keeps the count exact.
        assertEquals(6, count(PresolveConfig.AUTO, sensitive = true), "presolve must not inflate the count under -a")
        // Non-sensitive solve may eliminate aux vars (count is allowed to change there) — but every
        // surviving solution still projects to a valid circuit, so it stays satisfiable.
        assertTrue(count(PresolveConfig.AUTO, sensitive = false) >= 6, "solve presolve stays feasible")
    }

    @Test
    fun `affine pass protects objective variables`() {
        // x (0) is an affine singleton, but it is the objective variable -> must not be eliminated.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 9), IntDomain(0, 3)),
            listOf(Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        val ctx = PresolveContext.of(LinearObjective(intCoefficients = longArrayOf(1, 0)))
        val pre = Presolver.run(problem, PresolveConfig.parse("affine"), ctx)
        assertSame(problem, pre.problem)
    }
}
