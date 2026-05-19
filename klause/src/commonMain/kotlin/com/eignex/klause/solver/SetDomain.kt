package com.eignex.klause.solver

/**
 * Immutable initial domain for a set variable. Elements are slot indices in
 * `[0, universeSize)`; mapping from FZN / schema element values (e.g. `1..n` or enum tags)
 * to these slot indices lives in the front-end that builds the [Problem].
 *
 * Domain invariant: `required ⊆ S ⊆ possible`. A set var is *fixed* when
 * `required == possible`.
 *
 * Like [IntDomain], this is the static declaration; the per-session mutable view lives in
 * [com.eignex.klause.solver.propagation.PropagationState.setRequired] /
 * [com.eignex.klause.solver.propagation.PropagationState.setPossible]. Factors propagate by
 * calling `requireElement` / `excludeElement` on that state.
 *
 * Typical builders:
 *  - [unrestricted]: `(required=∅, possible=full)` — equivalent to MZN `var set of 1..n`.
 *  - [singleton]: forced one-element set.
 */
class SetDomain(
    val universeSize: Int,
    val required: Bits,
    val possible: Bits,
) {
    init {
        require(required.size == universeSize) {
            "required.size ${required.size} != universeSize $universeSize"
        }
        require(possible.size == universeSize) {
            "possible.size ${possible.size} != universeSize $universeSize"
        }
        require(possible.containsAll(required)) {
            "SetDomain invariant violated: required ⊄ possible"
        }
    }

    val cardMin: Int get() = required.cardinality()
    val cardMax: Int get() = possible.cardinality()

    /** Domain is single-valued when required and possible coincide. */
    val isFixed: Boolean get() = cardMin == cardMax

    override fun equals(other: Any?): Boolean {
        if (other !is SetDomain) return false
        return universeSize == other.universeSize &&
            required == other.required &&
            possible == other.possible
    }

    override fun hashCode(): Int {
        var h = universeSize
        h = 31 * h + required.hashCode()
        h = 31 * h + possible.hashCode()
        return h
    }

    override fun toString(): String = "SetDomain(required=$required, possible=$possible)"

    companion object {
        /** `var set of 0..n-1`: any subset of the universe is allowed. */
        fun unrestricted(universeSize: Int): SetDomain =
            SetDomain(universeSize, Bits.empty(universeSize), Bits.full(universeSize))

        /** Fixed at the given element set. */
        fun singleton(universeSize: Int, elements: IntArray): SetDomain {
            val both = Bits.of(universeSize, elements)
            return SetDomain(universeSize, both, both.copy())
        }
    }
}
