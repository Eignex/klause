package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState

// Pins the aux when the relation always or never holds on the current range; otherwise an assigned aux selects
// the body propagation, which receives the aux pin as its extra antecedent. [extraPin] is a relation-specific
// early pin returning its result, or null when it does not apply.
internal inline fun PropagationState.reifiedAuxTail(
    auxBoolVar: Int,
    alwaysHolds: Boolean,
    neverHolds: Boolean,
    pinAntecedent: () -> IntArray?,
    extraPin: () -> Boolean? = { null },
    propagateTrue: (auxAntecedent: Int) -> Boolean,
    propagateFalse: (auxAntecedent: Int) -> Boolean,
): Boolean {
    if (alwaysHolds) return boolValues[auxBoolVar] == true || pinBool(auxBoolVar, true, pinAntecedent())
    if (neverHolds) return boolValues[auxBoolVar] == false || pinBool(auxBoolVar, false, pinAntecedent())
    extraPin()?.let { return it }
    val aux = boolValues[auxBoolVar] ?: return true
    val auxAntecedent = Lit.make(auxBoolVar, !aux)
    return if (aux) propagateTrue(auxAntecedent) else propagateFalse(auxAntecedent)
}
