package com.eignex.klause.smt

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolveResult
import org.sosy_lab.common.ShutdownNotifier
import org.sosy_lab.common.configuration.Configuration
import org.sosy_lab.common.log.LogManager
import org.sosy_lab.java_smt.SolverContextFactory
import org.sosy_lab.java_smt.api.Model
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
 * Scaffold scope: `solve()` only. `samples()` / `enumerate()` / `minimize()` come in
 * follow-ups once the basic SAT path is validated.
 */
class SmtSolver(override val problem: Problem) : Solver<SmtParams> {

    override fun solve(params: SmtParams): SolveResult {
        val context = newContext(params)
        try {
            val (encoding, constraints) = SmtTranslator.translate(problem, context.formulaManager)
            context.newProverEnvironment(ProverOptions.GENERATE_MODELS).use { prover ->
                for (c in constraints) prover.addConstraint(c)
                addAssumptions(params, encoding, prover)
                return if (prover.isUnsat) {
                    SolveResult.Unsat
                } else {
                    SolveResult.Sat(decode(prover.model, encoding))
                }
            }
        } finally {
            context.close()
        }
    }

    override fun samples(params: SmtParams): Sequence<Sample> =
        error("SmtSolver.samples() not yet implemented; use solve() for now or fall back to klause-z3")

    override fun enumerate(params: SmtParams): Sequence<Sample> =
        error("SmtSolver.enumerate() not yet implemented; use solve() for now or fall back to klause-z3")

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
        prover: org.sosy_lab.java_smt.api.ProverEnvironment,
    ) {
        val bmgr = encoding.fm.booleanFormulaManager
        val imgr = encoding.fm.integerFormulaManager
        for ((boolVar, value) in params.assumptions.bools) {
            val lit = encoding.boolFormulas[boolVar]
            prover.addConstraint(if (value) lit else bmgr.not(lit))
        }
        for ((intVar, value) in params.assumptions.ints) {
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
