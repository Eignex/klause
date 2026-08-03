package com.eignex.klause.schema

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.MultipleSpec
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.NominalSpec
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.model.SetSpec
import com.eignex.skema.Schema
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** Suffix used to name the synthetic presence Boolean backing every opt variable. */
internal const val PRESENCE_SUFFIX: String = "__present"

/** Builds the canonical presence-variable name for an opt var named [varName]. */
internal fun presenceName(varName: String): String = "$varName$PRESENCE_SUFFIX"

/**
 * Property-delegate base class for declaring a typed klause schema. Backed by
 * [com.eignex.skema.Schema] so the same singleton produces typed compile-time handles
 * and a wire-serializable [com.eignex.skema.SchemaDef] of [SchemaEntry] rows.
 *
 * Constraint delegates evaluate their build lambda *during class initialization*, so any
 * handle referenced inside `constraint { ... }` must be declared earlier in the class.
 */
open class VariableSchema : Schema<SchemaEntry>() {

    protected fun boolVar() = register(BoolSpec) { BoolHandle(it) }

    protected fun nominal(vararg labels: String) = labels.toList().let { ls ->
        register(NominalSpec(ls)) { NominalHandle(it, ls) }
    }

    protected fun intVar(min: Int, max: Int) = register(IntSpec(min, max)) { IntHandle(it, min, max) }

    protected fun floatVar(min: Double, max: Double, buckets: Int = DEFAULT_FLOAT_BUCKETS) =
        register(FloatSpec(min, max, buckets)) { FloatHandle(it, min, max, buckets) }

    /**
     * Shared delegate for the opt-variable builders: declares the synthetic presence Boolean
     * `<prop>__present` plus the value variable's [valueSpec], then wires both into the typed
     * handle built by [makeHandle] (given the value var's name and its presence handle).
     */
    private fun <H> optVar(valueSpec: SchemaEntry, makeHandle: (name: String, present: BoolHandle) -> H) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, H>> { thisRef, prop ->
            val pName = presenceName(prop.name)
            thisRef.add(pName, PresenceSpec(prop.name))
            thisRef.add(prop.name, valueSpec)
            val handle = makeHandle(prop.name, BoolHandle(pName))
            ReadOnlyProperty { _, _ -> handle }
        }

    /**
     * Optional integer variable: declares a presence Boolean named `<prop>__present` alongside
     * the value variable. Compare via [OptIntHandle]'s opt-aware operators to get MiniZinc's
     * "undefined → false" semantics, or coerce to a regular [com.eignex.klause.model.IntExpr]
     * with [OptIntHandle.valueOr].
     */
    protected fun optIntVar(min: Int, max: Int) = optVar(IntSpec(min, max)) { name, present ->
        OptIntHandle(name = name, present = present, value = IntHandle(name, min, max))
    }

    /**
     * Optional float variable: declares a presence Boolean named `<prop>__present` alongside the
     * bucketised-float value variable. Compare via [OptFloatHandle]'s opt-aware operators to get
     * MiniZinc's "undefined → false" semantics; decode with
     * [com.eignex.klause.compile.CompiledSchema.decode] to read `null` when absent.
     */
    protected fun optFloatVar(min: Double, max: Double, buckets: Int = DEFAULT_FLOAT_BUCKETS) =
        optVar(FloatSpec(min, max, buckets)) { name, present ->
            OptFloatHandle(name = name, present = present, value = FloatHandle(name, min, max))
        }

    /**
     * Optional Boolean variable: declares a presence bool plus the value bool. In a Boolean
     * context the handle coerces to `present ∧ value`; access [OptBoolHandle.present] /
     * [OptBoolHandle.value] for direct manipulation.
     */
    protected fun optBoolVar() = optVar(BoolSpec) { name, present ->
        OptBoolHandle(name = name, present = present, value = BoolHandle(name))
    }

    /**
     * Set variable over an integer universe. Internally allocates one indicator bool per
     * universe element; the decoder reads those indicators back to a [Set]<Int>. Use the
     * infix operators in `com.eignex.klause.schema.SetHandles` to compose constraints.
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
        optVar(NominalSpec(ls)) { name, present ->
            OptNominalHandle(name = name, present = present, value = NominalHandle(name, ls))
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

    /** Bulk form: register a list of constraints under one property name. Each
     *  element gets its own [NamedConstraint] entry, keyed `<prop>[0]`, `<prop>[1]`, … */
    protected fun constraints(build: () -> List<BoolExpr>) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, List<NamedConstraint>>> {
                thisRef,
                prop,
            ->
            val ncs = build().map { NamedConstraint(it) }
            ncs.forEachIndexed { i, nc -> thisRef.add("${prop.name}[$i]", nc) }
            ReadOnlyProperty { _, _ -> ncs }
        }

    private var anonCounter: Int = 0
}
