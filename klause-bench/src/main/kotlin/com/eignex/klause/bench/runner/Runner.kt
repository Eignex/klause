package com.eignex.klause.bench.runner

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.bench.Formats
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.MpsObjective
import com.eignex.klause.bench.source.SmtObjective
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.objective.LinearObjective

// Subprocess solves pass the file directly to the CLI; only in-process consumers request the model, so their
// finite-domain restrictions do not filter subprocess inputs.
internal class ResolvedProblem(
    internal val ref: ProblemRef,
    private val ingest: Lazy<Problem>,
    objective: LinearObjective? = null,
    // objective stays minimization-canonical either way.
    internal val maximize: Boolean = false,
    // From the model's `solve :: int_search(...)` annotation, so benchmark runs honor the author's intended search
    // as the CLI does.
    searchParams: BacktrackParams? = null,
    deferredObjective: Lazy<LinearObjective?>? = null,
    deferredSearchParams: Lazy<BacktrackParams?>? = null,
    internal val optimizes: Boolean = objective != null,
) {
    internal val objective: LinearObjective? by (deferredObjective ?: lazyOf(objective))
    val searchParams: BacktrackParams? by (deferredSearchParams ?: lazyOf(searchParams))

    internal val name: String get() = ref.name

    // Throws when the frontend cannot build the model.
    internal val problem: Problem get() = ingest.value
}

/** Wall-clock solve budget. */
data class Budget(internal val timeoutMillis: Long = 10_000L)

internal interface Runner {
    val id: String
    fun supports(ref: ProblemRef): Boolean
    fun resolve(ref: ProblemRef): ResolvedProblem
}

internal object InProcessRunner : Runner {
    override val id = "in-process"

    override fun supports(ref: ProblemRef): Boolean =
        ref.source is ProblemSource.InCode || Formats[ref.format].inProcess

    override fun resolve(ref: ProblemRef): ResolvedProblem {
        val src = ref.source
        if (src is ProblemSource.InCode) {
            require(ref.format == Format.IN_CODE) { "${ref.name}: InCode source must use Format.IN_CODE" }
            return ResolvedProblem(ref, lazy { src.build() })
        }
        val format = Formats[ref.format]
        require(format.inProcess) { "${ref.name}: format ${ref.format} is not in-process; use its dedicated runner" }
        val file = CorpusFetcher.resolve(src)
        if (ref.format == Format.SMTLIB || ref.format == Format.MPS) {
            val sense = if (ref.format == Format.MPS) MpsObjective.read(file) else SmtObjective.read(file)
            val ingested by lazy { format.ingest(file) }
            return ResolvedProblem(ref, lazy { ingested.problem },
                deferredObjective = lazy { ingested.objective }, optimizes = sense != null,
                maximize = sense == ObjectiveSense.MAXIMIZE)
        }
        if (ref.format == Format.WCNF) {
            val ingested by lazy { format.ingest(file) }
            return ResolvedProblem(ref, lazy { ingested.problem },
                deferredObjective = lazy { ingested.objective }, optimizes = true)
        }
        return runCatching { format.ingest(file) }
            .map { ResolvedProblem(ref, lazy { it.problem }, it.objective, maximize = it.maximize) }
            .getOrElse { failure ->
                // A subprocess run does not need the in-process model, so the failure surfaces only if something asks
                // for it.
                ResolvedProblem(ref, lazy { throw failure })
            }
    }
}
