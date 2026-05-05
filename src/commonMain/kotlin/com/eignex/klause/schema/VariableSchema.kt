package com.eignex.klause.schema

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.SchemaDef
import com.eignex.klause.ast.VarSpec
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Property-delegate base class for declaring a typed schema. Mirrors kumulant's `StatSchema`:
 * each `boolVar()` / `nominal(...)` / `constraint { ... }` call returns a delegate provider that
 * captures the host property's name and registers a [VarSpec] / [NamedConstraint].
 *
 * Constraint delegates execute their build lambda *during class initialization*, so any handles
 * referenced inside `constraint { ... }` must be declared earlier in the class.
 */
abstract class VariableSchema {
    private val _vars = mutableListOf<VarSpec>()
    private val _constraints = mutableListOf<NamedConstraint>()

    val vars: List<VarSpec> get() = _vars
    val constraints: List<NamedConstraint> get() = _constraints

    fun definition(): SchemaDef = SchemaDef(_vars.toList(), _constraints.toList())

    protected fun boolVar() =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, BoolHandle>> { _, prop ->
            _vars += BoolSpec(prop.name)
            val handle = BoolHandle(prop.name)
            ReadOnlyProperty { _, _ -> handle }
        }

    protected fun nominal(vararg labels: String) =
        PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, NominalHandle>> { _, prop ->
            val labelList = labels.toList()
            _vars += NominalSpec(prop.name, labelList)
            val handle = NominalHandle(prop.name, labelList)
            ReadOnlyProperty { _, _ -> handle }
        }

    protected fun constraint(
        isHard: Boolean = true,
        weight: Double = 1.0,
        build: () -> BoolExpr,
    ) = PropertyDelegateProvider<VariableSchema, ReadOnlyProperty<VariableSchema, NamedConstraint>> { _, prop ->
        val nc = NamedConstraint(prop.name, build(), isHard, weight)
        _constraints += nc
        ReadOnlyProperty { _, _ -> nc }
    }
}
