package com.eignex.klause.formats.flatzinc

import com.eignex.klause.util.IntDisjointSet

internal const val DEFAULT_FLOAT_CHOICE_LIMIT = 4096

private val FLOAT_LINEAR_NAMES = setOf(
    "float_lin_le", "float_lin_eq", "float_lin_ne", "float_lin_lt",
    "float_lin_le_reif", "float_lin_eq_reif", "float_lin_ne_reif", "float_lin_lt_reif",
    "int2float", "float_eq", "float_le", "float_lt", "float_ne",
    "float_eq_reif", "float_le_reif", "float_lt_reif", "float_ne_reif",
    "float_abs", "float_min", "float_max", "array_float_element",
)

internal fun FlatZincCompiler.selectFloatNames(): Set<String> {
    val hasFloatColumns = model.varDecls.any { it.isVar && it.type.hasFloats() }
    val hasExactSelection = exactFloats && model.constraints.any { it.name == "array_float_element" }
    if (!hasFloatColumns && !hasExactSelection) return emptySet()
    // Resolve aliases before assigning real and integer ids, whose namespaces overlap.
    val declarations = FlatZincCompiler(
        model,
        floatBuckets,
        floatScale,
        forLocalSearch,
        unboundedIntLo,
        unboundedIntHi,
    )
    declarations.processDeclarations()
    return FloatChoicePlanner(declarations, exactFloats, floatChoiceLimit).select()
}

private fun FznType.hasFloats(): Boolean = when (this) {
    FznType.FloatAny, is FznType.FloatRange -> true
    is FznType.Array -> element.hasFloats()
    else -> false
}

private class FloatChoicePlanner(
    private val declarations: FlatZincCompiler,
    private val exact: Boolean,
    private val limit: Int,
) {
    private val model = declarations.model
    private val components = IntDisjointSet(declarations.intDomains.size)
    private val references = model.constraints.map { c -> c.args.flatMap(::variables) }
    private val singletons = declarations.floatVars.values.filter { it.lo == it.hi }
        .associate { it.varId to doubleArrayOf(it.lo) }
    private val choices = HashMap(singletons)
    private val integerImages = model.constraints.filter { it.name == "int2float" && it.args.size == 2 }
        .mapTo(HashSet()) { it.args[1] }
    private val costs = HashMap<Int, Long>()
    private val blocked = HashSet<Int>()

    fun select(): Set<String> {
        for (ids in references) for (id in ids.drop(1)) components.union(ids.first(), id)
        for (c in model.constraints) recordChoices(c)
        for ((i, c) in model.constraints.withIndex()) {
            val ids = references[i]
            if (ids.isEmpty()) {
                if (exact && c.name == "array_float_element" && c.args.size == 3) {
                    declarations.locateConstraint(c)
                    if (declarations.evalFloatConstArray(c.args[1]).size > limit) {
                        decline(-1, c, "finite float choices exceed the $limit alternative limit")
                    }
                }
                continue
            }
            val root = components.find(ids.first())
            declarations.locateConstraint(c)
            val alternatives = alternatives(c)
            if (alternatives == null) {
                decline(root, c, "`${c.name}` is unsupported by exact float lowering")
            } else {
                val cost = costs.getOrElse(root) { 0L } + alternatives
                costs[root] = cost
                if (cost > limit) decline(root, c, "finite float choices exceed the $limit alternative limit")
            }
        }
        val finite = choices.keys.mapTo(HashSet()) { components.find(it) }
        val objective = when (val solve = model.solve) {
            is FznSolve.Minimize -> variables(solve.obj)
            is FznSolve.Maximize -> variables(solve.obj)
            else -> emptyList()
        }.mapTo(HashSet()) { components.find(it) }
        return declarations.floatVars.filterValues {
            val root = components.find(it.varId)
            root !in blocked && (exact || root in finite || (root !in costs && root !in objective))
        }.keys
    }

    private fun decline(root: Int, c: FznConstraint, reason: String) {
        if (exact) throw UnsupportedFlatZincException(reason, c.line, c.col)
        blocked.add(root)
    }

    private fun recordChoices(c: FznConstraint) {
        if (c.name != "array_float_element" || c.args.size != 3) return
        val id = variables(c.args[2]).singleOrNull() ?: return
        if (id in singletons) return
        declarations.locateConstraint(c)
        val values = declarations.evalFloatConstArray(c.args[1]).distinct().toDoubleArray()
        choices[id] = choices[id]?.filter { candidate -> values.any { it == candidate } }?.toDoubleArray() ?: values
    }

    private fun alternatives(c: FznConstraint): Int? = when {
        c.name == "array_float_element" && c.args.size == 3 -> declarations.evalFloatConstArray(c.args[1]).size
        c.name in FLOAT_LINEAR_NAMES -> 0
        c.name == "float_div" && c.args.size == 3 && variables(c.args[1]).isEmpty() -> 0
        c.name == "float_times" && c.args.size == 3 -> productAlternatives(c)
        else -> null
    }

    private fun productAlternatives(c: FznConstraint): Int? {
        val a = variables(c.args[0]).singleOrNull() ?: return 0
        val b = variables(c.args[1]).singleOrNull() ?: return 0
        val aSize = choices[a]?.size
        val bSize = choices[b]?.size
        if (aSize != null || bSize != null) return minOf(aSize ?: Int.MAX_VALUE, bSize ?: Int.MAX_VALUE)
        val aInt = c.args[0] in integerImages
        val bInt = c.args[1] in integerImages
        return if (aInt != bInt && variables(c.args[2]).isNotEmpty()) 0 else null
    }

    private fun variables(e: FznExpr): List<Int> = when (e) {
        is FznExpr.Ident -> declarations.floatVars[e.name]?.let { listOf(it.varId) }
            ?: (declarations.arrays[e.name] as? FlatZincArray.Vars)?.floatBucketings?.map { it.varId }.orEmpty()

        is FznExpr.ArrayAccess -> (declarations.arrays[e.name] as? FlatZincArray.Vars)?.floatBucketings
            ?.getOrNull(e.index - 1)?.let { listOf(it.varId) }.orEmpty()

        is FznExpr.ArrayLit -> e.elements.flatMap(::variables)

        else -> emptyList()
    }
}
