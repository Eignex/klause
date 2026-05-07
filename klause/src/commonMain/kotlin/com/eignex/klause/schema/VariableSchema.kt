package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.SchemaEntry
import com.eignex.skema.Schema
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

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

    protected fun constraint(build: () -> BoolExpr) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, NamedConstraint>> { thisRef, prop ->
            val nc = NamedConstraint(build())
            thisRef.add(prop.name, nc)
            ReadOnlyProperty { _, _ -> nc }
        }
}
