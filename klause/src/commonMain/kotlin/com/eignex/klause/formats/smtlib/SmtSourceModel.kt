package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.pipeline.SourceWitnessValidator
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException

/** A source sort, independent of the scalar coordinates used to solve its encoding. */
sealed interface SmtSort {
    /** Boolean values. */
    data object Bool : SmtSort
    /** Unbounded mathematical integers. */
    data object Int : SmtSort
    /** Exact mathematical reals. */
    data object Real : SmtSort
    /** Fixed-width words. */
    data class BitVector(
        /** Positive number of bits. */
        val width: kotlin.Int,
    ) : SmtSort {
        init { require(width > 0) }
    }
    /** Extensional arrays. */
    data class Array(
        /** Index sort. */
        val index: SmtSort,
        /** Stored value sort. */
        val element: SmtSort,
    ) : SmtSort
    /** A named source sort and its sort arguments. */
    data class Uninterpreted(
        /** Source spelling of the sort constructor. */
        val name: String,
        /** Applied sort arguments. */
        val arguments: List<SmtSort> = emptyList(),
    ) : SmtSort
}

/** An original declaration, including functions with nonempty [parameters]. */
data class SmtSourceDeclaration(
    /** Original source spelling. */
    val name: String,
    /** Function argument sorts; empty for constants. */
    val parameters: List<SmtSort>,
    /** Constant or function result sort. */
    val result: SmtSort,
)

/** A reconstructed value in the source language. Real terms retain certified SMT syntax. */
sealed interface SmtValue {
    /** Compare composite values through their source semantics; null withholds an unavailable comparison. */
    fun semanticEquals(other: SmtValue, values: SmtModelValues): kotlin.Boolean? = null
    /** A Boolean value. */
    data class Boolean(
        /** Mathematical truth value. */
        val value: kotlin.Boolean,
    ) : SmtValue
    /** An arbitrary-precision integer in decimal notation. */
    data class Integer(
        /** Exact signed decimal digits. */
        val value: String,
    ) : SmtValue
    /** An exact rational, algebraic or symbolic real term in SMT syntax. */
    data class Real(
        /** Certified exact SMT term. */
        val term: String,
    ) : SmtValue
    /** Most-significant bit first. */
    data class BitVector(
        /** Binary digits in most-significant-bit order. */
        val bits: String,
    ) : SmtValue {
        init { require(bits.isNotEmpty() && bits.all { it == '0' || it == '1' }) }
    }
    /** A value of a free source sort. */
    data class Uninterpreted(
        /** Declared free sort. */
        val sort: SmtSort,
        /** Abstract model-domain symbol. */
        val identity: String,
    ) : SmtValue
    /** A finite function table with its value outside the table. */
    data class Function(
        /** Function argument sorts. */
        val parameters: List<SmtSort>,
        /** Argument tuples and their results. */
        val entries: List<Pair<List<SmtValue>, SmtValue>>,
        /** Result for tuples absent from [entries]. */
        val default: SmtValue,
    ) : SmtValue
    /** A constructor application for a composite source value. */
    data class Application(
        /** Source constructor symbol. */
        val constructor: String,
        /** Constructor field values. */
        val arguments: List<SmtValue>,
    ) : SmtValue
}

/** Named access to retained coordinates and composed reconstruction bindings. */
interface SmtModelValues {
    /** Current solve or reconstruction allowance, shared by all source checks. */
    val cancellation: ExecutionControl get() = ExecutionControl.Never
    /** Charge and poll the current witness-check allowance, independent of admission. */
    fun checkpoint(units: Long = 1)
    /** Read a retained Boolean coordinate by its encoding name. */
    fun boolean(name: String): Boolean
    /** Read a retained integer coordinate as an exact decimal. */
    fun integer(name: String): String
    /** Read a retained real coordinate as a certified SMT term. */
    fun real(name: String): String
    /** Reconstruct another named source or encoding binding. */
    fun value(name: String): SmtValue
    /** Native certified real authority, or null when a callback supplies no exact representation. */
    fun exactReal(name: String): ExactRealValue? = (value(name) as? SmtValue.Real)?.let { exactReal(it) }
    /** Native authority for a reconstructed real, with exact rational syntax as a fallback. */
    fun exactReal(value: SmtValue.Real): ExactRealValue? = smtRationalValue(value.term, cancellation)
    /** Certified semantic equality; null withholds an undecided comparison. */
    fun equal(left: String, right: String): Boolean? = equal(value(left), value(right))
    /** Certified equality including reconstructed composite fields. */
    fun equal(left: SmtValue, right: SmtValue): Boolean? = smtValuesEqual(this, left, right)
}

