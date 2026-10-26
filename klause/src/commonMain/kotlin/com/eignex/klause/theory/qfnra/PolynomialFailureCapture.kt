package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialFailureCapture {
    var scope: Map<String, String> = emptyMap()
        private set
    var operation: Map<String, String> = emptyMap()
        private set
    var context: Map<String, String> = emptyMap()
        private set
    var firstFailure: Map<String, String>? = null
        private set
    var firstCadFailure: Map<String, String>? = null
        private set
    var failures: Long = 0
        private set
    var repeatedFirstFailure: Long = 0
        private set
    private var firstKey: Map<String, String>? = null
    var repetitionComplete: Boolean = true
        private set
    var completedCopySteps: Long = 0
        private set
    var interruptedCapture: Boolean = false
        private set

    private fun capture(arithmetic: PolynomialArithmetic, block: FailureWriter.() -> Unit): Map<String, String> {
        interruptedCapture = true
        val writer = FailureWriter(arithmetic) { if (completedCopySteps < Long.MAX_VALUE) completedCopySteps++ }
        writer.block()
        val result = writer.finish()
        interruptedCapture = false
        return result
    }

    fun context(
        root: PolynomialFormulaReduction,
        arithmetic: PolynomialArithmetic,
        region: PolynomialBooleanRegion? = null,
        reduction: PolynomialAffineReduction? = null,
    ) {
        context = capture(arithmetic) {
            root.captureReconstruction(this)
            region?.let {
                for ((id, truth) in it.atoms) {
                    if (exhausted) break
                    put("sourcePremise.$id", truth.toString())
                }
                for ((id, truth) in it.bools) {
                    if (exhausted) break
                    put("sourceBoolean.$id", truth.toString())
                }
            }
            reduction?.captureReconstruction(this)
            atoms("snapshot", root.formula.atoms)
        }
    }

    fun scope(
        formula: PolynomialFormula,
        condition: PolynomialCondition,
        arithmetic: PolynomialArithmetic,
    ) {
        scope = capture(arithmetic) {
            for ((id, truth) in condition.bools) { if (exhausted) break; put("condition.bool.$id", truth.toString()) }
            for ((id, value) in condition.lower) { if (exhausted) break; fraction("condition.lower.$id", value) }
            for ((id, value) in condition.upper) { if (exhausted) break; fraction("condition.upper.$id", value) }
            atoms("normalized", formula.atoms)
        }
    }

    fun operation(
        stage: String,
        snapshot: Long,
        region: Long,
        work: Long,
        arithmetic: PolynomialArithmetic,
        operands: List<Polynomial> = emptyList(),
        variable: Int? = null,
        required: Polynomial? = null,
    ) {
        operation = capture(arithmetic) {
            put("stage", stage)
            put("snapshot", snapshot.toString())
            put("region", region.toString())
            put("work", work.toString())
            variable?.let { put("variable", it.toString()) }
            put("operandCount", operands.size.toString())
            for ((index, polynomial) in operands.withIndex()) {
                if (exhausted) break
                polynomial("operand.$index", polynomial)
            }
            required?.let { polynomial("requiredZero", it) }
        }
    }

    fun rejected(
        formula: PolynomialFormula,
        region: PolynomialBooleanRegion?,
        point: Map<Int, AlgebraicReal>,
        failed: Int?,
        snapshot: Long,
        work: Long,
        arithmetic: PolynomialArithmetic,
        signs: IntArray? = null,
    ) {
        if (failures < Long.MAX_VALUE) failures++
        if (failures > 256 && (region == null || firstCadFailure != null)) {
            repetitionComplete = false
            return
        }
        // Comparing a bounded exact representation supplies repetition evidence, not real equality or proof.
        val key = capture(arithmetic) {
            put("snapshot", snapshot.toString())
            failed?.let { put("failedAtom", it.toString()) }
            for ((id, value) in point) { if (exhausted) break; scalar("point.$id", value) }
        }
        if (firstFailure != null && (region == null || firstCadFailure != null)) {
            if (key["complete"] == "true" && key == firstKey && repeatedFirstFailure < Long.MAX_VALUE) {
                repeatedFirstFailure++
            }
            return
        }
        val record = capture(arithmetic) {
            put("work", work.toString())
            put("rejection", if (region == null) "Boolean formula false" else "regional atom impossible at prefix")
            signs?.let { for (id in it.indices) { if (exhausted) break; put("sign.$id", it[id].toString()) } }
            region?.let {
                for ((id, truth) in it.atoms) { if (exhausted) break; put("premise.$id", truth.toString()) }
                for ((id, truth) in it.bools) { if (exhausted) break; put("boolean.$id", truth.toString()) }
            }
            atoms("active", formula.atoms)
        }
        val captured = record + key.mapKeys { "candidate.${it.key}" } +
            operation.mapKeys { "operation.${it.key}" } + context.mapKeys { "context.${it.key}" }
        if (firstFailure == null) {
            firstKey = key
            firstFailure = captured
        }
        if (region != null && firstCadFailure == null) firstCadFailure = captured
    }
}

internal class FailureWriter(
    private val arithmetic: PolynomialArithmetic,
    private val copied: () -> Unit = {},
) {
    private fun step() { arithmetic.step(); copied() }
    private val values = LinkedHashMap<String, String>()
    private var remaining = 16_384
    private var complete = true
    val exhausted: Boolean get() = !complete

    fun put(key: String, value: String) {
        step()
        if (key.length + value.length > remaining || values.size >= 512) {
            complete = false
            return
        }
        remaining -= key.length + value.length
        values[key] = value
    }

    fun fraction(key: String, value: BigFraction) {
        step()
        if (value.num.magnitudeBitLength() > 4096 || value.den.magnitudeBitLength() > 4096) {
            complete = false
        } else put(key, value.toString())
    }

    fun polynomial(key: String, value: Polynomial) {
        step()
        put("$key.terms", value.termCount.toString())
        if (value.termCount > 128) {
            complete = false
            return
        }
        for ((index, term) in value.terms.withIndex()) {
            if (exhausted) break
            step()
            fraction("$key.$index.coefficient", term.coefficient)
            if (term.monomial.variableCount > 64) {
                complete = false
                continue
            }
            val variables = term.monomial.variables
            for (variable in variables) put("$key.$index.x$variable", term.monomial.exponent(variable).toString())
        }
    }

    fun atoms(key: String, atoms: List<PolynomialAtom>) {
        put("$key.count", atoms.size.toString())
        for ((index, atom) in atoms.take(128).withIndex()) {
            if (exhausted) break
            put("$key.$index.relation", atom.relation.name)
            polynomial("$key.$index.polynomial", atom.polynomial)
        }
        if (atoms.size > 128) complete = false
    }

    fun scalar(key: String, value: AlgebraicReal) {
        step()
        if (value.definingPolynomial.degree > 128) {
            complete = false
            return
        }
        put("$key.root", value.rootIndex.toString())
        fraction("$key.lower", value.lowerBound)
        fraction("$key.upper", value.upperBound)
        for ((index, coefficient) in value.definingCoefficients.withIndex()) fraction("$key.coefficient.$index", coefficient)
    }

    fun finish(): Map<String, String> = values.toMap() + ("complete" to complete.toString())
}
