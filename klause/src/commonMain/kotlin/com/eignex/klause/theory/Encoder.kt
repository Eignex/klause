package com.eignex.klause.theory

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import kotlin.jvm.JvmInline

/** A consumer's relation language, independent of its numerical execution policy. */
@JvmInline
value class EncodingTarget(
    /** Stable semantic language identifier. */
    val id: String,
) {
    init { require(id.isNotBlank()) }

    /** Built-in consumer languages; extensions can declare their own identifiers. */
    companion object {
        /** Exact linear relations with Boolean structure and strictness. */
        val LINEAR: EncodingTarget = EncodingTarget("linear")
        /** Integer difference relations with combined graph safety admission. */
        val DIFFERENCE: EncodingTarget = EncodingTarget("difference")
        /** Exact polynomial relations beside a retained finite projection. */
        val POLYNOMIAL: EncodingTarget = EncodingTarget("polynomial")
        internal val entries = listOf(LINEAR, DIFFERENCE, POLYNOMIAL)
    }
}

/** How a selected consumer can represent the whole source relation. */
enum class EncoderSupport {
    /** The consumer installs the relation directly. */
    DIRECT,
    /** A private encoding preserves the complete source relation. */
    EQUIVALENT,
    /** Equivalence requires the declared invocation conditions. */
    CONDITIONED,
    /** Only an outer relaxation is available; it cannot enforce the source. */
    RELAXATION_ONLY,
    /** No representation is available. */
    UNSUPPORTED,
}

/** A preparation or installation decline; none of these outcomes refutes a source. */
enum class EncoderDecline {
    /** The consumer has no complete encoding. */
    UNSUPPORTED,
    /** Required captured source conditions are missing. */
    CONDITIONS,
    /** Cancellation or a containing resource allowance interrupted installation. */
    RESOURCE,
}

/** Selects an immutable encoder for one factor and consumer language. */
fun interface EncoderProvider {
    /** Select without allocating live theory state or changing the factor. */
    fun select(factor: Factor, target: EncodingTarget): Encoder
}

/**
 * An immutable prepared relation installer. Equivalence, stable borrowed payloads and complete
 * source attribution are extension obligations. Mutable work belongs to the receiving component.
 */
class Encoder(
    /** Representation authority; relaxation support never authorizes source ownership. */
    val support: EncoderSupport,
    requiredIntegers: List<SourceIntId> = emptyList(),
    requiredBooleans: Map<SourceBoolId, Boolean> = emptyMap(),
    private val installer: (Factor, TheoryEncoding) -> EncoderDecline? = { _, _ -> EncoderDecline.UNSUPPORTED },
) {
    private val integerConditions = requiredIntegers.toList()
    private val booleanConditions = requiredBooleans.toMap()

    /** Source coordinates that must be fixed throughout the installation's invocation scope. */
    val requiredIntegers: List<SourceIntId> get() = integerConditions.toList()

    /** Boolean source pins required for equivalence throughout the invocation scope. */
    val requiredBooleans: Map<SourceBoolId, Boolean> get() = booleanConditions.toMap()

    init {
        require(support == EncoderSupport.CONDITIONED || (integerConditions.isEmpty() && booleanConditions.isEmpty()))
    }

    internal fun remapped(mapping: VarRemap): Encoder {
        if (integerConditions.isEmpty() && booleanConditions.isEmpty()) return this
        return Encoder(support,
            integerConditions.map { requireNotNull(mapping.int(it)) { "removed encoding condition" } },
            booleanConditions.mapKeys { requireNotNull(mapping.bool(it.key)) { "removed encoding condition" } },
            installer,
        )
    }

    /** Whether this descriptor enforces the unconditioned source relation. */
    val enforcesSource: Boolean get() = support == EncoderSupport.DIRECT || support == EncoderSupport.EQUIVALENT

    /** Install into a private receiving scope; the caller commits it only when no decline is returned. */
    fun install(factor: Factor, encoding: TheoryEncoding): EncoderDecline? {
        if (!enforcesSource && support != EncoderSupport.CONDITIONED) return EncoderDecline.UNSUPPORTED
        if (integerConditions.any { encoding.fixedInteger(it) == null } ||
            booleanConditions.any { (id, value) -> encoding.fixedBoolean(id) != value }
        ) return EncoderDecline.CONDITIONS
        if (encoding.cancellation()) return EncoderDecline.RESOURCE
        return installer(factor, encoding) ?: EncoderDecline.RESOURCE.takeIf { encoding.cancellation() }
    }
}

/**
 * A component-owned installation scope for one attributed factor. All emitted constraints belong
 * to its full relation; private auxiliaries must be reconstructed before source publication.
 */
interface TheoryEncoding {
    /** Selected consumer language. */
    val target: EncodingTarget
    /** Original factor index in the retained canonical source. */
    val sourceFactorIndex: Int
    /** Containing invocation allowance; installation cannot create a fresh budget. */
    val cancellation: Cancellation

    /** Exact captured condition, absent when the source integer is not fixed in this scope. */
    fun fixedInteger(id: SourceIntId): BigInt?
    /** Captured source Boolean pin, absent when the scope does not require that truth value. */
    fun fixedBoolean(id: SourceBoolId): Boolean?
    /** Install one complete linear conjunction or disjunction with unchanged source coordinates. */
    fun linear(form: LinearForm)
    /** Install a neutral polynomial relation, preserving guards and original attribution. */
    fun polynomial(formula: PolynomialRealFormula)
    /** Map a finite source integer to its private polynomial coordinate. */
    fun integer(id: SourceIntId): PolynomialExpression
    /** Map a source real to its polynomial coordinate. */
    fun real(id: SourceRealId): PolynomialExpression
    /** Encode a Boolean choice with an attributed private auxiliary and both defining guards. */
    fun conditional(
        condition: PolynomialRealFormula,
        whenTrue: PolynomialExpression,
        whenFalse: PolynomialExpression,
    ): PolynomialExpression
}
