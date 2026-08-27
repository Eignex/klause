package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.formats.flatzinc.*
import com.eignex.klause.util.IntArrayList

/** Build a `FlatZincSearchHints` view from one `solve` annotation block. */
internal fun FlatZincCompiler.compileSearchAnnotation(): FlatZincSearchHints? {
    val blocks = model.solve.annotations.filter(::isSearchAnnotation).flatMap(::searchBlocksOf)
    val tiers = blocks.mapNotNull(::compileSearchBlock)
    if (tiers.isEmpty()) return null

    val firstBlockVarName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(1) as? FznExpr.Ident)?.name }
    val firstBlockValName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(2) as? FznExpr.Ident)?.name }
    val fallbackVar = firstBlockVarName?.let(::mapFallbackVariableStrategy) ?: FlatZincSearchVarSelector.SmallestDomain
    val fallbackVal = firstBlockValName?.let(::mapValueStrategy) ?: FlatZincSearchValueSelector.IndomainMin
    val isOptimize = when (model.solve) {
        is FznSolve.Minimize, is FznSolve.Maximize -> true
        else -> false
    }
    return FlatZincSearchHints(
        tiers = tiers,
        fallbackVarSelector = fallbackVar,
        fallbackValueSelector = fallbackVal,
        solutionGuided = isOptimize,
    )
}

internal fun isSearchAnnotation(a: FznAnnotation): Boolean =
    a.name == "int_search" || a.name == "bool_search" || a.name == "set_search" || a.name == "seq_search"

/** Flatten one search annotation to concrete tiers. */
internal fun FlatZincCompiler.searchBlocksOf(a: FznAnnotation): List<FznAnnotation> = when (a.name) {
    "int_search", "bool_search", "set_search" -> listOf(a)
    "seq_search" -> {
        val list = (a.args.firstOrNull() as? FznExpr.ArrayLit)?.elements.orEmpty()
        list.filterIsInstance<FznExpr.AnnCall>()
            .flatMap { searchBlocksOf(FznAnnotation(it.name, it.args)) }
    }
    else -> emptyList()
}

private fun FlatZincCompiler.compileSearchBlock(a: FznAnnotation): FlatZincSearchTier? {
    if (a.args.size < 3) return null
    val bools = IntArrayList()
    val ints = IntArrayList()
    collectSearchVars(a.args[0], bools, ints)
    if (bools.isEmpty() && ints.isEmpty()) return null
    val varName = (a.args[1] as? FznExpr.Ident)?.name
    val valName = (a.args[2] as? FznExpr.Ident)?.name
    return FlatZincSearchTier(
        boolVars = bools.toIntArray(),
        intVars = ints.toIntArray(),
        varSelector = varName?.let(::mapTierVariableStrategy) ?: FlatZincSearchVarSelector.InputOrder,
        valueSelector = valName?.let(::mapValueStrategy) ?: FlatZincSearchValueSelector.IndomainMin,
    )
}

// Resolve a search variable expression to bool/int ids in the listed order.
private fun FlatZincCompiler.collectSearchVars(e: FznExpr, bools: IntArrayList, ints: IntArrayList) {
    when (e) {
        is FznExpr.Ident -> {
            val name = e.name
            boolVars[name]?.let {
                bools.add(it)
                return
            }
            intVars[name]?.let {
                ints.add(it)
                return
            }
            floatVars[name]?.let {
                ints.add(it.varId)
                return
            }
            setVarsByName[name]?.let { layout ->
                for (b in layout.indicatorBoolIds) bools.add(b)
                return
            }
            when (val arr = arrays[name]) {
                is FlatZincArray.Vars -> {
                    when (arr.elementKind) {
                        FlatZincArray.Vars.ElementKind.Bool -> for (v in arr.varIds) bools.add(v)
                        FlatZincArray.Vars.ElementKind.Int,
                        FlatZincArray.Vars.ElementKind.Float,
                        -> for (v in arr.varIds) ints.add(v)
                    }
                }

                else -> {}
            }
        }

        is FznExpr.ArrayLit -> for (el in e.elements) collectSearchVars(el, bools, ints)

        is FznExpr.ArrayAccess -> {
            val arr = arrays[e.name] as? FlatZincArray.Vars ?: return
            val idx = e.index - 1
            if (idx !in arr.varIds.indices) return
            when (arr.elementKind) {
                FlatZincArray.Vars.ElementKind.Bool -> bools.add(arr.varIds[idx])
                FlatZincArray.Vars.ElementKind.Int,
                FlatZincArray.Vars.ElementKind.Float,
                -> ints.add(arr.varIds[idx])
            }
        }

        else -> {}
    }
}

internal fun mapValueStrategy(name: String): FlatZincSearchValueSelector? = when (name) {
    "indomain_min", "indomain" -> FlatZincSearchValueSelector.IndomainMin
    "indomain_max" -> FlatZincSearchValueSelector.IndomainMax
    "indomain_middle" -> FlatZincSearchValueSelector.IndomainMiddle
    "indomain_median" -> FlatZincSearchValueSelector.IndomainMedian
    "indomain_split" -> FlatZincSearchValueSelector.IndomainSplit
    "indomain_random" -> FlatZincSearchValueSelector.IndomainRandom
    else -> null
}

private fun mapTierVariableStrategy(name: String): FlatZincSearchVarSelector? = when (name) {
    "input_order" -> FlatZincSearchVarSelector.InputOrder
    "first_fail", "most_constrained", "dom_w_deg", "occurrence" -> FlatZincSearchVarSelector.SmallestDomain
    "anti_first_fail" -> FlatZincSearchVarSelector.LargestDomain
    "smallest" -> FlatZincSearchVarSelector.SmallestLowerBound
    "largest" -> FlatZincSearchVarSelector.LargestUpperBound
    "max_regret" -> FlatZincSearchVarSelector.MaxRegret
    "random_order" -> FlatZincSearchVarSelector.RandomOrder
    else -> null
}

private fun mapFallbackVariableStrategy(name: String): FlatZincSearchVarSelector? = when (name) {
    "input_order" -> FlatZincSearchVarSelector.InputOrder
    "first_fail", "most_constrained" -> FlatZincSearchVarSelector.SmallestDomain
    "dom_w_deg" -> FlatZincSearchVarSelector.DomWdeg
    "anti_first_fail", "occurrence" -> FlatZincSearchVarSelector.LargestDomain
    "smallest" -> FlatZincSearchVarSelector.SmallestLowerBound
    "largest" -> FlatZincSearchVarSelector.LargestUpperBound
    "max_regret" -> FlatZincSearchVarSelector.MaxRegret
    "random_order" -> FlatZincSearchVarSelector.RandomOrder
    else -> null
}
