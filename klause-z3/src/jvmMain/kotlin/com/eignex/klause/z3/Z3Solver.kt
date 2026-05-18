package com.eignex.klause.z3

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.TerminationReason
import com.eignex.klause.solver.UnsatCore
import com.microsoft.z3.ArithExpr
import com.microsoft.z3.BoolExpr
import com.microsoft.z3.Context
import com.microsoft.z3.IntNum
import com.microsoft.z3.Model
import com.microsoft.z3.RealSort
import com.microsoft.z3.Solver as Z3LibSolver
import com.microsoft.z3.Status

/**
 * [Solver] backed by Z3 via direct SMT translation (no bit-blast). Each factor in the
 * [Problem] is converted to a native Z3 expression in [Z3Translator]; Z3 reasons over
 * integers natively, which catches bit-blaster bugs the LogicNG path inherits.
 *
 *  - [solve] — single SAT call; returns [SolveResult.Sat], [SolveResult.Unsat], or
 *    [SolveResult.Unknown] (timeout / Z3 returned `Status.UNKNOWN`).
 *  - [sample] — *with replacement*. Fresh Z3 [Context] per draw with a perturbed seed.
 *  - [enumerate] — *without replacement*. One context, blocking clause per yielded model.
 *    `params.minHammingDistance` / `params.recentWindow` apply on top as a post-filter.
 */
class Z3Solver(override val problem: Problem) : Solver<Z3Params>, Optimizer<Z3Params> {

    /**
     * Linear-objective minimisation via Z3's native [com.microsoft.z3.Optimize] solver.
     * Translates a [LinearObjective] to `Σ wᵢ · bᵢ + Σ cᵢ · iᵢ + constant` over Z3 reals
     * (bool indicators lifted via `mkITE`); other [Objective] subtypes are not supported
     * and throw at runtime.
     */
    override fun minimize(objective: Objective, params: Z3Params): MinimizeResult {
        require(objective is LinearObjective) {
            "Z3 backend only supports LinearObjective; got ${objective::class.simpleName}"
        }
        val ctx = newContext()
        try {
            val t = Z3Translator.translate(problem, ctx)
            val opt = ctx.mkOptimize()
            params.timeoutMillis?.let { ms ->
                val p = ctx.mkParams()
                p.add("timeout", ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                opt.setParameters(p)
            }
            for (c in t.allConstraints()) opt.Add(c)
            val objExpr = buildObjective(objective, t.encoding, ctx)
            opt.MkMinimize(objExpr)
            return when (opt.Check()) {
                Status.SATISFIABLE -> {
                    val sample = decode(opt.model, t.encoding)
                    MinimizeResult.Optimal(sample, objective.evaluate(sample))
                }
                // Z3 Optimize does not surface unsat cores (would require switching to a
                // tracked solver, which loses the optimization wrapper). Leave core null.
                Status.UNSATISFIABLE -> MinimizeResult.Infeasible()
                else -> MinimizeResult.Unknown(
                    TerminationReason.Timeout,
                )
            }
        } finally {
            ctx.close()
        }
    }


    private fun buildObjective(
        obj: LinearObjective,
        encoding: Z3Encoding,
        ctx: Context,
    ): ArithExpr<RealSort> {
        val terms = ArrayList<ArithExpr<RealSort>>()
        if (obj.constant != 0.0) terms.add(realLit(obj.constant, ctx))
        for (b in obj.boolWeights.indices) {
            val w = obj.boolWeights[b]
            if (w == 0.0) continue
            // `bool ? w : 0` as a real expression.
            @Suppress("UNCHECKED_CAST")
            val term = ctx.mkITE(encoding.boolExprs[b], realLit(w, ctx), realLit(0.0, ctx))
                as ArithExpr<RealSort>
            terms.add(term)
        }
        for (i in obj.intCoefficients.indices) {
            val c = obj.intCoefficients[i]
            if (c == 0.0) continue
            @Suppress("UNCHECKED_CAST")
            val asReal = ctx.mkInt2Real(encoding.intExprs[i]) as ArithExpr<RealSort>
            terms.add(ctx.mkMul(realLit(c, ctx), asReal))
        }
        if (terms.isEmpty()) return realLit(0.0, ctx)
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms.toTypedArray()) as ArithExpr<RealSort>
    }

    private fun realLit(value: Double, ctx: Context): ArithExpr<RealSort> =
        ctx.mkReal(value.toString())

