package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.magnitudeBitLength

internal class PolynomialFailureCursorState(val operation: Map<String, String>, val context: Map<String, String>)

internal class PolynomialFailureCapture {
    private var attached = false
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
    var firstRefusal: Map<String, String>? = null
        private set
    var firstSignConflict: Map<String, String>? = null
        private set
    var failures: Long = 0
        private set
    var repeatedFirstFailure: Long = 0
        private set
    private var firstKey: Map<String, String>? = null
    private var signOwner: Any? = null
    private var signCursor: PolynomialFailureCursorState? = null
    var repetitionComplete: Boolean = true
        private set
    var completedCopySteps: Long = 0
        private set
    var interruptedCapture: Boolean = false
        private set

    fun attach() {
        check(!attached) { "failure capture belongs to one query" }
        attached = true
    }

    fun cursorState(): PolynomialFailureCursorState = PolynomialFailureCursorState(operation, context)

    fun restoreCursor(state: PolynomialFailureCursorState) {
        operation = state.operation
        context = state.context
    }

    fun signCheck(
        owner: Any,
        premises: List<PolynomialAtom>,
        snapshot: Long,
        work: Long,
        arithmetic: PolynomialArithmetic,
    ) {
        if (signOwner === owner) {
            signCursor?.let(::restoreCursor)
            return
        }
        operation = capture(arithmetic) {
            put("stage", "partial-sign")
            put("snapshot", snapshot.toString())
            put("work", work.toString())
            atoms("signedPremises", premises)
        }
        signOwner = owner
        signCursor = cursorState()
    }

    fun signConflict(premises: List<PolynomialAtom>, snapshot: Long, work: Long, arithmetic: PolynomialArithmetic) {
        if (firstSignConflict != null) return
        val copied = capture(arithmetic) {
            put("snapshot", snapshot.toString())
            put("work", work.toString())
            atoms("signedPremises", premises)
        }
        firstSignConflict = copied + context.mapKeys { "context.${it.key}" } +
            operation.mapKeys { "operation.${it.key}" } + ("complete" to
            listOf(scope, context, operation, copied).all { it["complete"] == "true" }.toString())
    }

    fun refused(resource: String, arithmetic: PolynomialArithmetic) {
        if (firstRefusal == null) {
            val copied = capture(arithmetic) {
                put("resource", resource)
                for ((key, value) in operation) {
                    if (exhausted) break
                    put("operation.$key", value)
                }
                for ((key, value) in context) {
                    if (exhausted) break
                    put("context.$key", value)
                }
            }
            firstRefusal = copied + ("complete" to
                listOf(scope, context, operation, copied).all { it["complete"] == "true" }.toString())
        }
    }

    private fun capture(arithmetic: PolynomialArithmetic, block: FailureWriter.() -> Unit): Map<String, String> {
        val writer = FailureWriter(arithmetic) { if (completedCopySteps < Long.MAX_VALUE) completedCopySteps++ }
        var completed = false
        try {
            writer.block()
            val result = writer.finish()
            completed = true
            return result
        } finally {
            if (!completed) interruptedCapture = true
        }
    }

    fun context(
        root: PolynomialFormulaReduction,
        arithmetic: PolynomialArithmetic,
        region: PolynomialBooleanRegion? = null,
        reduction: PolynomialAffineReduction? = null,
    ) {
        context = capture(arithmetic) {
            put("root.requiresOriginalCheck", root.requiresOriginalCheck.toString())
            put("root.eliminatedCoordinates", root.eliminatedCount.toString())
            reduction?.let { put("region.eliminatedCoordinates", it.eliminatedCount.toString()) }
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
            for ((id, truth) in condition.bools) {
                if (exhausted) break
                put("condition.bool.$id", truth.toString())
            }
            for ((id, value) in condition.lower) {
                if (exhausted) break
                fraction("condition.lower.$id", value)
            }
            for ((id, value) in condition.upper) {
                if (exhausted) break
                fraction("condition.upper.$id", value)
            }
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
        zeroes: List<Polynomial> = emptyList(),
        nonzero: List<Polynomial> = emptyList(),
        completeFamily: Boolean? = null,
    ) {
        operation = capture(arithmetic) {
            put("stage", stage)
            put("snapshot", snapshot.toString())
            put("region", region.toString())
            put("work", work.toString())
            completeFamily?.let { put("completeFamily", it.toString()) }
            variable?.let { put("variable", it.toString()) }
            put("operandCount", operands.size.toString())
            for ((index, polynomial) in operands.withIndex()) {
                if (exhausted) break
                polynomial("operand.$index", polynomial)
            }
            required?.let { polynomial("requiredZero", it) }
            for ((index, polynomial) in zeroes.withIndex()) {
                if (exhausted) break
                polynomial("zero.$index", polynomial)
            }
            for ((index, polynomial) in nonzero.withIndex()) {
                if (exhausted) break
                polynomial("nonzero.$index", polynomial)
            }
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
            failed?.let {
                put("failedAtom", it.toString())
                put("failedRelation", formula.atoms[it].relation.name)
                polynomial("failedPolynomial", formula.atoms[it].polynomial)
            }
            region?.let {
                for ((id, truth) in it.atoms) {
                    if (exhausted) break
                    put("premise.$id", truth.toString())
                }
                for ((id, truth) in it.bools) {
                    if (exhausted) break
                    put("boolean.$id", truth.toString())
                }
            }
            for ((id, value) in point) {
                if (exhausted) break
                scalar("point.$id", value)
            }
        }
        if (key["complete"] != "true") repetitionComplete = false
        if (firstFailure != null && (region == null || firstCadFailure != null)) {
            if (key["complete"] == "true" && key == firstKey && repeatedFirstFailure < Long.MAX_VALUE) {
                repeatedFirstFailure++
            }
            return
        }
        val record = capture(arithmetic) {
            put("work", work.toString())
            put("rejection", if (region == null) "Boolean formula false" else "regional atom impossible at prefix")
            signs?.let {
                for (id in it.indices) {
                    if (exhausted) break
                    put("sign.$id", it[id].toString())
                }
            }
            region?.let {
                for ((id, truth) in it.atoms) {
                    if (exhausted) break
                    put("premise.$id", truth.toString())
                }
                for ((id, truth) in it.bools) {
                    if (exhausted) break
                    put("boolean.$id", truth.toString())
                }
            }
            atoms("active", formula.atoms)
        }
        val captured = record + key.mapKeys { "candidate.${it.key}" } +
            operation.mapKeys { "operation.${it.key}" } + context.mapKeys { "context.${it.key}" } +
            ("complete" to listOf(scope, context, operation, key, record).all { it["complete"] == "true" }.toString())
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
    private fun step() {
        arithmetic.step()
        copied()
    }
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
        for ((index, coefficient) in value.definingCoefficients.withIndex()) {
            fraction("$key.coefficient.$index", coefficient)
        }
    }

    fun finish(): Map<String, String> = values.toMap() + ("complete" to complete.toString())
}