/** A source-owned recipe which never depends on a mutable compiler environment. */
fun interface SmtReconstruction {
    /** Reconstruct a value from one checked solver assignment. */
    fun reconstruct(values: SmtModelValues): SmtValue

    /** A scalar coordinate retained by name. */
    data class Scalar(
        /** Retained encoding coordinate name. */
        val name: String,
        /** Coordinate's scalar sort. */
        val sort: SmtSort,
    ) : SmtReconstruction {
        override fun reconstruct(values: SmtModelValues): SmtValue = when (sort) {
            SmtSort.Bool -> SmtValue.Boolean(values.boolean(name))
            SmtSort.Int -> SmtValue.Integer(values.integer(name))
            SmtSort.Real -> SmtValue.Real(values.real(name))
            else -> values.value(name)
        }
    }

    /** Boolean coordinates in least-significant-bit order. */
    data class Bits(
        /** Retained Boolean names in least-significant-bit order. */
        val names: List<String>,
    ) : SmtReconstruction {
        init { require(names.isNotEmpty()) }
        override fun reconstruct(values: SmtModelValues): SmtValue = SmtValue.BitVector(
            names.asReversed().joinToString("") { if (values.boolean(it)) "1" else "0" },
        )
    }
}

/** An original declaration and its private encoding recipe. */
data class SmtSourceBinding(
    /** Original declaration being reconstructed. */
    val declaration: SmtSourceDeclaration,
    /** Owned encoding recipe. */
    val reconstruction: SmtReconstruction,
)

/** An original-source obligation with immutable captures, evaluated under each candidate's allowance. */
fun interface SmtSourceCheck {
    /** Return whether the original obligation holds for the reconstructed source values. */
    fun check(values: SmtModelValues): Boolean
}

/** A model containing only original declarations, in source order. */
data class SmtSourceModel(
    /** Original declarations paired with their values in source order. */
    val values: List<Pair<SmtSourceDeclaration, SmtValue>>,
)

/** The original typed source, retained through all encoding passes and solving. */
class SmtSourceSnapshot internal constructor(internal val typed: SmtTypedSource) {
    /** Original declarations, in source order. */
    val declarations: List<SmtSourceDeclaration> get() = typed.declarations.toList()
}

internal fun scalarSmtBindings(
    integers: Map<String, Int>,
    booleans: Map<String, Int>,
    reals: Map<String, Int>,
): List<SmtSourceBinding> = buildList {
    for ((sort, names) in listOf(SmtSort.Int to integers, SmtSort.Bool to booleans, SmtSort.Real to reals)) {
        for (name in names.keys) add(SmtSourceBinding(SmtSourceDeclaration(name, emptyList(), sort),
            SmtReconstruction.Scalar(name, sort)))
    }
}

private fun <T> withSmtModelValues(
    problem: SmtLibProblem,
    boolean: (Int) -> Boolean,
    integer: (Int) -> String,
    real: (Int) -> String,
    cancellation: ExecutionControl,
    exactReal: ((Int) -> ExactRealValue?)? = null,
    action: (SmtModelValues, () -> Unit) -> T,
): T {
    fun checkpoint(units: Long = 1) {
        cancellation.charge(units)
        if (cancellation()) throw CancellationException("SMT model reconstruction interrupted")
    }
    val booleans = problem.encodingBoolVarNames.mapKeys { checkpoint(); polynomialSymbol(it.key) }
    val integers = problem.encodingIntVarNames.mapKeys { checkpoint(); polynomialSymbol(it.key) }
    val reals = problem.encodingRealVarNames.mapKeys { checkpoint(); polynomialSymbol(it.key) }
    val recipes = problem.privateBindings.mapKeys { polynomialSymbol(it.key) }.toMutableMap()
    for (binding in problem.sourceBindings) recipes[polynomialSymbol(binding.declaration.name)] = binding.reconstruction
    val cache = HashMap<String, SmtValue>()
    val realAuthorities = HashMap<String, ExactRealValue>()
    val active = HashSet<String>()
    val sourceCancellation = cancellation
    val nativeReal = exactReal
    val values = object : SmtModelValues {
        override val cancellation: ExecutionControl = sourceCancellation
        override fun checkpoint(units: Long) {
            require(units >= 0)
            cancellation.charge(units)
            if (cancellation()) throw CancellationException("SMT model reconstruction interrupted")
        }
        override fun boolean(name: String): Boolean {
            checkpoint()
            return boolean(booleans.getValue(polynomialSymbol(name)))
        }
        override fun integer(name: String): String {
            checkpoint()
            return integer(integers.getValue(polynomialSymbol(name)))
        }
        override fun real(name: String): String {
            checkpoint()
            val id = reals.getValue(polynomialSymbol(name))
            val term = real(id)
            nativeReal?.invoke(id)?.let { realAuthorities[term] = it }
            return term
        }
        override fun exactReal(name: String): ExactRealValue? {
            checkpoint()
            val id = reals[polynomialSymbol(name)]
            if (id != null) nativeReal?.invoke(id)?.let { return it }
            return (value(name) as? SmtValue.Real)?.let { exactReal(it) }
        }
        override fun exactReal(value: SmtValue.Real): ExactRealValue? =
            realAuthorities[value.term] ?: smtRationalValue(value.term, cancellation)
        override fun value(name: String): SmtValue {
            checkpoint()
            val canonical = polynomialSymbol(name)
            cache[canonical]?.let { return it }
            check(active.add(canonical)) { "cyclic SMT reconstruction for '$name'" }
            val result = try {
                recipes[canonical]?.reconstruct(this) ?: when (canonical) {
                    in booleans -> SmtValue.Boolean(boolean(name))
                    in integers -> SmtValue.Integer(integer(name))
                    in reals -> SmtValue.Real(real(name))
                    else -> error("missing SMT reconstruction for '$name'")
                }
            } finally { active.remove(canonical) }
            cache[canonical] = result
            return result
        }
    }
    return action(values) { checkpoint() }
}

