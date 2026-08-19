package com.eignex.klause.cli

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.formats.smtlib.IntDigitColumns
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.IntDomain
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
            val render: (Sample) -> String = { s -> renderModel(ints, bools, reals, s, parsed.intDigits) }
            val base = linearSolvable(parsed.problem, parsed.objective, parsed.sense == ObjectiveSense.MAXIMIZE, render)
            // Defer OBBT into the presolve phase: parsing only reads, and the LP tightening runs under the
            // presolve budget instead of unbounded at load. The run also decides the clamp verdict. Absent
            // when the model has no open domain (nothing to bound, never clamped).
            clamp.clamped = parsed.clamped
            val deferred = parsed.deferredBounds ?: return base
            // A single witness answers "is it satisfiable"; it answers neither "what are all the solutions"
            // nor "which is best", so those callers must search as usual.
            val witnessUsable = parsed.objective == null && !common.allSolutions && common.solutionCap == null
            return base.withDeferredBounds { cancellation ->
                val bounded = deferred.run(cancellation)
                clamp.clamped = bounded.clamped
                // The digit lowering can add columns after the bounds were captured, leaving the witness
                // shorter than the problem; it covers only the variables it was derived over.
                val witness = bounded.openSolution
                    ?.takeIf { witnessUsable && it.size == parsed.problem.numIntVars }
                if (witness != null) {
                    // Verified against every row of the model before it was offered, and the model is
                    // nothing but those rows — so pinning to it hands the search a solution to confirm,
                    // where the invented box it would otherwise search may hold none.
                    parsed.problem.withIntDomains(
                        Array(witness.size) { IntDomain(witness[it], witness[it]) },
                        BooleanArray(witness.size),
                        BooleanArray(witness.size),
                    )
                } else if (bounded.openlyInfeasible) {
                    // Refuted over the genuinely open ranges, so the model has no solution anywhere —
                    // not merely none in the search box. Hand the search a problem that says so, and
                    // leave the clamp flag clear so the verdict is reported as the `unsat` it is.
                    refutedProblem(parsed.problem)
                } else {
                    val boxed = parsed.problem.withIntDomains(bounded.domains, bounded.openLo, bounded.openHi)
                    // Offer the status line a way to keep a refutation the box played no part in: the
                    // certificate is derived over the factors no invented bound reaches, so it can only
                    // recover the `unsat` that would otherwise be downgraded, never produce one.
                    if (bounded.clamped) {
                        clamp.boxFreeRefutation = { refutationIsBoxFree(boxed, certificationBudget(common)) }
                    }
                    boxed
                }
            }
        }

        override fun output(common: CommonOptions): OutputProtocol = SmtLibOutput(clamp)

        /** Budget for the certification: half of what the run's `-t` deadline still allows, so a residual
         *  the sound analysis cannot refute quickly cannot spend the rest of the limit either. Never, for a
         *  run with no limit. */
        private fun certificationBudget(common: CommonOptions): Cancellation {
            val deadline = common.deadlineAtMs ?: return Cancellation.Never
            val now = nowMillis()
            val stopAt = now + (deadline - now).coerceAtLeast(0L) / 2
            return Cancellation { nowMillis() >= stopAt }
        }
    }
}

/** Render an SMT-LIB `(get-model)`-style model: one `(define-fun name () Sort value)` per
 *  declared variable. Real values come from the leaf LP solve. */
internal fun renderModel(
    ints: Map<String, Int>,
    bools: Map<String, Int>,
    reals: Map<String, Int>,
    s: Sample,
    intDigits: Map<Int, IntDigitColumns> = emptyMap(),
): String = buildString {
    append("(\n")
    for ((name, id) in ints) append("  (define-fun $name () Int ${intValue(id, s, intDigits)})\n")
    for ((name, id) in bools) append("  (define-fun $name () Bool ${s.bools[id]})\n")
    for ((name, id) in reals) {
        val v = if (id < s.reals.size) s.reals[id] else 0.0
        append("  (define-fun $name () Real $v)\n")
    }
    append(")")
}

/** A declared integer's value: off its digit columns when it was lowered onto them, off the variable
 *  otherwise. */
private fun intValue(id: Int, s: Sample, intDigits: Map<Int, IntDigitColumns>): String =
    intDigits[id]?.decimalIn(s.ints) ?: s.ints[id].toString()

/** SMT-LIB output protocol: `sat`/`unsat`/`unknown` + the buffered model on sat. When [clamp] is set
 *  (by the presolve-phase deferred bounding), an `unsat` is only `unsat` within the finite solver range —
 *  the sound verdict for the original (unbounded) problem is `unknown`, so it is reported as such, unless
 *  the refutation can be re-derived without the box ([ClampFlag.refutationIsBoxFree]). */
internal class SmtLibOutput(private val clamp: ClampFlag = ClampFlag()) : BufferedBestOutput() {
    override val commentPrefix: String = ";"

    override fun statusLine(verdict: Verdict): String = when (verdict) {
        Verdict.SATISFIABLE, Verdict.OPTIMAL, Verdict.BEST_FOUND -> "sat"
        Verdict.UNSATISFIABLE -> if (clamp.clamped && !clamp.refutationIsBoxFree()) "unknown" else "unsat"
        Verdict.UNKNOWN -> "unknown"
    }

    // The two roads to `unknown` want opposite responses from the caller: a refutation the box blocked is
    // very likely an unsat waiting on real bounds, while an exhausted budget just wants a longer one.
    override fun verdictReason(verdict: Verdict): String? = when {
        verdict == Verdict.UNSATISFIABLE && clamp.clamped && !clamp.refutationIsBoxFree() ->
            "unknown: refuted inside the clamped search range, not over the model's own"

        verdict == Verdict.UNKNOWN -> "unknown: ${softVerdictCause()}"

        else -> null
    }

    // Deliberately lean block: SMT-LIB comments carry only the headline search counters.
    override fun keepStat(key: String): Boolean = key in SMT_SEARCH_KEYS

    private companion object {
        private val SMT_SEARCH_KEYS = setOf("nodes", "failures", "propagations")
    }
}
