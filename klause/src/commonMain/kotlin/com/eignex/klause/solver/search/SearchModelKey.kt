package com.eignex.klause.solver.search

import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BigInt
import kotlin.reflect.KClass

/**
 * A contribution key tied to its value type. Equality and hash must be stable and agree on [valueType].
 * [snapshot] captures borrowed producer state once during assembly; its result must be immutable.
 * The contract remains open for extension-defined arrays, functions and source symbols.
 */
interface TypedSearchValueKey<V : Any> : SearchValueKey {
    /** Runtime check for legacy untyped contribution paths; generic arguments remain the extension's contract. */
    val valueType: KClass<V>

    /** Owned immutable value retained by the assembled model. Immutable values may be returned unchanged. */
    fun snapshot(value: V): V = value
}

/**
 * Identity key for one component-owned value. Keep the same key instance for publication and reads.
 * [capture] freezes borrowed state; its default requires an already immutable value.
 */
class SearchModelKey<V : Any>(
    override val valueType: KClass<V>,
    private val capture: (V) -> V = { it },
) : TypedSearchValueKey<V> {
    override fun snapshot(value: V): V = capture(value)
}

/** Authoritative arbitrary-precision value of a source integer, including finite CP contributions. */
data class SearchExactIntValue(val variable: SourceIntId) : TypedSearchValueKey<BigInt> {
    override val valueType: KClass<BigInt> get() = BigInt::class
}

/** Authoritative rational value of a source continuous variable. */
data class SearchExactRealValue(val variable: SourceRealId) : TypedSearchValueKey<BigFraction> {
    override val valueType: KClass<BigFraction> get() = BigFraction::class
}

/** Contribute a value of the type associated with [key]; session assembly captures its ownership. */
@Suppress("DEPRECATION")
fun <V : Any> SearchModel.contribute(key: TypedSearchValueKey<V>, value: V) {
    put(key, value)
}

@Suppress("UNCHECKED_CAST")
internal fun TypedSearchValueKey<*>.capture(value: Any): Any {
    require(valueType.isInstance(value)) { "model value does not match ${valueType.simpleName}" }
    val captured = (this as TypedSearchValueKey<Any>).snapshot(value)
    require(valueType.isInstance(captured)) { "model snapshot does not match ${valueType.simpleName}" }
    return captured
}
