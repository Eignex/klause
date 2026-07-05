package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Lit

/**
 * The reification protocol shared by the reified arithmetic propagators (linear, pseudo-Boolean,
 * cardinality): once the relation's reachable range is known, the aux indicator is forced true when
 * the relation always holds and false when it never can; otherwise, if the aux is already assigned,
 * its polarity selects which body propagation runs.
 *
 * [alwaysHolds]/[neverHolds] are the relation's decidedness on the current range. [pinAntecedent]
 * supplies the reason for pinning the aux (same reason either way). [extraFalsePin] handles a
 * relation-specific early false-pin (e.g. a single-term equality whose target is unreachable inside
 * the bound interval); it returns the pin's result, or null when it does not apply. [propagateTrue]
 * and [propagateFalse] run the body under the assigned aux, receiving the aux pin as their extra
 * antecedent.
 */
internal inline fun PropagationState.reifiedAuxTail(
    auxBoolVar: Int,
    alwaysHolds: Boolean,
    neverHolds: Boolean,
    pinAntecedent: () -> IntArray?,
    extraFalsePin: () -> Boolean? = { null },
    propagateTrue: (auxAntecedent: Int) -> Boolean,
    propagateFalse: (auxAntecedent: Int) -> Boolean,
): Boolean {
    if (alwaysHolds) return pinBool(auxBoolVar, true, pinAntecedent())
    if (neverHolds) return pinBool(auxBoolVar, false, pinAntecedent())
    extraFalsePin()?.let { return it }
    val aux = boolValues[auxBoolVar] ?: return true
    val auxAntecedent = Lit.make(auxBoolVar, !aux)
    return if (aux) propagateTrue(auxAntecedent) else propagateFalse(auxAntecedent)
}
