package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.CircuitInvariant
import com.eignex.klause.factor.circuit.SubcircuitInvariant
import com.eignex.klause.factor.global.*
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.objective.ObjectiveBoundInvariant
import com.eignex.klause.factor.scheduling.*
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.Factor

/** Builds the local-search-engine view of immutable factor data. */
internal fun Factor.invariantProjection(): Invariant = when (this) {
    is AllDifferent -> AllDifferentInvariant(
        vars,
        domainMin,
        domainSize,
        presents,
        exceptValues,
        occurrencesByVar,
        { state, idx -> present(state, idx) },
    )

    is ArrayMinMax -> ArrayMinMaxInvariant(result, xs, max)

    is Cardinality -> CardinalityInvariant(boolVars, literals, min, max)

    is Circuit -> if (subcircuit) {
        SubcircuitInvariant(succ, n, ::computeCost)
    } else {
        CircuitInvariant(succ, n, ::computeCost)
    }

    is Clause -> ClauseInvariant(boolVars, literals, tautological)

    is ComparisonClause -> ComparisonClauseInvariant(vars, ops, consts)

    is Cumulative -> cumulativeInvariantProjection()

    is Diffn -> DiffnInvariant(xs, ys, widths, heights, widthVars, heightVars, nonStrict, n, ::varToRectOf)

    is Element -> ElementInvariant(idx, result, arr, arrIsVars, indexOffset)

    is GlobalCardinality -> GlobalCardinalityInvariant(
        xs,
        cover,
        countVars,
        countLow,
        countHigh,
        closed,
        presents,
        coverIndexByValue,
        { state, idx -> present(state, idx) },
    )

    is Increasing -> IncreasingInvariant(xs, gap)

    is Inverse -> InverseInvariant(f, g, fOffset, gOffset)

    is LexLess -> LexLessInvariant(xs, ys, strict)

    is Linear -> integerConstants?.let { LinearInvariant(it.coeffs, vars, op, it.bound) } ?: NoInvariant

    is Mdd -> MddInvariant(seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost)

    is NValue -> NValueInvariant(n, xs, mode, presents, { state, idx -> present(state, idx) })

    is ObjectiveBoundFactor -> ObjectiveBoundInvariant(boolVars, boolWeights, intVars, intCoeffs, bound)

    is Product -> ProductInvariant(a, b, result)

    is PseudoBoolean -> PseudoBooleanInvariant(boolVars, weights, literals, op, bound)

    is RealProduct,
    is ReifiedRealLinear,
    is GaussianXor,
    is SymmetryHandling,
    -> NoInvariant

    is ReifiedCardinality -> ReifiedCardinalityInvariant(auxBoolVar, literals, min, max, boolVars)

    is ReifiedLinear -> integerConstants?.let {
        ReifiedLinearInvariant(auxBoolVar, it.coeffs, vars, op, it.bound)
    } ?: NoInvariant

    is ReifiedPseudoBoolean -> ReifiedPseudoBooleanInvariant(auxBoolVar, weights, literals, op, bound, boolVars)

    is Regular -> RegularInvariant(seq, numStates, alphabetSize, transitions, q0, accepting)

    is Sort -> SortInvariant(xs, ys)

    is SymmetricAllDifferent -> SymmetricAllDifferentInvariant(xs, indexOffset)

    is Table -> TableInvariant(xs, tuples, arity, numTuples, singleColumnByVar, multiColumnsByVar, hi)

    is ValuePrecede -> ValuePrecedeInvariant(s, t, xs)

    is Xor -> XorInvariant(boolVars, literals, targetParity)

    else -> this as? Invariant ?: NoInvariant
}

private fun Cumulative.cumulativeInvariantProjection(): Invariant {
    val cumulative = CumulativeInvariant(
        starts,
        durations,
        resources,
        capacity,
        presents,
        durationVars,
        resourceVars,
        capacityVar,
        n,
        ::startPosOf,
        ::durPosOf,
        ::resPosOf,
    )
    return if (unary) {
        DisjunctiveInvariant(starts, durations, presents, durationVars, cumulative)
    } else {
        cumulative
    }
}
