package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.MultipleSpec
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.PresenceSpec
import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.ast.SearchAnnotation
import com.eignex.klause.ast.SetSpec
import com.eignex.klause.ast.ValSearchStrategy
import com.eignex.klause.ast.VarSearchStrategy
import com.eignex.skema.Schema
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** Suffix used to name the synthetic presence Boolean backing every opt variable. */
internal const val PRESENCE_SUFFIX: String = "__present"

/** Builds the canonical presence-variable name for an opt var named [varName]. */
internal fun presenceName(varName: String): String = "$varName$PRESENCE_SUFFIX"

/** Default bucket count for [VariableSchema.floatVar] when the caller doesn't specify one.
 *  10-bit precision is enough for typical config-style fractions; bump it explicitly when
 *  the constraint set needs finer granularity. */
const val DEFAULT_FLOAT_BUCKETS: Int = 1024

/**
 * Property-delegate base class for declaring a typed klause schema. Backed by
 * [com.eignex.skema.Schema] so the same singleton produces typed compile-time handles
 * and a wire-serializable [com.eignex.skema.SchemaDef] of [SchemaEntry] rows.
 *
 * Constraint delegates evaluate their build lambda *during class initialization*, so any
 * handle referenced inside `constraint { ... }` must be declared earlier in the class.
 */
abstract class VariableSchema : Schema<SchemaEntry>() {

    protected fun boolVar() = register(BoolSpec) { BoolHandle(it) }

    protected fun nominal(vararg labels: String) = labels.toList().let { ls ->
        register(NominalSpec(ls)) { NominalHandle(it, ls) }
    }

    protected fun intVar(min: Int, max: Int) =
        register(IntSpec(min, max)) { IntHandle(it, min, max) }

    protected fun floatVar(min: Double, max: Double, buckets: Int = DEFAULT_FLOAT_BUCKETS) =
        register(FloatSpec(min, max, buckets)) { FloatHandle(it, min, max, buckets) }

    /**
     * Optional integer variable: declares a presence Boolean named `<prop>__present` alongside
     * the value variable. Compare via [OptIntHandle]'s opt-aware operators to get MiniZinc's
     * "undefined → false" semantics, or coerce to a regular [com.eignex.klause.ast.IntExpr]
     * with [OptIntHandle.valueOr].
     */
    protected fun optIntVar(min: Int, max: Int) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, OptIntHandle>> { thisRef, prop ->
            val pName = presenceName(prop.name)
            thisRef.add(pName, PresenceSpec(prop.name))
            thisRef.add(prop.name, IntSpec(min, max))
            val handle = OptIntHandle(
                name = prop.name,
                present = BoolHandle(pName),
                value = IntHandle(prop.name, min, max),
            )
            ReadOnlyProperty { _, _ -> handle }
        }

    /**
     * Optional Boolean variable: declares a presence bool plus the value bool. In a Boolean
     * context the handle coerces to `present ∧ value`; access [OptBoolHandle.present] /
     * [OptBoolHandle.value] for direct manipulation.
     */
    protected fun optBoolVar() =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, OptBoolHandle>> { thisRef, prop ->
            val pName = presenceName(prop.name)
            thisRef.add(pName, PresenceSpec(prop.name))
            thisRef.add(prop.name, BoolSpec)
            val handle = OptBoolHandle(
                name = prop.name,
                present = BoolHandle(pName),
                value = BoolHandle(prop.name),
            )
            ReadOnlyProperty { _, _ -> handle }
        }

    /**
     * Set variable over an integer universe. Internally allocates one indicator bool per
     * universe element; the decoder reads those indicators back to a [Set]<Int>. Use the
     * infix operators in [com.eignex.klause.schema.SetHandles] to compose constraints.
     *
     * Example: `val chosen by setVar(0..9)` declares a set drawn from `{0, …, 9}`.
     */
    protected fun setVar(universe: IntRange) = setVar(universe.toList())

    /** Set variable over a non-contiguous integer universe. */
    protected fun setVar(universe: List<Int>) =
        register(SetSpec(universe.distinct().sorted())) { IntSetHandle(it, universe.distinct().sorted()) }

