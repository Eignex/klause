package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import kotlin.random.Random

/** A selected problem and the suite it was selected from, which with [ProblemRef.name] names it exactly. */
internal data class SelectedProblem(val suite: String, val ref: ProblemRef, val defaultPerFamily: Int? = null) {
    /** The family per-family caps count in: the provider's own grouping, kept apart per suite. */
    val family: String get() = "$suite:${ref.family ?: ref.name.substringBefore('/')}"
}

internal object BenchSelection {
    /**
     * Cap each family of [candidates] at [perFamily], or at its suite's default when that is null, keeping the
     * head of the family or, with a [seed], a seeded sample of it. [accept] runs only on the instances a cap
     * reaches, in that order, so an expensive filter (reading each source for its objective) fills the cap with
     * accepted instances without reading the whole corpus. Families keep their first-seen order.
     */
    fun capFamilies(
        candidates: List<SelectedProblem>,
        perFamily: Int?,
        seed: Long?,
        accept: (ProblemRef) -> Boolean = { true },
    ): List<SelectedProblem> {
        val families = LinkedHashMap<String, MutableList<SelectedProblem>>()
        for (candidate in candidates) families.getOrPut(candidate.family) { mutableListOf() }.add(candidate)
        return families.values.flatMap { family ->
            val cap = perFamily ?: family.first().defaultPerFamily
            val ordered = if (cap != null && seed != null) family.shuffled(Random(seed)) else family
            val accepted = ordered.asSequence().filter { accept(it.ref) }
            if (cap == null) accepted.toList() else accepted.take(cap).toList()
        }
    }
}