    override fun solve(params: Z3Params): SolveResult {
        val ctx = newContext()
        try {
            val t = Z3Translator.translate(problem, ctx)
            // Tracked asserts on factor-derived constraints let Z3 produce an unsat core
            // attributed back to klause factor ids. Auxiliary constraints (var domains,
            // float-int link) are asserted untracked — they're never the "fault".
            val solver = ctx.mkSolver().apply { applyParams(this, ctx, params, produceCores = true) }
            for (c in t.auxiliary) solver.add(c)
            // Track each factor constraint under a uniquely-named bool; Z3 returns those
            // names in `solver.unsatCore` on UNSAT, and we parse the factor id back out.
            for (fid in t.factorExprs.indices) {
                solver.assertAndTrack(t.factorExprs[fid], ctx.mkBoolConst(coreTrackerName(fid)))
            }
            return when (solver.check()) {
                Status.SATISFIABLE -> SolveResult.Sat(decode(solver.model, t.encoding))
                Status.UNSATISFIABLE -> SolveResult.Unsat(extractCore(solver))
                else -> SolveResult.Unknown(TerminationReason.Timeout)
            }
        } finally {
            ctx.close()
        }
    }

    /** Map Z3's unsat-core return (an array of named bool trackers) back to klause factor
     *  ids. Returns [UnsatCore.Empty] when the core is empty (Z3 derived UNSAT from the
     *  auxiliary constraints alone — possible if a var domain is empty). */
    private fun extractCore(solver: Z3LibSolver): UnsatCore {
        val coreExprs = solver.unsatCore
        if (coreExprs.isEmpty()) return UnsatCore.Empty
        val ids = IntArray(coreExprs.size)
        var w = 0
        for (e in coreExprs) {
            val parsed = parseTrackerName(e.toString()) ?: continue
            ids[w++] = parsed
        }
        return if (w == ids.size) UnsatCore.of(ids) else UnsatCore.of(ids.copyOf(w))
    }

    private fun coreTrackerName(factorId: Int): String = "$CORE_TRACKER_PREFIX$factorId"

    private fun parseTrackerName(rendered: String): Int? {
        // Z3 renders bool consts as either `name` or `(:var name ...)`-ish; for `mkBoolConst`
        // it's just the literal name. Strip surrounding pipes Z3 uses when names need
        // quoting, then check the prefix.
        val stripped = rendered.trim().trim('|')
        if (!stripped.startsWith(CORE_TRACKER_PREFIX)) return null
        return stripped.substring(CORE_TRACKER_PREFIX.length).toIntOrNull()
    }

