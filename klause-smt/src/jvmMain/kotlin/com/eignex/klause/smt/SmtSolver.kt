package com.eignex.klause.smt

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
import org.sosy_lab.common.ShutdownNotifier
import org.sosy_lab.common.configuration.Configuration
import org.sosy_lab.common.log.LogManager
import org.sosy_lab.java_smt.SolverContextFactory
import org.sosy_lab.java_smt.api.BooleanFormula
import org.sosy_lab.java_smt.api.Model
import org.sosy_lab.java_smt.api.NumeralFormula.RationalFormula
import org.sosy_lab.java_smt.api.OptimizationProverEnvironment
import org.sosy_lab.java_smt.api.SolverContext
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions

/**
 * [Solver] backed by [JavaSMT](https://github.com/sosy-lab/java-smt) — one adapter,
 * many SMT solvers behind a uniform API. Each call builds a fresh [SolverContext]
 * (closed in a finally block to release any native handles); klause factors are
 * translated to JavaSMT formulas by [SmtTranslator], handed to a [ProverEnvironment],
 * and the resulting model decoded back to a klause [Sample].
 *
 * Default backend: SMTInterpol (pure-Java, no natives required, always available).
 * Pick a different solver via [SmtParams.solver] — for the native-backed ones (Z3,
 * CVC5, MathSAT5, Bitwuzla, Yices2), you need the corresponding JavaSMT solver
 * artifact on the classpath.
 *
 * This module deliberately sits alongside `klause-z3` rather than replacing it —
 * users who want lean, single-backend Z3 access still pick that module; users who
 * want cross-backend experimentation or pure-Java SMT pick this one.
 *
 * Scaffold scope: `solve()` directly; `samples()` / `enumerate()` go through [session]
 * (a one-shot session is opened and immediately closed). Native `minimize()` comes in a
 * follow-up.
 */
class SmtSolver(override val problem: Problem) : Solver<SmtParams>, Optimizer<SmtParams> {

    /**
     * Open an [SmtSession] holding ONE [org.sosy_lab.java_smt.api.SolverContext] +
     * [org.sosy_lab.java_smt.api.ProverEnvironment] across `solve` / `samples` /
     * `enumerate` calls. The backend ([SmtParams.solver]) is locked at session
     * construction. Always [SmtSession.close] when done.
     */
    override fun session(): SmtSession = SmtSession(this, SmtParams())

    /** Open a session with a specific backend (and any other initial param defaults). */
    fun session(initialParams: SmtParams): SmtSession = SmtSession(this, initialParams)

    /**
     * Linear-objective minimisation via JavaSMT's [OptimizationProverEnvironment]. Only
     * Z3 and MathSAT5 implement optimization in JavaSMT today — other backends throw
     * [UnsupportedOperationException] from [SolverContext.newOptimizationProverEnvironment],
     * which propagates up to the caller. Pick [SmtParams.solver] accordingly.
     *
     * The objective `Σ wᵢ · bᵢ + Σ cᵢ · iᵢ + constant` is built over rationals (bool
     * indicators lifted via `ifThenElse`, ints cast via `castToRational`). Non-Linear
     * [Objective] subtypes throw at runtime — JavaSMT has no generic objective callback.
     */
    override fun minimize(objective: Objective, params: SmtParams): MinimizeResult {
        require(objective is LinearObjective) {
            "SmtSolver only supports LinearObjective; got ${objective::class.simpleName}"
        }
        val context = newContext(params)
        try {
            val opt: OptimizationProverEnvironment =
                context.newOptimizationProverEnvironment(ProverOptions.GENERATE_MODELS)
            opt.use { prover ->
                val t = SmtTranslator.translate(problem, context.formulaManager)
                for (c in t.auxiliary) prover.addConstraint(c)
                for (c in t.factorFormulas) prover.addConstraint(c)
                addAssumptions(params, t.encoding, prover)
                val objExpr = buildObjective(objective, t.encoding)
                prover.minimize(objExpr)
                return when (prover.check()) {
                    OptimizationProverEnvironment.OptStatus.OPT -> {
                        val sample = decode(prover.getModel(), t.encoding)
                        MinimizeResult.Optimal(sample, objective.evaluate(sample))
                    }
                    OptimizationProverEnvironment.OptStatus.UNSAT -> MinimizeResult.Infeasible()
                    OptimizationProverEnvironment.OptStatus.UNDEF ->
                        MinimizeResult.Unknown(TerminationReason.Timeout)
                }
            }
        } finally {
            context.close()
        }
    }

    /** Build `Σ wᵢ · bᵢ + Σ cᵢ · iᵢ + constant` as a single [RationalFormula]. Bools are
     *  lifted to `b ? 1 : 0`; ints are cast from the integer formula manager to rationals
     *  via [castIntToRational]. Skips zero-weight terms to keep the formula small. */
    private fun buildObjective(obj: LinearObjective, encoding: SmtEncoding): RationalFormula {
        val rmgr = encoding.fm.rationalFormulaManager
        val bmgr = encoding.fm.booleanFormulaManager
        var acc: RationalFormula = rmgr.makeNumber(obj.constant)
        for (b in obj.boolWeights.indices) {
            val w = obj.boolWeights[b]
            if (w == 0.0) continue
            val term = bmgr.ifThenElse(encoding.boolFormulas[b], rmgr.makeNumber(w), rmgr.makeNumber(0.0))
            acc = rmgr.add(acc, term)
        }
        for (i in obj.intCoefficients.indices) {
            val c = obj.intCoefficients[i]
            if (c == 0.0) continue
            val asReal = castIntToRational(encoding.intFormulas[i], encoding)
            acc = rmgr.add(acc, rmgr.multiply(rmgr.makeNumber(c), asReal))
        }
        return acc
    }

