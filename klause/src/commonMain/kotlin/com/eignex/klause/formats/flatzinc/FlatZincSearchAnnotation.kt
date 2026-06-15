package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredValueSelector
import com.eignex.klause.solver.backtrack.TieredVariableSelector
import com.eignex.klause.solver.backtrack.selector.DomWdeg
import com.eignex.klause.solver.backtrack.selector.DomainMaxRegret
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.LastConflict
import com.eignex.klause.solver.backtrack.selector.MaxRegret
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.SolutionGuided
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.Xor

/**
 * Map the `solve :: int_search/bool_search/set_search/seq_search(...)` annotation onto
 * [BacktrackParams]. Each search block becomes a [SearchTier] over the variables its
 * array actually lists, in order; `seq_search` contributes its blocks as consecutive
 * tiers. A [TieredVariableSelector] explores the tiers first and falls back to the
 * first block's strategy applied globally for the remaining (introduced) variables,
 * with a matching [TieredValueSelector] on the value side. Returns `null` when no
 * recognised search annotation is present or no block lists any resolvable variable.
 */
internal fun FlatZincCompiler.compileSearchAnnotation(): BacktrackParams? {
    val blocks = model.solve.annotations.filter(::isSearchAnnotation).flatMap(::searchBlocksOf)
    val tiers = blocks.mapNotNull(::compileSearchBlock)
    if (tiers.isEmpty()) return null
    val firstBlockVarName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(1) as? FznExpr.Ident)?.name }
    val firstBlockValName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(2) as? FznExpr.Ident)?.name }
    val fallbackVar = firstBlockVarName?.let(::mapVariableStrategy)
        ?: SmallestDomain
    val fallbackVal = firstBlockValName?.let(::mapValueStrategy)
        ?: IndomainMin
    val tieredVal = TieredValueSelector(tiers, fallbackVal, numBoolVars, intDomains.size)
    // For minimize / maximize, wrap the value side in SolutionGuided so each new
    // incumbent biases the next descent toward "near the last good solution" — the
    // standard SOTA phase-saving-for-BnB pattern.
    val wrappedValH = when (model.solve) {
        is FznSolve.Minimize, is FznSolve.Maximize -> SolutionGuided(tieredVal)
        is FznSolve.Satisfy -> tieredVal
    }
    return BacktrackParams(
        variableSelector = TieredVariableSelector(tiers, fallbackVar),
        valueSelector = wrappedValH,
        // Phase saving on top of the annotation: each variable's last successfully-pinned value
        // is retried first on re-descent, with the annotation's value order filling the rest.
        // The first descent is therefore unchanged (no saved phase yet), so the annotation is
        // still honoured; only post-backtrack re-descents are biased toward the values that
        // last worked. On scheduling-style models this lets the search rebuild good partial
        // assignments instead of re-deriving them from the annotation default every time, which
        // is the difference between thrashing and conflict-driven progress (#543).
        phaseSaving = true,
        // With trail-resident order literals, conflict analysis now learns asserting clauses at
        // a high rate (#588), so the annotated/FD track behaves like a real LCG solver and needs
        // the same database hygiene choco-lcg uses, or the learned clauses pile up unbounded and
        // drown BCP. Luby restarts (deterministic budget — NOT adaptive, which would bypass the
        // Luby budget and never fire here) drive [forgetIfOverCap] to bound the database; phase
        // saving + solution-guided value order above keep the annotation honoured on re-descent.
        lubyRestartBase = 2_000L,
        maxLearnedClauses = 4_000,
        tieredLearnedDb = true,
    )
}

/** Search recipe for a model carrying a multi-xor system: branch the system's bool vars
 *  in ascending xor-occurrence order (rare vars — typically per-row error/slack
 *  indicators — first, smallest value first), with conflict-driven free search
 *  completing the rest. */
internal fun FlatZincCompiler.xorSearchParams(xors: List<Xor>): BacktrackParams {
    val occ = LinkedHashMap<Int, Int>()
    for (x in xors) {
        for (lit in x.literals) {
            val v = Lit.variable(lit)
            occ[v] = (occ[v] ?: 0) + 1
        }
    }
    val ordered = occ.entries.sortedBy { it.value }.map { it.key }.toIntArray()
    val tier = SearchTier(ordered, IntArray(0), TierVarSelect.InputOrder, IndomainMin)
    return BacktrackParams(
        variableSelector = TieredVariableSelector(listOf(tier), LastConflict(Vsids())),
        valueSelector = TieredValueSelector(
            listOf(tier),
            SolutionGuided(IndomainMin),
            numBoolVars,
            intDomains.size,
        ),
        phaseSaving = true,
    )
}