    /**
     * Independent random samples. Z3 honours `random_seed` for branching tie-breaking but
     * its default phase selection produces the same model across calls on most
     * satisfiable instances. To get genuine diversity each yield pre-pins a random
     * subset of klause vars (a mix of bools and int-domain points) before invoking the
     * solver. Different pin subsets produce different models. Falls back to an unpinned
     * solve when random pins induce Unsat.
     */
    override fun samples(params: Z3Params): Sequence<Sample> = sequence {
        val rng = kotlin.random.Random(params.randomSeed ?: System.nanoTime())
        var attempts = 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val sample = drawDiverseSample(params, rng) ?: break
            yield(sample)
            attempts++
        }
    }

    /** Pin a random subset of vars and solve; retry with fresh pins on Unsat. */
    private fun drawDiverseSample(params: Z3Params, rng: kotlin.random.Random): Sample? {
        repeat(RANDOM_PIN_RETRIES) {
            val ctx = newContext()
            try {
                val t = Z3Translator.translate(problem, ctx)
                val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
                for (c in t.allConstraints()) solver.add(c)
                addRandomPins(ctx, t.encoding, solver, rng)
                if (solver.check() == Status.SATISFIABLE) {
                    return decode(solver.model, t.encoding)
                }
            } finally {
                ctx.close()
            }
        }
        // Fallback: no pins, deterministic Z3 result. Keeps the contract honest.
        val ctx = newContext()
        try {
            val t = Z3Translator.translate(problem, ctx)
            val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
            for (c in t.allConstraints()) solver.add(c)
            return if (solver.check() == Status.SATISFIABLE) decode(solver.model, t.encoding) else null
        } finally {
            ctx.close()
        }
    }

    /** Add up to [RANDOM_PIN_COUNT_CAP] random unit constraints — bools to random
     *  polarities, ints to random values within their domain. */
    private fun addRandomPins(
        ctx: Context,
        encoding: Z3Encoding,
        solver: Z3LibSolver,
        rng: kotlin.random.Random,
    ) {
        val pinBools = minOf(encoding.boolExprs.size, RANDOM_PIN_COUNT_CAP / 2)
        val pinInts = minOf(encoding.intExprs.size, RANDOM_PIN_COUNT_CAP - pinBools)
        if (pinBools > 0) {
            val boolIds = (0 until encoding.boolExprs.size).toMutableList().apply { shuffle(rng) }
            for (i in 0 until pinBools) {
                val v = boolIds[i]
                val polarity = rng.nextBoolean()
                solver.add(if (polarity) encoding.boolExprs[v] else ctx.mkNot(encoding.boolExprs[v]))
            }
        }
        if (pinInts > 0) {
            val intIds = (0 until encoding.intExprs.size).toMutableList().apply { shuffle(rng) }
            for (i in 0 until pinInts) {
                val v = intIds[i]
                val d = problem.intDomains[v]
                val pick = d.min + rng.nextInt(d.size)
                solver.add(ctx.mkEq(encoding.intExprs[v], ctx.mkInt(pick)))
            }
        }
    }

    private companion object {
        const val RANDOM_PIN_COUNT_CAP: Int = 8
        const val RANDOM_PIN_RETRIES: Int = 5
        const val CORE_TRACKER_PREFIX: String = "fc"
    }

    override fun enumerate(params: Z3Params): Sequence<Sample> = sequence {
        val ctx = newContext()
        try {
            val t = Z3Translator.translate(problem, ctx)
            val encoding = t.encoding
            val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
            for (c in t.allConstraints()) solver.add(c)
            val window = ArrayDeque<Sample>()
            var attempts = 0L
            val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
            while (attempts < params.maxModels) {
                if (deadline != null && System.currentTimeMillis() > deadline) break
                if (solver.check() != Status.SATISFIABLE) break
                val model = solver.model
                val s = decode(model, encoding)
                attempts++
                solver.add(blockingClause(model, encoding, ctx))
                if (farEnough(s, window, params.minHammingDistance)) {
                    yield(s)
                    if (params.recentWindow > 0) {
                        if (window.size >= params.recentWindow) window.removeFirst()
                        window.addLast(s)
                    }
                }
            }
        } finally {
            ctx.close()
        }
    }

    // ---- helpers ----

    private fun newContext(): Context = Context()

    /** Apply per-solver knobs ([Z3Params.randomSeed], [Z3Params.timeoutMillis]). Z3
     *  rejects `random_seed` as a [Context] global; it has to live on the solver.
     *  When [produceCores] is true, also enables `unsat_core=true` so [Z3LibSolver.assertAndTrack]
     *  works and `solver.unsatCore` is populated on UNSAT. Tracking adds a small constant
     *  per-assertion overhead — only on for `solve` (which exposes a core through the
     *  result type); not enabled on the sample/enumerate paths. */
    private fun applyParams(
        solver: Z3LibSolver, ctx: Context, params: Z3Params, produceCores: Boolean = false,
    ) {
        if (params.randomSeed == null && params.timeoutMillis == null && !produceCores) return
        val p = ctx.mkParams()
        params.randomSeed?.let { p.add("random_seed", it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
        params.timeoutMillis?.let { p.add("timeout", it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
        if (produceCores) p.add("unsat_core", true)
        solver.setParameters(p)
    }

    private fun decode(model: Model, encoding: Z3Encoding): Sample {
        val ctx = encoding.ctx
        val bools = BooleanArray(encoding.boolExprs.size) { i ->
            val v = model.eval(encoding.boolExprs[i], true)
            v.equals(ctx.mkTrue())
        }
        val ints = IntArray(encoding.intExprs.size) { i ->
            val v = model.eval(encoding.intExprs[i], true)
            (v as? IntNum)?.int ?: 0  // float-backed int vars are populated below from reals
        }
        // For each float var, recover the real value from the model and discretize it
        // back to the bucket index expected by the schema-side decoder. Any int var that
        // backs a float gets its bucket value written here.
        val meta = problem.floatMetadata
        if (meta != null) {
            for (fid in 0 until meta.numFloatVars) {
                val realExpr = encoding.realExprs[fid]
                val v = model.eval(realExpr, true)
                val real = (v as com.microsoft.z3.RatNum).let {
                    it.bigIntNumerator.toDouble() / it.bigIntDenominator.toDouble()
                }
                val ivl = meta.intervals[fid]
                val buckets = meta.bucketCounts[fid]
                val bucket = if (buckets <= 1) 0
                else (((real - ivl.lo) / (ivl.hi - ivl.lo)) * (buckets - 1)).toInt()
                    .coerceIn(0, buckets - 1)
                ints[meta.intVarByFloatVar[fid]] = bucket
            }
        }
        return Sample(bools, ints)
    }

    private fun blockingClause(model: Model, encoding: Z3Encoding, ctx: Context): BoolExpr {
        // Forbid the exact assignment by OR-ing each variable with the negation of its
        // current model value.
        val terms = ArrayList<BoolExpr>(encoding.boolExprs.size + encoding.intExprs.size)
        for (b in encoding.boolExprs) {
            val current = model.eval(b, true)
            if (current.equals(ctx.mkTrue())) terms.add(ctx.mkNot(b)) else terms.add(b)
        }
        for (i in encoding.intExprs) {
            val current = model.eval(i, true) as IntNum
            terms.add(ctx.mkNot(ctx.mkEq(i, ctx.mkInt(current.int))))
        }
        return ctx.mkOr(*terms.toTypedArray())
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }}
