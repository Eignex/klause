package com.eignex.klause.bench.target

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusSelection
import kotlin.random.Random

internal data class SelectedProblem(val suite: String, val ref: ProblemRef, val defaultPerFamily: Int? = null) {
    // The provider's own grouping, kept apart per suite.
    val family: String get() = "$suite:${ref.family ?: ref.name.substringBefore('/')}"
}

// The `collection` klause-lab files reference results under.
internal fun collectionOf(ref: ProblemRef): String = when (val s = ref.source) {
    is ProblemSource.Prepared -> "prepared"
    is ProblemSource.External -> s.collection.id
    is ProblemSource.ExternalIndexed -> s.collection.id
    is ProblemSource.Vendored -> s.workspaceRelPath.substringBeforeLast('/', "vendored")
    is ProblemSource.MpsFeasibility -> collectionOf(ref.copy(source = s.source)) + "-feasibility"
    is ProblemSource.InCode -> "in-code"
}

internal object BenchSelection {
    // Admits only the round-robin prefix a positive overall cap needs. Format balancing needs every family's admitted
    // size for its water-fill, so it keeps the complete per-family selection.
    fun select(
        candidates: List<SelectedProblem>,
        perFamily: Int?,
        seed: Long?,
        maxInstances: Int?,
        balanceFormats: Boolean = false,
        compatible: (SelectedProblem) -> Boolean = { true },
        accept: (ProblemRef) -> Boolean = { true },
    ): List<SelectedProblem> {
        if (balanceFormats || maxInstances == null || maxInstances <= 0) {
            val capped = capFamilies(candidates, perFamily, seed, compatible, accept)
            val selection = CorpusSelection.Selection(maxInstances = maxInstances)
            return if (balanceFormats) {
                CorpusSelection.applyBalancedBy(capped, selection, { it.family }) { it.ref.format }
            } else {
                CorpusSelection.applySelectionBy(capped, selection) { it.family }
            }
        }
        val families = admittedFamilies(candidates, perFamily, seed, compatible, accept).map { it.iterator() }.toList()
        return buildList {
            while (size < maxInstances) {
                var added = false
                for (family in families) {
                    if (size == maxInstances) return@buildList
                    if (family.hasNext()) {
                        add(family.next())
                        added = true
                    }
                }
                if (!added) break
            }
        }
    }

    // accept runs only on the instances a cap reaches, in order, so an expensive filter fills the cap without reading
    // the whole corpus.
    fun capFamilies(
        candidates: List<SelectedProblem>,
        perFamily: Int?,
        seed: Long?,
        compatible: (SelectedProblem) -> Boolean = { true },
        accept: (ProblemRef) -> Boolean = { true },
    ): List<SelectedProblem> =
        admittedFamilies(candidates, perFamily, seed, compatible, accept).flatMap { it }.toList()

    private fun admittedFamilies(
        candidates: List<SelectedProblem>,
        perFamily: Int?,
        seed: Long?,
        compatible: (SelectedProblem) -> Boolean,
        accept: (ProblemRef) -> Boolean,
    ): Sequence<Sequence<SelectedProblem>> {
        val families = LinkedHashMap<String, MutableList<SelectedProblem>>()
        for (candidate in candidates) families.getOrPut(candidate.family) { mutableListOf() }.add(candidate)
        return families.values.asSequence().map { family ->
            val cap = perFamily ?: family.first().defaultPerFamily
            val ordered = if (cap != null && seed != null) family.shuffled(Random(seed)) else family
            val accepted = ordered.asSequence().filter { accept(it.ref) && compatible(it) }
            if (cap == null) accepted else accepted.take(cap)
        }
    }
}