internal fun reconstructSmtModel(
    problem: SmtLibProblem,
    boolean: (Int) -> Boolean,
    integer: (Int) -> String,
    real: (Int) -> String,
    cancellation: ExecutionControl,
    exactReal: ((Int) -> ExactRealValue?)? = null,
): SmtSourceModel = withSmtModelValues(problem, boolean, integer, real, cancellation, exactReal) { values, checkpoint ->
    val model = SmtSourceModel(problem.sourceBindings.map { binding ->
        checkpoint()
        binding.declaration to values.value(binding.declaration.name)
    })
    checkSmtSource(problem, values, checkpoint)
    model
}

private fun checkSmtSource(problem: SmtLibProblem, values: SmtModelValues, checkpoint: () -> Unit) {
    for (check in problem.sourceChecks) {
        checkpoint()
        if (!check.check(values)) throw SmtSourceWitnessRejected()
    }
    checkpoint()
}

private class SmtSourceWitnessRejected : IllegalArgumentException(
    "reconstructed SMT model failed original-source checking",
)

internal fun SmtLibProblem.withSourceWitnessValidation(): SmtLibProblem {
    if (sourceChecks.isEmpty()) return this
    val validator = SourceWitnessValidator { assignment, cancellation ->
        try {
            withSmtModelValues(this, assignment::boolValue, assignment::intValue,
                { assignment.smtRealValue(it, cancellation) },
                cancellation, { assignment.smtExactRealValue(it, cancellation) }) { values, checkpoint ->
                    checkSmtSource(this, values, checkpoint)
                }
            SourceEvidenceCheck.Checked
        } catch (_: SmtSourceWitnessRejected) {
            SourceEvidenceCheck.Rejected("SMT reconstructed value violates its original source")
        } catch (_: SmtSourceCheckingUnavailable) {
            SourceEvidenceCheck.Unavailable("SMT original-source comparison is not certified")
        }
    }
    return copy(source = source.withWitnessValidator(validator))
}

/** Render a source sort using SMT-LIB syntax. */
fun SmtSort.toSmtLib(): String = when (this) {
    SmtSort.Bool -> "Bool"
    SmtSort.Int -> "Int"
    SmtSort.Real -> "Real"
    is SmtSort.BitVector -> "(_ BitVec $width)"
    is SmtSort.Array -> "(Array ${index.toSmtLib()} ${element.toSmtLib()})"
    is SmtSort.Uninterpreted -> if (arguments.isEmpty()) name
        else "($name ${arguments.joinToString(" ") { it.toSmtLib() }})"
}

/** Render one reconstructed value using SMT-LIB syntax. */
fun SmtValue.toSmtLib(): String = when (this) {
    is SmtValue.Boolean -> value.toString()
    is SmtValue.Integer -> if (value.startsWith('-')) "(- ${value.drop(1)})" else value
    is SmtValue.Real -> term
    is SmtValue.BitVector -> "#b$bits"
    is SmtValue.Uninterpreted -> "(as $identity ${sort.toSmtLib()})"
    is SmtValue.Application -> if (arguments.isEmpty()) constructor
        else "($constructor ${arguments.joinToString(" ") { it.toSmtLib() }})"
    is SmtValue.Function -> error("a function value requires its declaration")
}

/** Render original declarations without exposing private encoding names. */
fun SmtSourceModel.toSmtLib(): String = buildString {
    append("(\n")
    for ((declaration, value) in values) {
        append("  (define-fun ${declaration.name} (")
        declaration.parameters.forEachIndexed { index, sort ->
            if (index > 0) append(' ')
            append("(x$index ${sort.toSmtLib()})")
        }
        append(") ${declaration.result.toSmtLib()} ")
        if (value is SmtValue.Function) {
            for ((arguments, result) in value.entries) {
                append("(ite (and")
                for ((index, argument) in arguments.withIndex()) append(" (= x$index ${argument.toSmtLib()})")
                append(") ${result.toSmtLib()} ")
            }
            append(value.default.toSmtLib())
            repeat(value.entries.size) { append(')') }
        } else append(value.toSmtLib())
        append(")\n")
    }
    append(')')
}