    /** Cross-theory cast from an integer formula to a rational formula. JavaSMT's
     *  `RationalFormulaManager` accepts an integer formula directly in arithmetic
     *  operations on backends that support mixed arithmetic; for the lean path we wrap
     *  via a fresh rational variable equated to the int. */
    private fun castIntToRational(
        intF: org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula,
        encoding: SmtEncoding,
    ): RationalFormula {
        // RationalFormulaManager.add accepts IntegerFormula via covariance on most backends,
        // but the static return type forces a NumeralFormula. Multiplying 1 × intF yields a
        // RationalFormula whose value tracks the int.
        val rmgr = encoding.fm.rationalFormulaManager
        return rmgr.multiply(rmgr.makeNumber(1L), intF as org.sosy_lab.java_smt.api.NumeralFormula)
    }

    override fun solve(params: SmtParams): SolveResult {
        val context = newContext(params)
        try {
            val t = SmtTranslator.translate(problem, context.formulaManager)
            // Request unsat-core generation so `prover.unsatCore` is populated on UNSAT.
            // Tracking maps each factor-derived formula to its factor id; auxiliary
            // (domain / real-link) constraints are added but not tracked — they'd never
            // be a useful "blame" target. Not every JavaSMT backend honors
            // GENERATE_UNSAT_CORE (SMTInterpol does; some others ignore it and return an
            // empty core), so we treat an empty/incompatible result as `core = null`.
            context.newProverEnvironment(
                ProverOptions.GENERATE_MODELS,
                ProverOptions.GENERATE_UNSAT_CORE,
            ).use { prover ->
                for (c in t.auxiliary) prover.addConstraint(c)
                val factorByFormula = HashMap<BooleanFormula, Int>(t.factorFormulas.size * 2)
                for (fid in t.factorFormulas.indices) {
                    val f = t.factorFormulas[fid]
                    prover.addConstraint(f)
                    factorByFormula[f] = fid
                }
                addAssumptions(params, t.encoding, prover)
                return if (prover.isUnsat) {
                    SolveResult.Unsat(extractCore(prover, factorByFormula))
                } else {
                    SolveResult.Sat(decode(prover.model, t.encoding))
                }
            }
        } finally {
            context.close()
        }
    }

    /** Map JavaSMT's unsat-core formulas back to klause factor ids. Returns `null` when
     *  the backend doesn't support core extraction (returns an empty list despite UNSAT),
     *  preserving the "core is opt-in per backend" contract. */
    private fun extractCore(
        prover: org.sosy_lab.java_smt.api.ProverEnvironment,
        factorByFormula: Map<BooleanFormula, Int>,
    ): UnsatCore? {
        val coreFormulas = try {
            prover.unsatCore
        } catch (_: UnsupportedOperationException) {
            return null
        }
        if (coreFormulas.isEmpty()) return null
        val ids = IntArray(coreFormulas.size)
        var w = 0
        for (f in coreFormulas) {
            val id = factorByFormula[f] ?: continue
            ids[w++] = id
        }
        if (w == 0) return null
        return UnsatCore.of(if (w == ids.size) ids else ids.copyOf(w))
    }

    override fun samples(params: SmtParams): Sequence<Sample> = sequence {
        session(params).use { s -> for (smp in s.samples(params)) yield(smp) }
    }

    override fun enumerate(params: SmtParams): Sequence<Sample> = sequence {
        session(params).use { s -> for (smp in s.enumerate(params)) yield(smp) }
    }

    private fun newContext(params: SmtParams): SolverContext {
        val config = Configuration.defaultConfiguration()
        val logger = LogManager.createNullLogManager()
        val shutdownNotifier = ShutdownNotifier.createDummy()
        return SolverContextFactory.createSolverContext(
            config, logger, shutdownNotifier, params.solver,
        )
    }

    private fun addAssumptions(
        params: SmtParams,
        encoding: SmtEncoding,
        prover: org.sosy_lab.java_smt.api.BasicProverEnvironment<*>,
    ) {
        val bmgr = encoding.fm.booleanFormulaManager
        val imgr = encoding.fm.integerFormulaManager
        params.assumptions.forEachBool { boolVar, value ->
            val lit = encoding.boolFormulas[boolVar]
            prover.addConstraint(if (value) lit else bmgr.not(lit))
        }
        params.assumptions.forEachInt { intVar, value ->
            prover.addConstraint(imgr.equal(encoding.intFormulas[intVar], imgr.makeNumber(value.toLong())))
        }
    }

    private fun decode(model: Model, encoding: SmtEncoding): Sample {
        val bools = BooleanArray(encoding.boolFormulas.size) { i ->
            model.evaluate(encoding.boolFormulas[i]) == true
        }
        val ints = IntArray(encoding.intFormulas.size) { i ->
            model.evaluate(encoding.intFormulas[i])?.toInt() ?: 0
        }
        // Float vars: read the real formula's value, snap back to the bucket index.
        val meta = problem.floatMetadata
        if (meta != null) {
            for (fid in 0 until meta.numFloatVars) {
                val real = model.evaluate(encoding.realFormulas[fid])?.toDouble() ?: 0.0
                val ivl = meta.intervals[fid]
                val buckets = meta.bucketCounts[fid]
                val bucket = if (buckets <= 1) 0
                else (((real - ivl.lo) / (ivl.hi - ivl.lo)) * (buckets - 1)).toInt().coerceIn(0, buckets - 1)
                ints[meta.intVarByFloatVar[fid]] = bucket
            }
        }
        return Sample(bools, ints)
    }
}
