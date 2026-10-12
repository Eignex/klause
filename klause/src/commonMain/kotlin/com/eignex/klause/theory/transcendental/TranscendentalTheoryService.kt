package com.eignex.klause.theory.transcendental

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.polynomial.ceilInteger
import com.eignex.klause.arithmetic.polynomial.exactInteger
import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.transcendental.RationalEnclosure
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qfnra.PolynomialCondition
import com.eignex.klause.theory.qfnra.QfNraParams
import com.eignex.klause.theory.qfnra.QfNraResult
import com.eignex.klause.theory.qfnra.solveQfNra
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter

internal class TranscendentalCondition(
    val rational: PolynomialCondition = PolynomialCondition(),
    lowerStrict: Set<Int> = emptySet(),
    upperStrict: Set<Int> = emptySet(),
) {
    val lowerStrict = lowerStrict.toSet()
    val upperStrict = upperStrict.toSet()
}

internal class TranscendentalWitness(
    bools: BooleanArray,
    integers: List<BigInt>,
    reals: List<ExactRealValue>,
    coordinates: List<ExactRealValue>,
) {
    private val booleanValues = bools.copyOf()
    private val integerValues = integers.toList()
    private val realValues = reals.toList()
    private val numericValues = coordinates.toList()
    val bools: BooleanArray get() = booleanValues.copyOf()
    val integers: List<BigInt> get() = integerValues.toList()
    val reals: List<ExactRealValue> get() = realValues.toList()
    val coordinates: List<ExactRealValue> get() = numericValues.toList()
}

internal sealed interface TranscendentalCheckResult {
    data class Witness(val value: TranscendentalWitness) : TranscendentalCheckResult
    data object Refuted : TranscendentalCheckResult
    data object Unknown : TranscendentalCheckResult
}