internal fun FlatZincCompiler.isSearchAnnotation(a: FznAnnotation): Boolean =
    a.name == "int_search" || a.name == "bool_search" || a.name == "set_search" || a.name == "seq_search"

/** Flatten an annotation into its concrete search blocks: a plain block is itself;
 *  `seq_search` lists its blocks in order (recursing through nested seq_search). */
internal fun FlatZincCompiler.searchBlocksOf(a: FznAnnotation): List<FznAnnotation> = when (a.name) {
    "int_search", "bool_search", "set_search" -> listOf(a)

    "seq_search" -> {
        val list = (a.args.firstOrNull() as? FznExpr.ArrayLit)?.elements.orEmpty()
        list.mapNotNull { it as? FznExpr.AnnCall }
            .flatMap { searchBlocksOf(FznAnnotation(it.name, it.args)) }
    }

    else -> emptyList()
}

/** One search block → one [SearchTier], or null when the block lists no resolvable
 *  variable. Signature: `search(varArray, var_strategy, value_strategy, complete)`. */
private fun FlatZincCompiler.compileSearchBlock(a: FznAnnotation): SearchTier? {
    if (a.args.size < 3) return null
    val bools = ArrayList<Int>()
    val ints = ArrayList<Int>()
    collectSearchVars(a.args[0], bools, ints)
    if (bools.isEmpty() && ints.isEmpty()) return null
    val varName = (a.args[1] as? FznExpr.Ident)?.name
    val valName = (a.args[2] as? FznExpr.Ident)?.name
    return SearchTier(
        boolVars = bools.toIntArray(),
        intVars = ints.toIntArray(),
        // An unrecognised variable strategy keeps the tier (the var list is the
        // valuable part) and labels it in listed order.
        varSelect = varName?.let(::mapTierVarSelect) ?: TierVarSelect.InputOrder,
        valueSelector = valName?.let(::mapValueStrategy)
            ?: IndomainMin,
    )
}

/** Resolve a search block's variable-array expression into engine var ids, in listed
 *  order. Set vars contribute their indicator bools; float vars their bucket int var;
 *  constants are skipped (models do list literals in search arrays). */
private fun FlatZincCompiler.collectSearchVars(e: FznExpr, bools: ArrayList<Int>, ints: ArrayList<Int>) {
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
                is FlatZincArray.Vars -> when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> for (v in arr.varIds) bools.add(v)

                    FlatZincArray.Vars.ElementKind.Int,
                    FlatZincArray.Vars.ElementKind.Float,
                    -> for (v in arr.varIds) ints.add(v)
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

        // Constants and anything else contribute no search variables.
        else -> {}
    }
}

internal fun FlatZincCompiler.mapTierVarSelect(name: String): TierVarSelect? = when (name) {
    "input_order" -> TierVarSelect.InputOrder
    "first_fail", "most_constrained", "dom_w_deg", "occurrence" -> TierVarSelect.SmallestDomain
    "anti_first_fail" -> TierVarSelect.LargestDomain
    "smallest" -> TierVarSelect.SmallestLowerBound
    "largest" -> TierVarSelect.LargestUpperBound
    "max_regret" -> TierVarSelect.MaxRegret
    "random_order" -> TierVarSelect.RandomOrder
    else -> null
}

internal fun FlatZincCompiler.mapVariableStrategy(name: String): VariableSelector? = when (name) {
    "input_order" -> InputOrder
    "first_fail", "most_constrained" -> SmallestDomain
    "dom_w_deg" -> DomWdeg()
    "anti_first_fail", "occurrence" -> LargestDomain
    "smallest" -> SmallestLowerBound
    "largest" -> LargestUpperBound
    "max_regret" -> DomainMaxRegret
    "random_order" -> RandomVariable
    else -> null
}

internal fun FlatZincCompiler.mapValueStrategy(name: String): ValueSelector? = when (name) {
    "indomain_min", "indomain" -> IndomainMin
    "indomain_max" -> IndomainMax
    "indomain_middle" -> IndomainMiddle
    "indomain_median" -> IndomainMedian
    "indomain_split" -> IndomainSplit
    "indomain_random" -> IndomainRandom
    else -> null
}
