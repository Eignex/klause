package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.runner.MZN_RANDOM_SEED
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles

/**
 * One instance solved by the strong solver of its format: clasp for DIMACS/OPB/WCNF, the CPMpy cp-sat container for
 * XCSP3 (OR-Tools reads no XCSP3), z3 for SMT-LIB, SCIP for MPS, and `minizinc --solver <backend>` for MiniZinc.
 * `bench reference` and `solve-one backend=reference` both solve through here, so a reference row and a lab case of the
 * same instance come from the same invocation. Results are cached as `bench reference` always cached them.
 */
internal object ReferenceSolve {
    /** A reference solve: the solver that ran, its result, and the model's objective sense. */
    data class Run(
        val solver: String,
        val result: SolverInvocation.Result,
        val optimize: Boolean,
        val maximize: Boolean,
    )

    /** The solver [ref]'s format is referenced by; [backend] names the one for formats that take a choice. */
    fun solverIdFor(ref: ProblemRef, backend: String): String = when (ref.format) {
        Format.DIMACS, Format.OPB, Format.WCNF -> "clasp"
        Format.SMTLIB -> "z3"
        Format.MPS -> "scip"
        else -> backend
    }

    fun run(ref: ProblemRef, backend: String, asked: SolverInvocation.Settings, budget: Budget): Run {
        val solver = solverIdFor(ref, backend)
        // One worker unless asked for more: a MiniZinc backend such as cp-sat otherwise starts one per core, so cases
        // run side by side oversubscribe the machine and their times say more about the load than the instance.
        val settings = asked.copy(processors = asked.processors ?: 1)
        val cacheTag = when (ref.format) {
            Format.DIMACS, Format.OPB, Format.WCNF -> "clasp"

            Format.XCSP3 -> "$backend-xcsp3"

            Format.SMTLIB -> "z3"

            Format.MPS -> "scip"

            // The seed fixes the instance a random-data model compiles to, so results under another seed differ; so do
            // results with another worker count.
            else -> "$backend-seed$MZN_RANDOM_SEED-p${settings.processors}"
        }
        val key = BenchCache.keyFor(ref, cacheTag, budget)
        val cached = BenchCache.load(key)
        fun cache(r: SolverInvocation.Result) = r.also { BenchCache.store(key, it) }
        return when (ref.format) {
            // clasp minimises OPB (its only PB sense) and DIMACS has no objective; the result carries which.
            Format.DIMACS, Format.OPB, Format.WCNF -> {
                val r = cached ?: cache(ClaspReference.run(ref, budget))
                Run(
                    solver,
                    r,
                    optimize = r.objective != null || ref.format != Format.DIMACS,
                    maximize = r.stats["maximize"].toBoolean(),
                )
            }

            Format.XCSP3 -> {
                val r = cached ?: cache(Xcsp3CpSatReference.run(ref, budget, checkNotNull(settings.processors)))
                Run(solver, r, optimize = r.objective != null, maximize = r.stats["maximize"].toBoolean())
            }

            // SMT-LIB benchmarks are decision instances: no objective to orient.
            Format.SMTLIB -> Run(
                solver,
                cached ?: cache(Z3Reference.run(ref, budget)),
                optimize = false,
                maximize = false,
            )

            // SCIP reports the bound in the model's OBJSENSE orientation, carried in stats.
            Format.MPS -> {
                val r = cached ?: cache(ScipReference.run(ref, budget))
                Run(solver, r, optimize = true, maximize = r.stats["maximize"].toBoolean())
            }

            else -> {
                val (optimize, maximize) = solveKind(ref)
                Run(
                    solver,
                    cached ?: cache(SolverInvocation.runReference(ref, backend, settings, budget, optimize)),
                    optimize,
                    maximize,
                )
            }
        }
    }

    /** Read `(optimize, maximize)` from the model's `solve` item (comments stripped) so the reference
     *  path needs no klause `Problem`. A `satisfy` model — or one whose solve item is not in the top
     *  `.mzn` — is treated as a CSP: feasibility only, no `-a` enumeration. */
    private fun solveKind(ref: ProblemRef): Pair<Boolean, Boolean> {
        val stripped = CorpusFiles.readText(CorpusFetcher.resolve(ref.source))
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("%[^\n]*"), " ")
        val keyword = Regex("""\bsolve\b[^;]*?\b(satisfy|minimize|maximize)\b""", RegexOption.DOT_MATCHES_ALL)
            .findAll(stripped).lastOrNull()?.groupValues?.get(1)
        return when (keyword) {
            "maximize" -> true to true
            "minimize" -> true to false
            else -> false to false
        }
    }
}
