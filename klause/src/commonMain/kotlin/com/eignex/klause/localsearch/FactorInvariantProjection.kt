package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.CircuitInvariant
import com.eignex.klause.factor.circuit.SubcircuitInvariant
import com.eignex.klause.factor.global.*
import com.eignex.klause.factor.objective.FloatObjectiveBoundInvariant
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.objective.ObjectiveBoundInvariant
import com.eignex.klause.factor.objective.objectiveSumIsWide
import com.eignex.klause.factor.scheduling.*
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.ScoringCapability
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntIntMap

/**
 * Builds the local-search-engine view of immutable factor data. [domains], when given, are the domains the search
 * moves over: a linear row whose running sum can leave the 64-bit range over them takes the exact
 * [ExactLinearInvariant].
 */
internal fun Factor.invariantProjection(domains: Array<IntDomain>? = null): Invariant {
    val capabilities = executionCapabilities()
    check(capabilities.scoring != ScoringCapability.UNSUPPORTED) {
        "unsupported local-search route for ${this::class.simpleName}; implement Invariant or delegate to NoInvariant"
    }
    return buildInvariantProjection(domains).also {
        check((it === NoInvariant) == (capabilities.scoring == ScoringCapability.INERT)) {
            "local-search capability disagrees with projection for ${this::class.simpleName}"
        }
    }
}

private fun Factor.buildInvariantProjection(domains: Array<IntDomain>?): Invariant = when (this) {
    is AllDifferent -> allDifferentInvariantProjection()

    is ArrayMinMax -> ArrayMinMaxInvariant(result, xs, max)

    is Cardinality -> CardinalityInvariant(boolVars, literals, min, max)

    is Circuit -> if (subcircuit) {
        SubcircuitInvariant(succ, n, CircuitScoring(succ, subcircuit)::computeCost)
    } else {
        CircuitInvariant(succ, n, CircuitScoring(succ, subcircuit)::computeCost)
    }

    is Clause -> ClauseInvariant.of(this)

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
        { state, idx -> OptionalPresence.isPresentInAssignment(presents, idx, state) },
    )

    is Increasing -> IncreasingInvariant(xs, gap)

    is Inverse -> InverseInvariant(f, g, fOffset, gOffset)

    is LexLess -> LexLessInvariant(xs, ys, strict)

    is Linear -> integralConstants?.let { integral ->
        val integer = integerConstants
        if (integer != null && (domains == null || !needsExactSum(integer, vars, domains))) {
            LinearInvariant(integer.coeffs, vars, op, integer.bound)
        } else {
            ExactLinearInvariant(integral, vars, op)
        }
    } ?: realConstants?.let {
        RealRowInvariant(
            vars,
            it.intCoefficients.toDoubleArray(),
            realVars,
            it.realCoefficients.toDoubleArray(),
            op,
            it.bound,
            it.strict,
        )
    } ?: NoInvariant

    is Mdd -> MddInvariant(seq, numStatesPerLayer, layerStarts, transitions, initial, accepting, recordStride, cost)

    is NValue -> NValueInvariant(
        n, xs, mode, presents,
        { state, idx -> OptionalPresence.isPresentInAssignment(presents, idx, state) },
    )

    is ObjectiveBoundFactor ->
        if (realVars.isEmpty() && (domains == null || !objectiveSumIsWide(boolWeights, intVars, intCoeffs, domains))) {
            ObjectiveBoundInvariant(boolVars, boolWeights, intVars, intCoeffs, bound)
        } else {
            FloatObjectiveBoundInvariant(boolVars, boolWeights, intVars, intCoeffs, realVars, realCoeffs, bound)
        }

    is Product -> ProductInvariant(a, b, result)

    is PseudoBoolean -> PseudoBooleanInvariant(boolVars, weights, literals, op, bound)

    is RealProduct -> RealProductInvariant(intOperand, realOperand, result)

    is ReifiedRealLinear -> RealRowInvariant(vars, intCoeffs, realVars, realCoeffs, op, bound, strict, aux)

    is GaussianXor,
    is SymmetryHandling,
    -> NoInvariant

    is ReifiedCardinality -> ReifiedCardinalityInvariant(auxBoolVar, literals, min, max, boolVars)

    is ReifiedLinear -> integerConstants.let { integer ->
        if (integer != null && (domains == null || !needsExactSum(integer, vars, domains))) {
            ReifiedLinearInvariant(auxBoolVar, integer.coeffs, vars, op, integer.bound)
        } else {
            ExactLinearInvariant(constants, vars, op, auxBoolVar)
        }
    }

    is ReifiedPseudoBoolean -> ReifiedPseudoBooleanInvariant(auxBoolVar, weights, literals, op, bound, boolVars)

    is Regular -> RegularInvariant(seq, numStates, alphabetSize, transitions, q0, accepting)

    is Sort -> SortInvariant(xs, ys)

    is SymmetricAllDifferent -> SymmetricAllDifferentInvariant(xs, indexOffset)

    is Table -> tableInvariantProjection()

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

private fun Table.tableInvariantProjection(): Invariant {
    val (single, multi) = tableColumnMaps(xs, arity)
    return TableInvariant(xs, tuples, arity, numTuples, single, multi, hi)
}

private fun AllDifferent.allDifferentInvariantProjection(): Invariant {
    val counts = MutableIntIntMap()
    for (v in vars) counts.addTo(v, 1)
    val keys = IntArrayList(counts.size)
    val values = IntArrayList(counts.size)
    counts.forEach { v, count ->
        keys.add(v)
        values.add(count)
    }
    val occurrences = IntIntMap.build(keys.toIntArray(), values.toIntArray(), absent = 0)
    val except = if (exceptSet.isEmpty()) AllDifferentInvariant.NO_EXCEPT else {
        LongHashSet(exceptSet.size).also { set -> for (value in exceptSet) set.add(value) }
    }
    return AllDifferentInvariant(
        vars, domainMin, domainSize, presents, except, occurrences,
        { state, idx -> OptionalPresence.isPresentInAssignment(presents, idx, state) },
    )
}
