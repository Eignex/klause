package com.eignex.klause.z3

import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Objective
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Sampler
import com.eignex.klause.solver.SolveResult
import com.microsoft.z3.ArithExpr
import com.microsoft.z3.BoolExpr
import com.microsoft.z3.Context
import com.microsoft.z3.IntNum
import com.microsoft.z3.Model
import com.microsoft.z3.RealSort
import com.microsoft.z3.Solver
import com.microsoft.z3.Status

/**
 * [Sampler] backed by Z3 via direct SMT translation (no bit-blast). Each factor in the
 * [Problem] is converted to a native Z3 expression in [Z3Translator]; Z3 reasons over
 * integers natively, which catches bit-blaster bugs the LogicNG path inherits.
 *
 *  - [solve] — single SAT call; returns [SolveResult.Sat], [SolveResult.Unsat], or
 *    [SolveResult.Unknown] (timeout / Z3 returned `Status.UNKNOWN`).
 *  - [sample] — *with replacement*. Fresh Z3 [Context] per draw with a perturbed seed.
 *  - [enumerate] — *without replacement*. One context, blocking clause per yielded model.
 *    `params.minHammingDistance` / `params.recentWindow` apply on top as a post-filter.
 */
class Z3Sampler(override val problem: Problem) : Sampler<Z3Params>, Optimizer<Z3Params> {

    /**
     * Linear-objective minimisation via Z3's native [com.microsoft.z3.Optimize] solver.
     * Translates a [LinearObjective] to `Σ wᵢ · bᵢ + Σ cᵢ · iᵢ + constant` over Z3 reals
     * (bool indicators lifted via `mkITE`); other [Objective] subtypes are not supported
     * and throw at runtime.
     */
    override fun minimize(objective: Objective, params: Z3Params): Sample? {
        require(objective is LinearObjective) {
            "Z3 backend only supports LinearObjective; got ${objective::class.simpleName}"
        }
        val ctx = newContext(params)
        try {
            val (encoding, constraints) = Z3Translator.translate(problem, ctx)
            val opt = ctx.mkOptimize()
            params.timeoutMillis?.let { ms ->
                val p = ctx.mkParams()
                p.add("timeout", ms.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                opt.setParameters(p)
            }
            for (c in constraints) opt.Add(c)
            val objExpr = buildObjective(objective, encoding, ctx)
            opt.MkMinimize(objExpr)
            return when (opt.Check()) {
                Status.SATISFIABLE -> decode(opt.model, encoding)
                else -> null
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
        val ctx = newContext(params)
        try {
            val (encoding, constraints) = Z3Translator.translate(problem, ctx)
            val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
            for (c in constraints) solver.add(c)
            return when (solver.check()) {
                Status.SATISFIABLE -> SolveResult.Sat(decode(solver.model, encoding))
                Status.UNSATISFIABLE -> SolveResult.Unsat
                else -> SolveResult.Unknown
            }
        } finally {
            ctx.close()
        }
    }

    override fun samples(params: Z3Params): Sequence<Sample> = sequence {
        var attempts = 0L
        var seed = params.randomSeed ?: 0L
        val deadline = params.timeoutMillis?.let { System.currentTimeMillis() + it }
        while (attempts < params.maxModels) {
            if (deadline != null && System.currentTimeMillis() > deadline) break
            val ctx = newContext(params.copy(randomSeed = seed))
            var producedSample: Sample? = null
            try {
                val (encoding, constraints) = Z3Translator.translate(problem, ctx)
                val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
                for (c in constraints) solver.add(c)
                if (solver.check() == Status.SATISFIABLE) {
                    producedSample = decode(solver.model, encoding)
                }
            } finally {
                ctx.close()
            }
            if (producedSample == null) break
            yield(producedSample)
            attempts++
            seed++
        }
    }

    override fun enumerate(params: Z3Params): Sequence<Sample> = sequence {
        val ctx = newContext(params)
        try {
            val (encoding, constraints) = Z3Translator.translate(problem, ctx)
            val solver = ctx.mkSolver().apply { applyParams(this, ctx, params) }
            for (c in constraints) solver.add(c)
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

    private fun newContext(@Suppress("UNUSED_PARAMETER") params: Z3Params): Context = Context()

    /** Apply per-solver knobs ([Z3Params.randomSeed], [Z3Params.timeoutMillis]). Z3
     *  rejects `random_seed` as a [Context] global; it has to live on the solver. */
    private fun applyParams(solver: Solver, ctx: Context, params: Z3Params) {
        if (params.randomSeed == null && params.timeoutMillis == null) return
        val p = ctx.mkParams()
        params.randomSeed?.let { p.add("random_seed", it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
        params.timeoutMillis?.let { p.add("timeout", it.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()) }
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
            (v as IntNum).int
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
        for (p in window) if (hamming(candidate, p) < minDistance) return false
        return true
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }
}
