package com.eignex.klause.solver.projection

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.RationalInterval
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.factor.arithmetic.GridCoordinate
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.util.Cancellation

internal class FloatGridColumn(
    val source: SourceRealId,
    val coordinate: GridCoordinate,
    val origin: BigFraction,
    val step: BigFraction,
) {
    fun value(index: Long): BigFraction? = if (coordinate.contains(index)) {
        origin + step * BigFraction.ofLong(index)
    } else null
}

internal class FloatGridMapping(
    val source: Problem,
    val target: Problem,
    columns: List<FloatGridColumn>,
    retainedReals: IntArray,
    factorOrigins: IntArray,
    private val owner: Any,
    private val containingCancellation: Cancellation,
) {
    private val capturedColumns = columns.toList()
    private val retainedReals = retainedReals.copyOf()
    private val origins = factorOrigins.copyOf()
    private val bySource = capturedColumns.associateBy { it.source.index }

    init {
        require(target.semantics === source.semantics && target.numBoolVars == source.numBoolVars)
        require(target.numIntVars == source.numIntVars + capturedColumns.size)
        require(target.numRealVars + capturedColumns.size == source.numRealVars)
        require(this.retainedReals.size == source.numRealVars)
        require(origins.size == target.numFactors && origins.all { it in source.factors.indices })
        require(bySource.size == capturedColumns.size)
        require(capturedColumns.map { it.coordinate.variable }.distinct().size == capturedColumns.size)
        require(capturedColumns.all {
            it.source.index in 0 until source.numRealVars &&
                it.coordinate.variable in source.numIntVars until target.numIntVars &&
                this.retainedReals[it.source.index] == -1
        })
        require((0 until source.numRealVars).all { it in bySource || this.retainedReals[it] in 0 until target.numRealVars })
        require(this.retainedReals.filter { it >= 0 }.distinct().size == target.numRealVars)
        require(origins.toSet().size == source.numFactors)
    }

    fun requireSource(problem: Problem) {
        require(problem === source) { "float projection belongs to a different original source" }
    }

    fun requireOwner(invocation: Any) {
        require(invocation === owner) { "float projection belongs to a different selected invocation" }
    }

    fun requireTarget(problem: Problem) {
        require(problem === target) { "float reconstruction belongs to a different private projection" }
    }

    fun column(sourceId: SourceRealId): FloatGridColumn? = bySource[sourceId.index]

    fun sourceFactor(targetFactor: Int): Int = origins[targetFactor]

    fun retainedReal(sourceId: SourceRealId): Int = retainedReals[sourceId.index]

    fun guardedCancellation(cancellation: Cancellation): Cancellation =
        (cancellation or containingCancellation).encodingPreparationScope()

    fun reconstruct(problem: Problem, sample: Sample, cancellation: Cancellation): Sample? {
        requireTarget(problem)
        require(sample.numBoolVars == target.numBoolVars && sample.numIntVars == target.numIntVars &&
            sample.numRealVars == target.numRealVars) { "float reconstruction needs a complete private assignment" }
        val stop = guardedCancellation(cancellation)
        if (stop()) return null
        val values = arrayOfNulls<BigFraction>(source.numRealVars)
        val approximate = DoubleArray(source.numRealVars)
        for (id in values.indices) {
            stop.charge(1)
            if (stop()) return null
            val grid = bySource[id]
            if (grid != null) {
                val exact = grid.value(sample.intValue(grid.coordinate.variable)) ?: return null
                values[id] = exact
                approximate[id] = exact.toDouble()
                if (!approximate[id].isFinite()) return null
            } else {
                val retained = retainedReals[id]
                values[id] = sample.exactReals?.get(retained)
                approximate[id] = sample.approximateRealValue(retained)
            }
        }
        val exact = if (values.all { it != null }) values.map { requireNotNull(it) } else null
        val algebraic = sample.algebraicReals?.let { original ->
            values.indices.map { id ->
                stop.charge(1)
                if (stop()) return null
                if (id in bySource) scalar(requireNotNull(values[id])) else original[retainedReals[id]]
            }
        }
        val symbolic = sample.symbolicReals?.let { original ->
            values.indices.map { id ->
                stop.charge(1)
                if (stop()) return null
                if (id in bySource) ExactRealValue(ExactRealExpression.Algebraic(scalar(requireNotNull(values[id]))))
                else original[retainedReals[id]]
            }
        }
        val bools = BooleanArray(source.numBoolVars)
        val ints = LongArray(source.numIntVars)
        for (id in bools.indices) {
            stop.charge(1)
            if (stop()) return null
            bools[id] = sample.boolValue(id)
        }
        for (id in ints.indices) {
            stop.charge(1)
            if (stop()) return null
            ints[id] = sample.intValue(id)
        }
        if (stop()) return null
        val result = Sample(bools, ints, approximate, exact, sample.exactInts?.take(source.numIntVars), algebraic, symbolic)
            .withSourceSemantics(source.semantics)
        return if (stop()) null else result
    }
}

// A rational leaf needs no arithmetic context with a separate work allowance.
private fun scalar(value: BigFraction): AlgebraicReal = AlgebraicReal(
    RationalPolynomial(listOf(value.negated(), BigFraction.ONE)), 1, RationalInterval(value, value),
)
