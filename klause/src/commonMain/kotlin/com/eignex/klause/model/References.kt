package com.eignex.klause.model

import com.eignex.skema.SchemaDef

// Every `when` below is exhaustive over a sealed family without an `else` branch, so a new
// node fails compilation here instead of passing through with stale references.

/** Returns this expression with every variable reference replaced by `rename(name)`. */
@Suppress("CyclomaticComplexMethod")
fun IntExpr.mapReferences(rename: (String) -> String): IntExpr = when (this) {
    is IntRef -> copy(name = rename(name))

    is IntLit -> this

    is IntScale -> copy(child = child.mapReferences(rename))

    is IntSum -> copy(children = children.mapIntReferences(rename))

    is IntMin -> copy(children = children.mapIntReferences(rename))

    is IntMax -> copy(children = children.mapIntReferences(rename))

    is IntAbs -> copy(child = child.mapReferences(rename))

    is IntElement -> copy(index = index.mapReferences(rename), items = items.mapIntReferences(rename))

    is IntMul -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is IntDiv -> copy(num = num.mapReferences(rename), den = den.mapReferences(rename))

    is IntMod -> copy(num = num.mapReferences(rename), den = den.mapReferences(rename))

    is IntPower -> copy(base = base.mapReferences(rename))

    is SetCard -> copy(set = set.mapReferences(rename))

    is IntIfThenElse -> copy(
        cond = cond.mapReferences(rename),
        thenE = thenE.mapReferences(rename),
        elseE = elseE.mapReferences(rename),
    )
}

/**
 * Returns this expression with every variable reference replaced by `rename(name)`, including
 * references inside nested integer, set, continuous and presence operands. Nominal labels are
 * values, not references, and are kept.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun BoolExpr.mapReferences(rename: (String) -> String): BoolExpr = when (this) {
    is BoolRef -> copy(name = rename(name))

    is NominalEq -> copy(name = rename(name))

    is Not -> copy(child = child.mapReferences(rename))

    is And -> copy(children = children.mapBoolReferences(rename))

    is Or -> copy(children = children.mapBoolReferences(rename))

    is Implies -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is Iff -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is AtMost -> copy(children = children.mapBoolReferences(rename))

    is AtLeast -> copy(children = children.mapBoolReferences(rename))

    is CardinalityExpr -> copy(children = children.mapBoolReferences(rename))

    is XorExpr -> copy(children = children.mapBoolReferences(rename))

    is PseudoBooleanExpr -> copy(lits = lits.mapBoolReferences(rename))

    is IntCompare -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is FloatLinearConstraint -> copy(varNames = varNames.map(rename))

    is ContinuousRealEquality -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is AllDifferent -> copy(terms = terms.mapIntReferences(rename))

    is SymmetricAllDifferent -> copy(terms = terms.mapIntReferences(rename))

    is InverseChannel -> copy(f = f.mapIntReferences(rename), g = g.mapIntReferences(rename))

    is MddExpr -> copy(seq = seq.mapIntReferences(rename))

    is CostRegularExpr -> copy(seq = seq.mapIntReferences(rename), cost = cost.mapReferences(rename))

    is CostMddExpr -> copy(seq = seq.mapIntReferences(rename), cost = cost.mapReferences(rename))

    is CircuitExpr -> copy(succ = succ.mapIntReferences(rename))

    is SubcircuitExpr -> copy(succ = succ.mapIntReferences(rename))

    is CumulativeExpr -> copy(starts = starts.mapIntReferences(rename))

    is DisjunctiveExpr -> copy(starts = starts.mapIntReferences(rename))

    is SortExpr -> copy(xs = xs.mapIntReferences(rename), ys = ys.mapIntReferences(rename))

    is IncreasingExpr -> copy(xs = xs.mapIntReferences(rename))

    is DiffnExpr -> copy(xs = xs.mapIntReferences(rename), ys = ys.mapIntReferences(rename))

    is RegularExpr -> copy(seq = seq.mapIntReferences(rename))

    is TableConstraint -> copy(terms = terms.mapIntReferences(rename))

    is SetIn -> copy(elem = elem.mapReferences(rename), set = set.mapReferences(rename))

    is SetNominalIn -> copy(set = set.mapReferences(rename))

    is SetSubsetOf -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is SetDisjoint -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is SetEq -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is AllDifferentOpt -> copy(terms = terms.mapIntReferences(rename), presents = presents.mapBoolReferences(rename))

    is CumulativeExprOpt ->
        copy(starts = starts.mapIntReferences(rename), presents = presents.mapBoolReferences(rename))

    is DisjunctiveExprOpt ->
        copy(starts = starts.mapIntReferences(rename), presents = presents.mapBoolReferences(rename))

    is NValueExpr -> copy(n = n.mapReferences(rename), xs = xs.mapIntReferences(rename))

    is NValueExprOpt -> copy(
        n = n.mapReferences(rename),
        xs = xs.mapIntReferences(rename),
        presents = presents.mapBoolReferences(rename),
    )

    is GccExprOpt -> copy(xs = xs.mapIntReferences(rename), presents = presents.mapBoolReferences(rename))

    is LexExpr -> copy(xs = xs.mapIntReferences(rename), ys = ys.mapIntReferences(rename))

    is VariableCumulativeExpr -> copy(
        starts = starts.mapIntReferences(rename),
        durations = durations.mapIntReferences(rename),
        demands = demands.mapIntReferences(rename),
        capacity = capacity.mapReferences(rename),
    )

    is FactorConstraint -> copy(bools = bools.map(rename), ints = ints.map(rename), reals = reals.map(rename))
}

/** Returns this set expression with every set-variable reference replaced by `rename(name)`. */
fun SetExpr.mapReferences(rename: (String) -> String): SetExpr = when (this) {
    is SetRef -> copy(name = rename(name))

    is SetLiteral -> this

    is SetNominalLiteral -> this

    is SetUnion -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is SetIntersect -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))

    is SetDiff -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))
}

