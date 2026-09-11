package com.eignex.klause.solver.search

/**
 * Stable Boolean names for complementary immutable payloads in one source-root search session.
 *
 * [sourceBooleanCount] reserves every existing Boolean id, including currently unused ids. No new
 * source Booleans may be introduced afterward. A changed source model or root requires a new session
 * and registry; retracts, restarts and physical theory epochs with identical source meaning do not.
 */
class SearchAtomRegistry(
    /** Number of pre-existing source Boolean variables. */
    val sourceBooleanCount: Int,
    private val maxAtoms: Int = 4096,
) {
    private val names = HashMap<SearchTheoryDecision, RegisteredTheoryDecision>()
    private val assertions = ArrayList<RegisteredTheoryDecision>()
    private var attached = false

    init {
        require(sourceBooleanCount in 0..MAX_BOOLEAN_COUNT)
        require(maxAtoms in 0..Int.MAX_VALUE / 2)
    }

    internal fun attach() {
        check(!attached) { "an atom registry belongs to exactly one search session" }
        attached = true
    }

    internal fun register(positive: SearchTheoryDecision, negative: SearchTheoryDecision): SearchTheoryAtom? {
        if (positive is RegisteredTheoryDecision || negative is RegisteredTheoryDecision || positive == negative) return null
        val previous = names[positive]
        if (previous != null) {
            val complement = assertions[(previous.literal xor 1) - sourceBooleanCount * 2]
            return if (complement.payload == negative) SearchTheoryAtom(previous, complement) else null
        }
        if (negative in names || assertions.size / 2 >= maxAtoms) return null
        val variable = sourceBooleanCount.toLong() + assertions.size / 2
        if (variable >= MAX_BOOLEAN_COUNT) return null
        val literal = variable.toInt() shl 1
        val first = RegisteredTheoryDecision(positive, literal, this)
        val second = RegisteredTheoryDecision(negative, literal xor 1, this)
        names[positive] = first
        names[negative] = second
        assertions.add(first)
        assertions.add(second)
        return SearchTheoryAtom(first, second)
    }

    internal fun accepts(literal: Int): Boolean = literal >= 0 &&
        (literal ushr 1).toLong() < sourceBooleanCount.toLong() + assertions.size / 2

    internal fun assertion(literal: Int): RegisteredTheoryDecision? {
        val index = literal.toLong() - sourceBooleanCount.toLong() * 2
        return if (index in 0 until assertions.size.toLong()) assertions[index.toInt()] else null
    }

    internal fun literal(decision: SearchDecision): Int? = when (decision) {
        is SearchDecision.Bool -> decision.literal.takeIf(::accepts)
        is SearchDecision.Theory -> (decision.decision as? RegisteredTheoryDecision)
            ?.takeIf { it.owner === this }?.literal
        else -> null
    }

    private companion object {
        const val MAX_BOOLEAN_COUNT = 1 shl 30
    }
}

/** An owner-bound assertion delivered inside [SearchDecision.Theory] alongside its Boolean name. */
class RegisteredTheoryDecision internal constructor(
    /** Immutable theory-native assertion, interpreted by its owning component. */
    val payload: SearchTheoryDecision,
    /** Encoded literal valid only in the originating source-root session. */
    val literal: Int,
    internal val owner: SearchAtomRegistry,
) : SearchTheoryDecision

/** An exhaustive exclusive pair; both alternatives use the shared Boolean trail and clause learning. */
class SearchTheoryAtom internal constructor(
    /** First registered assertion. */
    val positive: RegisteredTheoryDecision,
    /** Its exact complement. */
    val negative: RegisteredTheoryDecision,
) {
    /** Theory alternatives for [SearchBrancher.nextBranch]. */
    fun alternatives(): List<SearchDecision> = listOf(SearchDecision.Theory(positive), SearchDecision.Theory(negative))
}

/** Source antecedents for a component-proved deduction; every leaf must be nameable and active. */
sealed interface SearchAtomPremise {
    /** A source Boolean or owner-bound theory assertion. */
    data class Asserted(val decision: SearchDecision) : SearchAtomPremise

    /** All premises of an independently justified intermediate deduction. Empty means a root axiom. */
    class All(premises: List<SearchAtomPremise>) : SearchAtomPremise {
        internal val premises = premises.toList()
    }

    /** A missing witness invalidates the entire explanation. */
    data object Unavailable : SearchAtomPremise
}

/**
 * Expand exact source antecedents to a clause, or decline without omitting any premise.
 *
 * The component must prove that [premise] implies [conclusion] (or contradiction when null) under the
 * immutable root. This function checks names and current assertion truth, not the arithmetic proof.
 * [maxNodes] bounds traversal including repeated DAG visits and the resulting literal count.
 */
fun SearchContext.explainAtoms(
    premise: SearchAtomPremise,
    conclusion: SearchDecision? = null,
    maxNodes: Int = 4096,
): SearchExplanation? {
    if (maxNodes <= 0) return null
    val literals = LinkedHashSet<Int>()
    if (conclusion != null) literals.add(atomLiteral(conclusion) ?: return null)
    val pending = ArrayDeque<SearchAtomPremise>()
    pending.add(premise)
    var remaining = maxNodes
    while (pending.isNotEmpty()) {
        if (remaining-- <= 0) return null
        when (val current = pending.removeLast()) {
            is SearchAtomPremise.Asserted -> {
                val literal = atomLiteral(current.decision) ?: return null
                if (boolValue(literal ushr 1) != (literal and 1 == 0)) return null
                literals.add(literal xor 1)
            }
            is SearchAtomPremise.All -> {
                if (current.premises.size > remaining - pending.size) return null
                pending.addAll(current.premises)
            }
            SearchAtomPremise.Unavailable -> return null
        }
    }
    return SearchExplanation(literals.toIntArray())
}
