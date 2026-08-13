package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample

/**
 * SMT-LIB 2 front-end (`.smt2` / `.smt`; QF_LIA / QF_LRA / QF_LIRA). Emits the SMT-LIB convention: a
 * `sat` / `unsat` / `unknown` status line, followed (when sat) by a `(get-model)`-style
 * `(define-fun …)` block. `-s` statistics are emitted as `;` comment lines.
 */
internal object SmtLibMode : CliMode {
    override val names = listOf("smtlib", "smt", "smt2")
    override val extensions = listOf("smt2", "smt")
    override fun newSession(): ModeSession = Session()

    private class Session : ModeSession {
        // Shared with the deferred bounding: set once the presolve-phase OBBT decides whether a side fell
        // back to a lossy clamp, and read by output() at status-line time (after solving) — so an `unsat`
        // over a clamped box is reported as `unknown`.
        private val clamp = ClampFlag()

        override fun flags(): List<FlagSpec> = emptyList()

        override fun load(path: String, common: CommonOptions): Solvable {
            // Unbounded SMT ints use the ambient default int range (shared with the FlatZinc front-end).
            val config = KlauseConfig.current
            val parsed = SmtLib.parse(
                openFileSource(path),
                config.unboundedIntLo,
                config.unboundedIntHi,
                searchBound = config.unboundedSearchBound,
            )
            cliLogger(common.verbose).v {
                "parsed ${fileName(path)}: bool=${parsed.problem.numBoolVars} int=${parsed.problem.numIntVars} " +
                    "real=${parsed.problem.numRealVars} factors=${parsed.problem.numFactors}"
            }
            val ints = parsed.intVarNames
            val bools = parsed.boolVarNames
            val reals = parsed.realVarNames
            val render: (Sample) -> String = { s -> renderModel(ints, bools, reals, s) }
            val base = linearSolvable(parsed.problem, parsed.objective, parsed.sense == ObjectiveSense.MAXIMIZE, render)
            // Defer OBBT into the presolve phase: parsing only reads, and the LP tightening runs under the
            // presolve budget instead of unbounded at load. The run also decides the clamp verdict. Absent
            // when the model has no open domain (nothing to bound, never clamped).
            val deferred = parsed.deferredBounds ?: return base
            return base.withDeferredBounds { cancellation ->
                val bounded = deferred.run(cancellation)
                clamp.clamped = bounded.clamped
                if (bounded.openlyInfeasible) {
                    // Refuted over the genuinely open ranges, so the model has no solution anywhere —
                    // not merely none in the search box. Hand the search a problem that says so, and
                    // leave the clamp flag clear so the verdict is reported as the `unsat` it is.
                    refutedProblem(parsed.problem)
                } else {
                    parsed.problem.withIntDomains(bounded.domains, bounded.openLo, bounded.openHi)
                }
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput(clamp)
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. Real values come from the leaf LP solve. */
internal fun renderModel(ints: Map<String, Int>, bools: Map<String, Int>, reals: Map<String, Int>, s: Sample): String =
    buildString {
        append("(\n")
        for ((name, id) in ints) append("  (define-fun $name () Int ${s.ints[id]})\n")
        for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
        for ((name, id) in reals) {
            val v = if (id < s.reals.size) s.reals[id] else 0.0
            append("  (define-fun $name () Real $v)\n")
        }
        append(")")
    }

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. When [clamp] is set
 *  (by the presolve-phase deferred bounding), an `unsat` is only `unsat` within the finite solver range —
 *  the sound verdict for the original (unbounded) problem is `unknown`, so it is reported as such. */
internal class SmtLibOutput(private val clamp: ClampFlag = ClampFlag()) : BufferedBestOutput() {
    override val commentPrefix: String = ";"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "sat"
        Verdict.UNSATISFIABLE -> if (clamp.clamped) "unknown" else "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // Deliberately lean block: SMT-LIB comments carry only the headline search counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS

    private companion object {
        private val SMT_SEARCH_KEYS = setOf("nodes", "failures", "propagations")
    }
}

/**
 * [problem] rewritten so it is plainly unsatisfiable: every integer pinned to zero and one row demanding
 * `x₀ ≥ 1`. Used when the model has already been refuted over its genuinely open ranges — the search must
 * not be handed the original domains (it would explore an invented box and could only answer `unknown`),
 * and it must not be handed a merely *pinned* problem either, since that could be satisfiable by accident
 * and would then report a model the original does not have.
 */
private fun refutedProblem(problem: Problem): Problem {
    val pinned = Array(problem.numIntVars) { IntDomain(0L, 0L) }
    val contradiction = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
    return Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = pinned,
        factors = problem.factors.toList() + contradiction,
        numRealVars = problem.numRealVars,
        realLower = problem.realLower,
        realUpper = problem.realUpper,
    )
}