/**
 * Returns this continuous expression with every variable reference replaced by `rename(name)`.
 * Coefficients keep their exact binary64 values; a rename that maps two coefficients of one
 * affine term to the same name is rejected, because merging them would round their sum.
 */
fun ContinuousRealExpr.mapReferences(rename: (String) -> String): ContinuousRealExpr = when (this) {
    is ContinuousRealExpr.Affine -> {
        val renamed = LinkedHashMap<String, Double>(coefficients.size)
        for ((name, coefficient) in coefficients) {
            val target = rename(name)
            require(renamed.put(target, coefficient) == null) {
                "Renaming merges continuous coefficients onto '$target'"
            }
        }
        copy(coefficients = renamed)
    }

    is ContinuousRealExpr.Power -> copy(base = base.mapReferences(rename))

    is ContinuousRealExpr.Multiply -> copy(left = left.mapReferences(rename), right = right.mapReferences(rename))
}

/**
 * Returns this entry with every variable reference replaced by `rename(name)`: the expression of
 * a [NamedConstraint] and the value variable a [PresenceSpec] gates. Other declarations carry no
 * references and are returned unchanged.
 */
fun SchemaEntry.mapReferences(rename: (String) -> String): SchemaEntry = when (this) {
    is NamedConstraint -> copy(expr = expr.mapReferences(rename))

    is PresenceSpec -> copy(valueName = rename(valueName))

    is BoolSpec, is NominalSpec, is IntSpec, is IntDomainSpec, is FloatSpec, is SetSpec, is MultipleSpec -> this
}

/**
 * Renames every entry to `rename(name)` and rewrites every reference inside the entries with the
 * same function, keeping declaration order. References to names this schema does not declare are
 * passed to [rename] too; return a name unchanged to keep it. Two entries renamed to the same name
 * are rejected.
 */
fun SchemaDef<SchemaEntry>.renameDeclarations(rename: (String) -> String): SchemaDef<SchemaEntry> {
    val renamed = LinkedHashMap<String, SchemaEntry>(entries.size)
    for ((name, entry) in entries) {
        val target = rename(name)
        require(renamed.put(target, entry.mapReferences(rename)) == null) {
            "Renaming declares '$target' twice"
        }
    }
    return SchemaDef(renamed)
}

private fun List<IntExpr>.mapIntReferences(rename: (String) -> String): List<IntExpr> =
    map { it.mapReferences(rename) }

private fun List<BoolExpr>.mapBoolReferences(rename: (String) -> String): List<BoolExpr> =
    map { it.mapReferences(rename) }
