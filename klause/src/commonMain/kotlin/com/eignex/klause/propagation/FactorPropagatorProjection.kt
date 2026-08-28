package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.*
import com.eignex.klause.factor.bool.*
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.CircuitPropagator
import com.eignex.klause.factor.circuit.SubcircuitPropagator
import com.eignex.klause.factor.global.*
import com.eignex.klause.factor.objective.ObjectiveBoundFactor
import com.eignex.klause.factor.scheduling.*
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.factor.symmetry.SymmetryPropagator
import com.eignex.klause.factor.table.*
import com.eignex.klause.ir.Factor
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.propagation.difference.DifferenceSystemPropagator
import com.eignex.klause.util.PermutationGroup

/** Builds the propagation-engine view of immutable factor data. */
internal fun Factor.propagatorProjection(): Propagator = when (this) {
    is ArrayMinMax -> ArrayMinMaxPropagator(result, xs, max, boolVars, intVars)

    is Cardinality -> CardinalityPropagator(boolVars, intVars, literals, min, max)

    is Circuit -> if (subcircuit) SubcircuitPropagator(succ, n) else CircuitPropagator(succ, n)

    is Clause -> ClausePropagator(boolVars, intVars, literals)

    is ComparisonClause -> ComparisonClausePropagator(vars, ops, consts)

    is Cumulative -> cumulativePropagator()

    is DifferenceSystem -> DifferenceSystemPropagator(edges)

    is Diffn -> DiffnPropagator(intVars, xs, ys, widths, heights, widthVars, heightVars, nonStrict, n, varSize)

    is Element -> ElementPropagator(boolVars, intVars, idx, result, arr, arrIsVars, indexOffset)

    is GaussianXor -> GaussianXorPropagator(constraints, boolVars)

    is GlobalCardinality -> GlobalCardinalityPropagator(
        boolVars,
        intVars,
        xs,
        cover,
        countVars,
        countLow,
        countHigh,
        closed,
        presents,
        coverIndexByValue,
        { idx, state -> definitelyPresent(idx, state) },
        { idx, state -> definitelyAbsent(idx, state) },
    )

    is Increasing -> IncreasingPropagator(xs, gap)

    is Inverse -> InversePropagator(boolVars, intVars, f, g, fOffset, gOffset, fIndexOf, gIndexOf)

    is LexLess -> LexLessPropagator(boolVars, intVars, xs, ys, strict)

    is Linear -> when (val c = constants) {
        is RealConstants -> NoPropagator
        is WideConstants -> WideLinearPropagator(intVars, vars, c.coefficients.toTypedArray(), op, c.bound)
        is IntegerConstants -> LinearPropagator(boolVars, intVars, c.coeffs, vars, op, c.bound)
    }

    is Mdd -> MddPropagator(
        boolVars,
        intVars,
        seq,
        numStatesPerLayer,
        layerStarts,
        transitions,
        initial,
        accepting,
        recordStride,
        cost,
        transitionIndex,
    )

    is NValue -> nValuePropagator()

    is ObjectiveBoundFactor -> NoPropagator

    is Product -> ProductPropagator(a, b, result, boolVars, intVars)

    is PseudoBoolean -> PseudoBooleanPropagator(boolVars, intVars, weights, literals, op, bound)

    is RealProduct -> NoPropagator

    is ReifiedCardinality -> ReifiedCardinalityPropagator(auxBoolVar, literals, min, max, boolVars, intVars)

    is ReifiedLinear -> when (val c = constants) {
        is WideConstants -> WideReifiedLinearPropagator(
            auxBoolVar,
            boolVars,
            intVars,
            c.coefficients.toTypedArray(),
            vars,
            op,
            c.bound,
        )

        is IntegerConstants -> ReifiedLinearPropagator(auxBoolVar, boolVars, intVars, c.coeffs, vars, op, c.bound)
    }

    is ReifiedPseudoBoolean -> ReifiedPseudoBooleanPropagator(
        auxBoolVar,
        weights,
        literals,
        op,
        bound,
        boolVars,
        intVars,
    )

    is ReifiedRealLinear -> NoPropagator

    is Regular -> RegularPropagator(boolVars, intVars, seq, numStates, alphabetSize, transitions, q0, accepting)

    is Sort -> SortPropagator(boolVars, intVars, xs, ys)

    is SymmetricAllDifferent -> SymmetricAllDifferentPropagator(boolVars, intVars, xs, indexOffset)

    is SymmetryHandling -> symmetryPropagator()

    is Table -> TablePropagator(boolVars, intVars, xs, tuples, arity, numTuples, hi, groupCache)

    is ValuePrecede -> ValuePrecedePropagator(boolVars, intVars, s, t, xs)

    // Custom factors are an established extension point. Built-in factor data stays on the engine-owned
    // projection path while the legacy hook preserves that compatibility during the migration.
    else -> asPropagator()
}

private fun Cumulative.cumulativePropagator(): Propagator = if (unary) {
    DisjunctivePropagator(intVars, starts, durations, presents, durationVars, n)
} else {
    CumulativePropagator(
        intVars,
        starts,
        durations,
        resources,
        capacity,
        presents,
        durationVars,
        resourceVars,
        capacityVar,
        n,
        sharpReasonEligible,
        constantEnergyAndCap,
    )
}

private fun NValue.nValuePropagator(): Propagator {
    val watches = if (presents.isNotEmpty()) {
        null
    } else {
        val distinct = intVars.toHashSet()
        IntArray(distinct.size * IntEvent.COUNT).also { out ->
            var i = 0
            for (v in distinct) {
                out[i++] = IntEvent.pack(v, IntEvent.LB_RAISED)
                out[i++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
                out[i++] = IntEvent.pack(v, IntEvent.VALUE_REMOVED)
                out[i++] = IntEvent.pack(v, IntEvent.FIXED)
            }
        }
    }
    return NValuePropagator(
        boolVars,
        intVars,
        n,
        xs,
        mode,
        presents,
        watches,
        presents.isEmpty(),
        { idx, state -> definitelyAbsent(idx, state) },
        { idx, state -> definitelyPresent(idx, state) },
    )
}

private fun SymmetryHandling.symmetryPropagator(): Propagator {
    val nInt = generators.first().first.size
    val nBool = generators.first().second.size
    val unified = generators.map { generator ->
        IntArray(nInt + nBool).also { out ->
            for (i in 0 until nInt) out[i] = generator.first[i]
            for (b in 0 until nBool) out[nInt + b] = nInt + generator.second[b]
        }
    }
    val strong = PermutationGroup.strongGenerators(unified, nInt + nBool, 64)
    return SymmetryPropagator(
        strong.map { permutation ->
            val movedInts = (0 until nInt).filter { permutation[it] != it }
            val movedBools = (nInt until nInt + nBool).filter { permutation[it] != it }
            val left = IntArray(movedInts.size + movedBools.size)
            val right = IntArray(left.size)
            val isBool = BooleanArray(left.size)
            var i = 0
            for (v in movedInts) {
                left[i] = v
                right[i++] = permutation[v]
            }
            for (v in movedBools) {
                left[i] = v - nInt
                right[i] = permutation[v] - nInt
                isBool[i++] = true
            }
            SymmetryPropagator.Generator(left, right, isBool)
        },
        strong,
        nInt,
    )
}