internal class TranscendentalTheoryService(
    private val source: TranscendentalProblem,
    private val cancellation: Cancellation = Cancellation.Never,
    private val maximumWork: Long? = 100000,
    maximumBits: Int = 65536,
    private val polynomialLimits: PolynomialLimits = PolynomialLimits(),
) {
    var work: Long = 0
        private set
    var representationDeclined: Boolean = false
        private set
    val exhausted: Boolean get() = maximumWork?.let { work >= it } == true || representationDeclined
    private var worker = Cancellation.Never
    private val meter = WorkMeter { units ->
        require(units >= 0)
        work += minOf(units, Long.MAX_VALUE - work)
        cancellation.charge(units)
    }
    private val stop = object : Cancellation {
        override fun isCancelled(): Boolean = cancellation() || worker() || exhausted
        override fun workMeter(): WorkMeter = meter
        override fun deadline() = cancellation.deadline()
    }
    private val transcendental = TranscendentalArithmetic(stop, maximumBits)
    private val arithmetic = ExactRealArithmetic(
        transcendental, AlgebraicArithmetic(PolynomialArithmetic(stop, polynomialLimits)),
    )

    init { require(maximumWork == null || maximumWork >= 0) }

    fun check(
        condition: TranscendentalCondition = TranscendentalCondition(),
        cancellation: Cancellation = Cancellation.Never,
        maximumProposals: Int = 8,
    ): TranscendentalCheckResult {
        require(maximumProposals >= 0)
        worker = cancellation
        return try {
            transcendental.step()
            query(condition, maximumProposals)
        } catch (_: TranscendentalInterrupted) {
            TranscendentalCheckResult.Unknown
        } catch (_: PolynomialInterrupted) {
            TranscendentalCheckResult.Unknown
        } catch (_: TranscendentalResourceLimit) {
            representationDeclined = true
            TranscendentalCheckResult.Unknown
        } catch (_: PolynomialResourceLimit) {
            representationDeclined = true
            TranscendentalCheckResult.Unknown
        } finally {
            worker = Cancellation.Never
        }
    }

    private fun query(condition: TranscendentalCondition, maximumProposals: Int): TranscendentalCheckResult {
        val pins = condition.rational
        require(pins.bools.keys.all { it in 0 until source.boolCount })
        require((pins.lower.keys + pins.upper.keys).all { it in 0 until source.numericCount })
        if (pins.lower.any { (id, lower) -> pins.upper[id]?.let { it < lower } == true }) {
            return TranscendentalCheckResult.Refuted
        }
        val boxes = pins.lower.mapNotNull { (id, lower) ->
            pins.upper[id]?.let { id to RationalEnclosure(lower, it) }
        }.toMap()
        val relaxation = TranscendentalRelaxationModel(source.numericCount, transcendental, boxes)
        val relaxed = relaxation.formula(source.formula)
        val strict = buildList {
            fun addBounds(ids: Set<Int>, values: Map<Int, BigFraction>, relation: PolynomialRelation) {
                for (id in ids) {
                    val value = requireNotNull(values[id])
                    add(PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(id)), relation,
                        PolynomialExpression.Constant.of(value.num, value.den)))
                }
            }
            addBounds(condition.lowerStrict, pins.lower, PolynomialRelation.GT)
            addBounds(condition.upperStrict, pins.upper, PolynomialRelation.LT)
        }
        val result = solveQfNra(
            PolynomialRealFormula.And(listOf(relaxed) + strict), source.boolCount, relaxation.numericCount,
            QfNraParams(stop, 4096, polynomialLimits), pins,
        )
        if (result is QfNraResult.Unsatisfiable) return TranscendentalCheckResult.Refuted
        val bools = (result as? QfNraResult.Satisfiable)?.assignment?.bools
            ?: BooleanArray(source.boolCount) { pins.bools[it] ?: false }
        if (result is QfNraResult.Satisfiable) {
            val proposed = List(source.numericCount) { id ->
                val value = result.assignment.realValue(id)
                if (id in source.integerCoordinates) {
                    arithmetic.rational(BigFraction.of(arithmetic.algebraic.ceilInteger(value), BIG_ONE))
                } else arithmetic.scalar(value)
            }
            certify(proposed, bools, condition)?.let { return TranscendentalCheckResult.Witness(it) }
        }
        val hints = listOf(0L, 1L, -1L, 2L, -2L, 4L, -4L, 8L)
        for (attempt in 0 until minOf(maximumProposals, hints.size)) {
            transcendental.step()
            val proposed = List(source.numericCount) { id ->
                val lower = pins.lower[id]
                val upper = pins.upper[id]
                var point = BigFraction.ofLong(hints[attempt])
                if (lower != null && upper != null) {
                    point = transcendental.divide(transcendental.add(lower, upper), BigFraction.ofLong(2))
                }
                else if (lower != null && point <= lower) point = lower + BigFraction.ONE
                else if (upper != null && point >= upper) point = upper - BigFraction.ONE
                if (id in source.integerCoordinates) point = BigFraction.of(point.ceilInteger(), BIG_ONE)
                arithmetic.rational(point)
            }
            certify(proposed, bools, condition)?.let { return TranscendentalCheckResult.Witness(it) }
        }
        return TranscendentalCheckResult.Unknown
    }

    private fun certify(
        proposal: List<ExactRealValue>,
        bools: BooleanArray,
        condition: TranscendentalCondition,
    ): TranscendentalWitness? {
        val values = repairTranscendentalCandidate(source.formula, proposal, bools, arithmetic)
        val pins = condition.rational
        if (pins.bools.any { (id, value) -> bools[id] != value }) return null
        fun obey(bounds: Map<Int, BigFraction>, strict: Set<Int>, lower: Boolean): Boolean = bounds.all { (id, value) ->
            val comparison = arithmetic.compare(values[id], arithmetic.rational(value))
            comparison != null && if (lower) comparison > 0 || (comparison == 0 && id !in strict)
            else comparison < 0 || (comparison == 0 && id !in strict)
        }
        if (!obey(pins.lower, condition.lowerStrict, true) || !obey(pins.upper, condition.upperStrict, false)) {
            return null
        }
        val integers = source.integerCoordinates.map { id ->
            val scalar = values[id].algebraic ?: return null
            arithmetic.algebraic.exactInteger(scalar) ?: return null
        }
        if (TranscendentalSourceCheck(arithmetic, values, bools).check(source.formula) != SourceTruth.TRUE) return null
        return TranscendentalWitness(bools, integers, source.realCoordinates.map(values::get), values)
    }
}