    /**
     * Set variable over a nominal universe of labels. The labels are fixed at schema
     * construction; the decoder returns a [Set]<String> of currently-selected labels.
     */
    protected fun multiple(vararg labels: String) = labels.toList().let { ls ->
        register(MultipleSpec(ls)) { NominalSetHandle(it, ls) }
    }

    /**
     * Optional nominal variable: declares a presence bool plus the underlying one-hot nominal.
     * Comparisons via [OptNominalHandle.eq] / [OptNominalHandle.ne] fold the presence guard in.
     */
    protected fun optNominal(vararg labels: String) = labels.toList().let { ls ->
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, OptNominalHandle>> { thisRef, prop ->
            val pName = presenceName(prop.name)
            thisRef.add(pName, PresenceSpec(prop.name))
            thisRef.add(prop.name, NominalSpec(ls))
            val handle = OptNominalHandle(
                name = prop.name,
                present = BoolHandle(pName),
                value = NominalHandle(prop.name, ls),
            )
            ReadOnlyProperty { _, _ -> handle }
        }
    }

    protected fun constraint(build: () -> BoolExpr) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, NamedConstraint>> { thisRef, prop ->
            val nc = NamedConstraint(build())
            thisRef.add(prop.name, nc)
            ReadOnlyProperty { _, _ -> nc }
        }

    /** Anonymous form of [constraint]; the entry is registered under a synthetic
     *  `__c<n>` name. Use when the constraint has no natural identifier and you
     *  don't need to reference it by handle elsewhere. */
    protected fun constraint(expr: BoolExpr) {
        add("__c${anonCounter++}", NamedConstraint(expr))
    }

    /**
     * Schema-level search annotation: declare branching strategy alongside variables.
     * Mirrors MiniZinc's `solve :: int_search(vars, var_strategy, value_strategy, complete)`
     * at the schema-DSL layer — the compiler reads the annotation and bundles it into
     * [com.eignex.klause.compile.CompiledProblem.defaultBacktrackParams].
     *
     * At most one search annotation per schema. Subsequent calls overwrite the previous
     * (last-write-wins) so subclasses can refine a base schema's choice.
     *
     * Example:
     * ```kotlin
     * class MySchema : VariableSchema() {
     *     val x by intVar(0, 9)
     *     val y by intVar(0, 9)
     *     // …
     *     init {
     *         search(
     *             variableStrategy = VarSearchStrategy.SmallestDomain,
     *             valueStrategy = ValSearchStrategy.Min,
     *             phaseSaving = true,
     *             lubyRestartBase = 100,
     *         )
     *     }
     * }
     * ```
     */
    protected fun search(
        variableStrategy: VarSearchStrategy = VarSearchStrategy.Default,
        valueStrategy: ValSearchStrategy = ValSearchStrategy.Default,
        phaseSaving: Boolean = false,
        lubyRestartBase: Long? = null,
        maxDecisions: Long = Long.MAX_VALUE,
    ) {
        replaceAt(
            SEARCH_KEY,
            SearchAnnotation(variableStrategy, valueStrategy, phaseSaving, lubyRestartBase, maxDecisions),
        )
    }

    /** Synthetic name under which the (at-most-one) search annotation is registered.
     *  Starts with `__` so it can't collide with a user-declared property. */
    private val SEARCH_KEY: String get() = "__search"

    /** Register [entry] under [name], or under a uniquely-suffixed variant when [name]
     *  is already taken. `Schema.add` rejects duplicates and exposes no in-place mutator,
     *  so callers that need last-write-wins semantics (e.g. [search]) rely on the
     *  compiler picking the last matching entry in declaration order. */
    private fun replaceAt(name: String, entry: SchemaEntry) {
        if (entries[name] == null) {
            add(name, entry)
        } else {
            add("${name}_${anonCounter++}", entry)
        }
    }

    /** Bulk form: register a list of constraints under one property name. Each
     *  element gets its own [NamedConstraint] entry, keyed `<prop>[0]`, `<prop>[1]`, … */
    protected fun constraints(build: () -> List<BoolExpr>) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, List<NamedConstraint>>> { thisRef, prop ->
            val ncs = build().map { NamedConstraint(it) }
            ncs.forEachIndexed { i, nc -> thisRef.add("${prop.name}[$i]", nc) }
            ReadOnlyProperty { _, _ -> ncs }
        }

    private var anonCounter: Int = 0
}
