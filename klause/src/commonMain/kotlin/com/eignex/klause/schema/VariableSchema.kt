package com.eignex.klause.schema

import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.ConstraintRole
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntDomainSpec
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

/** The declaration of an integer taking exactly [values], as ascending runs over their hull. */
internal fun valueSetSpec(values: Collection<Long>): IntDomainSpec {
    val sorted = values.toSortedSet().toList()
    require(sorted.isNotEmpty()) { "intVar: values must not be empty" }
    val runs = ArrayList<List<Long>>()
    for (value in sorted) {
        val last = runs.lastOrNull()
        if (last != null && last[1] == value - 1) {
            runs[runs.lastIndex] = listOf(last[0], value)
        } else {
            runs += listOf(value, value)
        }
    }
    return IntDomainSpec(sorted.first(), sorted.last(), runs)
}

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

    protected fun intVar(min: Int, max: Int) =
        register(IntSpec(min, max)) { IntHandle(it, min.toLong(), max.toLong()) }

    /** Integer variable over a 64-bit range. */
    protected fun intVar(min: Long, max: Long) =
        register(IntDomainSpec(min, max)) { IntHandle(it, min, max) }

    /** Integer variable with an open side: a `null` bound leaves that side unbounded. */
    protected fun openIntVar(min: Long? = null, max: Long? = null) =
        register(IntDomainSpec(min, max)) { IntHandle(it, min ?: Long.MIN_VALUE, max ?: Long.MAX_VALUE) }

    /** Integer variable taking exactly one of [values], which need not be contiguous. */
    protected fun intVar(values: Collection<Long>) = valueSetSpec(values).let { spec ->
        register(spec) { IntHandle(it, checkNotNull(spec.lower), checkNotNull(spec.upper)) }
    }

    /** Float variable; an infinite [min] or [max] leaves that side open. */
    protected fun floatVar(
        min: Double = Double.NEGATIVE_INFINITY,
        max: Double = Double.POSITIVE_INFINITY,
        buckets: Int? = null,
    ) = register(FloatSpec(min, max, buckets)) { FloatHandle(it, min, max) }

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
        OptIntHandle(name = name, present = present, value = IntHandle(name, min.toLong(), max.toLong()))
    }

    /**
     * Optional float variable: declares a presence Boolean named `<prop>__present` alongside the
     * bucketised-float value variable. Compare via [OptFloatHandle]'s opt-aware operators to get
     * MiniZinc's "undefined → false" semantics; decode with
     * [com.eignex.klause.compile.CompiledSchema.decode] to read `null` when absent.
     */
    protected fun optFloatVar(min: Double, max: Double, buckets: Int? = null) =
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

    /**
     * A constraint the other constraints already imply, such as a redundant global. It is posted like any
     * other, while local search weighs it lower.
     */
    protected fun impliedConstraint(build: () -> BoolExpr) = roleConstraint(ConstraintRole.IMPLIED, build)

    /**
     * An implied constraint that breaks symmetry. Declaring one also stops presolve from adding its own
     * symmetry breaking to the model.
     */
    protected fun symmetryBreakingConstraint(build: () -> BoolExpr) =
        roleConstraint(ConstraintRole.SYMMETRY_BREAKING, build)

    private fun roleConstraint(role: ConstraintRole, build: () -> BoolExpr) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, NamedConstraint>> { thisRef, prop ->
            val nc = NamedConstraint(build(), role)
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
