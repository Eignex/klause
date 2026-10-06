package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntDisjointSet
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.LongHashSet
import com.eignex.klause.util.MutableIntIntMap
import com.eignex.klause.util.MutableIntObjectMap

internal object SymmetryBreaking {

    /** Cap on a verified-symmetry candidate group; larger groups are skipped — verification is
     *  quadratic in the group size, and skipping only forgoes symmetries, never invents one. */
    private const val MAX_VERIFIED_GROUP = 40

    /**
     * Symmetry breaking by detecting interchangeable variables. A variable transposition
     * is broken only when swapping the two variables maps the factor multiset onto itself — verified by
     * remapping every factor and comparing `Factor.structuralKey` counts, so it is sound by
     * construction. Candidate groups come from Weisfeiler–Leman colour refinement (only same-colour
     * variables can be interchangeable); each candidate swap is then checked. Ordering a verified orbit
     * (`x₀ ≤ x₁ ≤ …` for ints, `¬gⱼ ∨ gⱼ₊₁` for bools) keeps exactly one representative per orbit —
     * sound (never removes the last solution of an orbit).
     *
     * Variables in [objectiveIntVars] / [objectiveBoolVars] are excluded so an asymmetric objective
     * can't be cut — keep those sets empty for pure feasibility. Enabled by default except in a pure
     * local-search portfolio.
     *
     * Also breaks value symmetry ([breakValueSymmetry]).
     */
    fun breakSymmetries(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): PassDelta {
        val extra = breakings(problem, FiniteColumns(problem), objectiveIntVars, objectiveBoolVars, cancellation)
        return if (extra.isEmpty()) PassDelta() else PassDelta(addedFactors = extra)
    }

    /**
     * [breakSymmetries] over a source model that still has an open integer column.
     *
     * Two columns are interchangeable only when their declarations match: the same value set for closed
     * columns, and for a column with an open side the same open sides and the same finite endpoints, which
     * is all such a column declares. A value orbit is grouped by which columns admit each value, open
     * columns included, so a transposition within it maps every declaration to itself; the scanned values
     * are those of the closed columns, so an open column never contributes an endpoint it does not have.
     *
     * Only breaks some theory can own are posted, since a CP-only factor over an open column leaves that
     * column with no lane: the scalar total orders, the Boolean orders, and the single-column value pin.
     * The generator lex and the value-precedence chain are CP propagators and wait for the finite lane.
     * A model with every column closed is left to the finite form outright, which reads root-propagated
     * domains and posts both.
     *
     * A total order needs only transpositions, so the source form never runs the generator search: it
     * tests the pairs inside each colour class directly, against the factors the two columns read.
     */
    fun breakSourceSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: Cancellation = Cancellation.Never,
    ): SourceDelta {
        val bounds = problem.intBounds
        if ((0 until problem.numIntVars).all { bounds.hasLower(it) && bounds.hasUpper(it) }) return SourceDelta()
        if (problem.factors.any { it is SymmetryHandling }) return SourceDelta()
        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return SourceDelta()
        // A column no factor reads is decided by no lane, so ordering it buys nothing and only adds a row the
        // theory then has to carry; it is held fixed like an objective column.
        val readInts = BooleanArray(problem.numIntVars)
        val readBools = BooleanArray(problem.numBoolVars)
        for (f in problem.factors) {
            for (v in f.intVars) readInts[v] = true
            for (v in f.boolVars) readBools[v] = true
        }
        val heldInts = objectiveIntVars + readInts.indices.filter { !readInts[it] }
        val heldBools = objectiveBoolVars + readBools.indices.filter { !readBools[it] }
        val columns = SourceColumns(problem)
        val extra = ArrayList<Factor>()
        extra.addAll(transpositionOrders(problem, columns, heldInts, heldBools, cancellation))
        extra.addAll(breakValueSymmetry(problem, columns, heldInts, cancellation, chainPrecedence = false))
        return if (extra.isEmpty()) SourceDelta() else SourceDelta(addedFactors = extra)
    }

    private fun breakings(
        problem: Problem,
        columns: ColumnValues,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
        cancellation: Cancellation,
    ): List<Factor> {
        // Symmetry breaking is a one-shot transformation: once a [SymmetryHandling] factor is present
        // the generators have been found and posted. The presolve round engine re-enables this pass
        // whenever another pass changes the problem, but re-running would (a) re-search from scratch and
        // (b) have to remap (conjugate every generator) and re-key the heavy [SymmetryHandling] factor it
        // just added — an O(rounds) blow-up that dominated presolve on symmetric models. So detect once.
        if (problem.factors.any { it is SymmetryHandling }) return emptyList()

        // A model too large for even one generator-refinement round ([GENERATOR_ROUND_COST_BUDGET]) is
        // skipped outright — not just for the generator search, but for the value / scalar heuristics too.
        // Such models empirically carry no verifiable symmetry of any kind, and the value-orbit keying and
        // the round-cost scan itself are each O(factors); paying them only to post nothing dominates
        // presolve on the largest models (a 245k-factor routing model spends hundreds of ms here). Sound —
        // skipping symmetry breaking only ever forgoes a reduction, never changes a solution.
        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return emptyList()

        // Generator-based detection: individualization–refinement over the unified variable+factor
        // colouring yields verified automorphism generators (catching composite and bool/int-mixed
        // symmetries the per-kind heuristics miss). The whole group is handled dynamically by one
        // [SymmetryHandling] factor whose [SymmetryPropagator] enforces every generator's lex-leader
        // `V ≤lex σ(V)` at each search node — sound (the orbit lex-minimum satisfies it) and with no
        // static enumeration of group elements.
        val generators = findGenerators(problem, columns, objectiveIntVars, objectiveBoolVars, cancellation)
        // For an orbit whose members are *individually* interchangeable (each single transposition is
        // itself an automorphism — a scalar symmetric group, not a lockstep matrix), the full total
        // order is sound and strictly stronger than the generator lex, so post it too.
        val scalarLex = scalarTotalOrders(problem, generators, objectiveIntVars, objectiveBoolVars)
        val valuePins = breakValueSymmetry(problem, columns, objectiveIntVars, cancellation, chainPrecedence = true)
        val extra = ArrayList<Factor>()
        if (generators.isNotEmpty()) extra.add(SymmetryHandling(generators))
        extra.addAll(scalarLex)
        extra.addAll(valuePins)
        return extra
    }

    /**
     * Total orders over the columns that single transpositions map onto each other.
     *
     * A transposition is an automorphism only between two columns of one colour class, so the classes of
     * the equitable partition are the candidates, and only when two columns share a seed can any class
     * hold two. Each candidate pair is checked by [swapsOntoItself], and the verified pairs are unioned:
     * transpositions connecting a set generate its whole symmetric group, so ordering the set keeps one
     * representative of every orbit. Both checks are exact, so an exhausted budget only finds fewer.
     */
    private fun transpositionOrders(
        problem: Problem,
        columns: ColumnValues,
        heldInts: Set<Int>,
        heldBools: Set<Int>,
        cancellation: Cancellation,
    ): List<Factor> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val seedInt = Array(nInt) { v -> if (v in heldInts) objectiveSeed(SPACE_INT, v) else columns.seed(v) }
        val freeBools = (0 until nBool).count { it !in heldBools }
        val seeds = HashSet<RefineKey>()
        val sharedSeed = (0 until nInt).any { v -> v !in heldInts && !seeds.add(seedInt[v]) }
        if (!sharedSeed && freeBools < 2) return emptyList()
        val seedBool = Array(nBool) { v -> if (v in heldBools) objectiveSeed(SPACE_BOOL, v) else columns.boolSeed(v) }
        val budget = intArrayOf(GENERATOR_WORK_BUDGET)
        val (intColour, boolColour) = equitablePartition(problem, seedInt, seedBool, budget, cancellation)
        val intInc = Array(nInt) { IntArrayList() }
        val boolInc = Array(nBool) { IntArrayList() }
        problem.factors.forEachIndexed { fi, f ->
            for (v in f.intVars.distinct()) intInc[v].add(fi)
            for (v in f.boolVars.distinct()) boolInc[v].add(fi)
        }
        val intMap = IntArray(nInt) { it }
        val boolMap = IntArray(nBool) { it }
        val mapping = VarRemap(boolMap, intMap)
        val extra = ArrayList<Factor>()
        for (cell in colourCells(intColour, heldInts)) {
            for (group in verifiedGroups(problem, cell, intInc, intMap, mapping, cancellation)) {
                for (j in 0 until group.size - 1) {
                    extra.add(Linear(intArrayOf(1, -1), intArrayOf(group[j], group[j + 1]), LinearOp.LE, 0))
                }
            }
        }
        for (cell in colourCells(boolColour, heldBools)) {
            for (group in verifiedGroups(problem, cell, boolInc, boolMap, mapping, cancellation)) {
                for (j in 0 until group.size - 1) {
                    extra.add(Clause(intArrayOf(Lit.make(group[j], false), Lit.make(group[j + 1], true))))
                }
            }
        }
        return extra
    }

    /** The columns of each colour outside [held], ascending, for every colour with 2 to [MAX_VERIFIED_GROUP]. */
    private fun colourCells(colour: IntArray, held: Set<Int>): List<IntArray> {
        val cells = MutableIntObjectMap<IntArrayList>()
        for (v in colour.indices) if (v !in held) cells.getOrPut(colour[v]) { IntArrayList() }.add(v)
        val out = ArrayList<IntArray>()
        cells.forEach { _, members -> if (members.size in 2..MAX_VERIFIED_GROUP) out.add(members.toIntArray()) }
        out.sortBy { it[0] }
        return out
    }

    /** The members of [cell] that verified transpositions connect, each group ascending and of size 2 or more. */
    private fun verifiedGroups(
        problem: Problem,
        cell: IntArray,
        incident: Array<IntArrayList>,
        identity: IntArray,
        mapping: VarRemap,
        cancellation: Cancellation,
    ): List<IntArray> {
        val ds = IntDisjointSet(cell.size)
        var pairCost = 0L
        for (v in cell) pairCost += incident[v].size
        unionVerifiedPairs(ds, IntArray(cell.size) { it }, pairCost, cancellation) { i, j ->
            swapsOntoItself(problem, incident, cell[i], cell[j], identity, mapping)
        }
        return ds.groups().filter { it.size >= 2 }.map { group ->
            IntArray(group.size) { cell[group[it]] }.apply { sort() }
        }
    }

    /**
     * Whether swapping columns [a] and [b] maps the factor multiset onto itself.
     *
     * A factor reading neither column is its own image, and the image of one that reads either still reads
     * one of them, so the swap is an automorphism exactly when it maps the factors reading [a] or [b] onto
     * themselves. [identity] is the identity map of the swapped space, restored before returning.
     */
    private fun swapsOntoItself(
        problem: Problem,
        incident: Array<IntArrayList>,
        a: Int,
        b: Int,
        identity: IntArray,
        mapping: VarRemap,
    ): Boolean {
        val reading = (incident[a].toIntArray() + incident[b].toIntArray()).distinct()
        val factors = reading.map { problem.factors[it] }
        identity[a] = b
        identity[b] = a
        val swapped = PresolveShared.matchesMultiset(factors, PresolveShared.structuralKeyMultiset(factors)) {
            it.remap(mapping)
        }
        identity[a] = a
        identity[b] = b
        return swapped
    }

    /**
     * Value symmetry breaking. A permutation of values that maps every domain to itself
     * and the factor set to itself is a symmetry. Candidate orbits are values with the same
     * domain-incidence (the set of variables whose domain contains them) — so any transposition
     * within an orbit already maps every domain to itself. Each transposition is then *verified*
     * against the factors: applying it via `Factor.remapValues` and comparing the `Factor.structuralKey`
     * multiset proves the swap is a symmetry, the value analog of the `Factor.remap`-based
     * automorphism check. Transpositions generate the full symmetric group on a verified orbit, so one variable
     * whose domain lies entirely within an orbit is pinned to the orbit minimum — a sound break (a
     * solution can always be relabeled within the orbit so that variable takes the minimum).
     *
     * When every factor is value-anonymous (`Factor.isValueAnonymous` — AllDifferent), verification is
     * skipped: anonymity means every relabeling is a symmetry, so the whole incidence group is one
     * orbit (the anonymity fast path). Otherwise verification widens detection to problems with
     * value-relabelable factors (GlobalCardinality, Table, …) that the anonymity gate switched off; a
     * factor that is unkeyed or returns `null` from `Factor.remapValues` conservatively blocks it.
     */
    private fun breakValueSymmetry(
        problem: Problem,
        columns: ColumnValues,
        objectiveIntVars: Set<Int>,
        cancellation: Cancellation,
        chainPrecedence: Boolean,
    ): List<Factor> {
        val orbits = verifiedValueOrbits(problem, columns, cancellation) ?: return emptyList()
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            val orbitSet = LongHashSet()
            orbit.forEach { orbitSet.add(it) }
            val internal = (0 until problem.numIntVars)
                .filter { it !in objectiveIntVars && domainWithin(columns.finite(it), orbitSet) }
            when {
                // Law–Lee value precedence (the default value break): introduce the orbit's values in
                // sorted order across the interchangeable variables — one representative per value-symmetry
                // class survives, strictly stronger than pinning a single variable. Needs ≥ 2 variables to
                // chain; with one it degrades to the single-variable pin (the only sound break available).
                chainPrecedence && internal.size >= 2 -> {
                    val sortedValues = orbit.sorted()
                    val seq = internal.toIntArray()
                    for (i in 0 until sortedValues.size - 1) {
                        extra.add(ValuePrecede(sortedValues[i], sortedValues[i + 1], seq))
                    }
                }

                internal.isNotEmpty() -> extra.add(
                    Linear(longArrayOf(1), intArrayOf(internal[0]), LinearOp.EQ, orbit.min()),
                )
            }
        }
        return extra
    }

    /**
     * The verified-interchangeable value orbits shared by [breakValueSymmetry] and
     * [breakValuePrecedence]: values grouped by domain-incidence, then refined against the factors
     * ([verifyValueOrbits]) unless every factor is value-anonymous (the anonymity fast path skips
     * verification). Returns the orbits of size ≥ 2, or `null` when nothing is eligible (no int
     * variables, an unkeyed factor on the verified path, or an empty value range) — each caller maps
     * `null` to its own "post nothing" result. Objective-variable exclusion happens at each caller's
     * per-orbit action, not here.
     */
    private fun verifiedValueOrbits(
        problem: Problem,
        columns: ColumnValues,
        cancellation: Cancellation = Cancellation.Never,
    ): List<List<Long>>? {
        if (problem.numIntVars == 0 || cancellation()) return null
        val allAnonymous = problem.factors.all { it.isValueAnonymous() }
        // Verifying value swaps re-keys the whole factor set, so on a model whose factors carry large
        // constant data (wide tables) the keying dominates while almost never yielding a value symmetry —
        // the value-side analog of the generator search's [GENERATOR_ROUND_COST_BUDGET] skip. Bail before
        // keying when the multiset would be that expensive (sound — skipping only forgoes value pins).
        if (!allAnonymous) {
            var keyCost = 0L
            for (f in problem.factors) {
                keyCost += f.structuralKeyWeight.toLong()
                if (keyCost > VALUE_ORBIT_KEY_BUDGET) return null
            }
        }
        // The anonymous fast path needs no multiset; otherwise key every factor — but lazily, so a
        // candidate-free model (or a fired budget) never pays for the (wide-table-) expensive keying.
        val base: Map<StructuralKey, Int> by lazy { PresolveShared.structuralKeyMultiset(problem.factors.asList()) }
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (v in 0 until problem.numIntVars) {
            val d = columns.finite(v) ?: continue
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return null
        // Size skip: the incidence scan below visits every value in [lo, hi] against every int
        // variable, so a wide span across many variables is too costly to be worth the value pins.
        // Written as `hi > lo + perVarSpan` to stay overflow-safe when the bounds straddle Long extremes.
        val perVarSpan = VALUE_ORBIT_SCAN_BUDGET / problem.numIntVars.coerceAtLeast(1)
        if (hi > lo + perVarSpan) return null
        // Group values by domain-incidence signature: same set of containing variables ⇒ a candidate
        // orbit (a swap within it maps every domain to itself). The incident variable ids, in
        // ascending order, are the signature words.
        val incidence = HashMap<RefineKey, MutableList<Long>>()
        for (value in lo..hi) {
            // Bail on a fired presolve budget — the scan and the per-candidate keying below are the
            // value-symmetry phase's cost; returning what is grouped so far only forgoes value pins.
            if (cancellation()) return null
            cancellation.charge(problem.numIntVars.toLong())
            val sig = LongArrayList()
            for (x in 0 until problem.numIntVars) if (columns.contains(x, value)) sig.add(x.toLong())
            if (!sig.isEmpty()) incidence.getOrPut(RefineKey(sig.toLongArray())) { ArrayList() }.add(value)
        }
        val orbits = ArrayList<List<Long>>()
        for (candidate in incidence.values) {
            if (cancellation()) break
            if (candidate.size < 2) continue
            // Anonymous: the whole group is one orbit. Otherwise refine into verified-equal orbits.
            val refined =
                if (allAnonymous) listOf(candidate) else verifyValueOrbits(problem, base, candidate, cancellation)
            for (orbit in refined) if (orbit.size >= 2) orbits.add(orbit)
        }
        return orbits
    }

    /**
     * Law–Lee value precedence, the strong value-symmetry break, posted with the native
     * [ValuePrecede] propagator. For the value-anonymous case (every factor is
     * `Factor.isValueAnonymous`, so any value relabeling is a symmetry), each orbit of interchangeable
     * values is forced to be *introduced in sorted order*: the first occurrence of the orbit's `j`-th
     * smallest value precedes the first occurrence of its `(j+1)`-th, over the variables whose domain
     * is that orbit. This is a `value_precede_chain` — one [ValuePrecede] per consecutive value pair.
     * Every solution can be relabeled within the orbit to this canonical "restricted-growth" form, so
     * exactly one representative per symmetry class survives — strictly stronger than pinning a single
     * variable ([breakValueSymmetry]).
     *
     * Only the value-anonymous setting is handled: there an orbit equals a value-incidence class, so
     * every fully-internal variable's domain is *exactly* the orbit (incidence-equality forces it),
     * which is what makes ordering the first occurrences sound. Non-anonymous problems keep the
     * verified single-variable pin. Unlike the original decomposition this needs no auxiliary
     * variables — the native factor reasons over arbitrary (not just consecutive) value pairs — so
     * the variable space is unchanged and no reconstruction is required.
     *
     * Variables in [objectiveIntVars] are excluded (ordering them would change the optimum). Returns
     * the original problem unchanged when nothing is eligible.
     */
    fun breakValuePrecedence(problem: BakedProblem, objectiveIntVars: Set<Int> = emptySet()): PassDelta {
        val n = problem.numIntVars
        // A verified orbit is interchangeable; ordering its first occurrences is sound. A
        // fully-internal variable (domain ⊆ orbit) exists only when the orbit equals the whole
        // incidence group, so a split orbit simply posts nothing — never unsound.
        val columns = FiniteColumns(problem)
        val orbits = verifiedValueOrbits(problem, columns) ?: return PassDelta()
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            val orbitSet = LongHashSet()
            orbit.forEach { orbitSet.add(it) }
            val seq = IntArrayList()
            for (x in 0 until n) {
                if (x !in objectiveIntVars && domainWithin(columns.finite(x), orbitSet)) seq.add(x)
            }
            if (seq.size < 2) continue
            val sortedValues = orbit.sorted()
            val seqArray = seq.toIntArray()
            for (i in 0 until sortedValues.size - 1) {
                extra.add(ValuePrecede(sortedValues[i], sortedValues[i + 1], seqArray))
            }
        }
        if (extra.isEmpty()) return PassDelta()
        return PassDelta(addedFactors = extra)
    }

    /** Refine a domain-incidence candidate [values] into verified-interchangeable value orbits: union
     *  the value pairs whose transposition is verified a symmetry ([verifyValueSwap]). Transpositions
     *  generate the full symmetric group on each resulting orbit. Groups beyond [MAX_VERIFIED_GROUP]
     *  are skipped (the O(n²·factors) guard, as for variables). */
    private fun verifyValueOrbits(
        problem: Problem,
        base: Map<StructuralKey, Int>,
        values: List<Long>,
        cancellation: Cancellation = Cancellation.Never,
    ): List<List<Long>> {
        val n = values.size
        if (n > MAX_VERIFIED_GROUP) return emptyList()
        val ds = IntDisjointSet(n)
        unionVerifiedPairs(ds, IntArray(n) { it }, problem.factors.size.toLong(), cancellation) { i, j ->
            verifyValueSwap(problem, base, values[i], values[j])
        }
        return ds.groups().map { group -> group.map { values[it] } }
    }

    /** Whether the value transposition `(v w)` maps the factor multiset to itself — relabel every
     *  factor via `Factor.remapValues` and compare `Factor.structuralKey` counts against [base].
     *  `false` if any factor is not value-relabelable (returns `null`). The value analog of
     *  [isAutomorphism]. */
    private fun verifyValueSwap(problem: Problem, base: Map<StructuralKey, Int>, v: Long, w: Long): Boolean {
        val swap = { x: Long ->
            if (x == v) {
                w
            } else if (x == w) {
                v
            } else {
                x
            }
        }
        return PresolveShared.matchesMultiset(problem.factors.asList(), base) { it.remapValues(swap) }
    }

    private fun domainWithin(d: IntDomain?, values: LongHashSet): Boolean {
        if (d == null) return false
        for (v in d.min..d.max) {
            if (v !in d) continue
            if (v !in values) return false
        }
        return true
    }

    /** Sentinel "variable id" marking the focal variable in a [refineColours] port signature; far
     *  above any colour id (colours are small dense counters) so it never collides with one. */
    private const val WL_FOCAL = 1_000_000_000

    // Colour-refinement signatures are [RefineKey]s (LongArray-backed, the non-string analog of
    // StructuralKey). The leading word is a *space* tag so an int and a bool variable never share a
    // colour; bool sorts below int, matching the "B" < "I" canonical order. The next word
    // discriminates the seed/signature shape so distinct logical signatures never collide.
    private const val SPACE_BOOL = 0L
    private const val SPACE_INT = 1L
    private const val SEED_DOMAIN = 0L
    private const val SEED_OBJECTIVE = 1L
    private const val SEED_BOOL = 2L
    private const val SEED_INDIVIDUALIZED = 3L
    private const val SIG_PORT = 4L
    private const val SEED_DOMAIN_SURVIVORS = 5L
    private const val SEED_OPEN_RANGE = 6L
    private const val SEED_BOOL_FIXED = 7L

    /** The values each integer column admits, as far as the lane running the search knows them. */
    private interface ColumnValues {
        /** The finite value set of column [v], or null when [v] has an open side. */
        fun finite(v: Int): IntDomain?

        /** Whether column [v] admits [value]. */
        fun contains(v: Int, value: Long): Boolean

        /** A colour seed equal for two columns exactly when they admit the same values. */
        fun seed(v: Int): RefineKey

        /** A colour seed equal for two Boolean columns exactly when they admit the same values. */
        fun boolSeed(v: Int): RefineKey
    }

    /**
     * Root-propagated domains of a finite projection.
     *
     * A Boolean the root fixes is a value no factor need carry: a presolve view's factors may no longer
     * read it at all, so only its seed keeps it from being swapped with a free or oppositely fixed one.
     */
    private class FiniteColumns(private val problem: BakedProblem) : ColumnValues {
        private val root = problem.baked as? PropagationResult.Implied

        override fun finite(v: Int): IntDomain = problem.rootIntDomain(v)

        override fun contains(v: Int, value: Long): Boolean = value in problem.rootIntDomain(v)

        override fun seed(v: Int): RefineKey = domainSeed(problem.rootIntDomain(v))

        override fun boolSeed(v: Int): RefineKey = when (root?.boolValueOrNull(v)) {
            null -> FREE_BOOL_SEED
            true -> RefineKey(longArrayOf(SPACE_BOOL, SEED_BOOL_FIXED, 1L))
            false -> RefineKey(longArrayOf(SPACE_BOOL, SEED_BOOL_FIXED, 0L))
        }
    }

    /**
     * A source model's declarations. A column with an open side declares a range and no value set, so it
     * admits every integer between its finite endpoints and is never read through the box it was stated in.
     */
    private class SourceColumns(problem: Problem) : ColumnValues {
        private val bounds = problem.intBounds
        private val closed = Array(problem.numIntVars) { v ->
            if (bounds.hasLower(v) && bounds.hasUpper(v)) {
                problem.declaredIntDomains.declaredOrNull(v) ?: IntDomain(bounds.lower(v), bounds.upper(v))
            } else {
                null
            }
        }

        override fun finite(v: Int): IntDomain? = closed[v]

        override fun contains(v: Int, value: Long): Boolean = finite(v)?.contains(value)
            ?: (
                (!bounds.hasLower(v) || value >= bounds.lower(v)) &&
                    (!bounds.hasUpper(v) || value <= bounds.upper(v))
                )

        override fun boolSeed(v: Int): RefineKey = FREE_BOOL_SEED

        override fun seed(v: Int): RefineKey = finite(v)?.let(::domainSeed) ?: RefineKey(
            longArrayOf(
                SPACE_INT,
                SEED_OPEN_RANGE,
                if (bounds.hasLower(v)) 1L else 0L,
                if (bounds.hasLower(v)) bounds.lower(v) else 0L,
                if (bounds.hasUpper(v)) 1L else 0L,
                if (bounds.hasUpper(v)) bounds.upper(v) else 0L,
            ),
        )
    }

    private val FREE_BOOL_SEED = RefineKey(longArrayOf(SPACE_BOOL, SEED_BOOL))

    /** Domain signature so only variables with the *same* domain (bounds and holes) can group. */
    private fun domainSeed(d: IntDomain): RefineKey {
        // Build the word array directly — this is computed for every int variable on every symmetry
        // search, and a boxed `ArrayList<Long>` per variable (one box per bound and per hole) was a
        // measurable cost on wide, holey domains. Holes collect into a primitive list first.
        //
        // Fingerprint by whichever of the holes or the survivors is the smaller set: a wide-but-sparse
        // domain (few survivors over a span reaching millions) has O(span) holes but O(size) survivors, so
        // the hole form would allocate and hash an O(span) key on every refinement step. The two forms
        // carry a distinct marker word so they never collide; within a form, equal domains yield equal keys
        // and distinct domains distinct keys, so the colour partition — and thus every generator found — is
        // identical to the all-holes seed, at O(min(holes, survivors)) rather than O(span).
        // Only the survivor form for a domain that can hand over its values: a wide domain has no
        // span to walk, so it takes the hole form below (forEachHole is empty/small for the wide
        // reps), never allocating a span-sized array.
        val survivors = d.spanOrNull()
        if (survivors != null && survivors.size <= d.holeCount) {
            val words = LongArray(4 + survivors.size)
            words[0] = SPACE_INT
            words[1] = SEED_DOMAIN_SURVIVORS
            words[2] = d.min
            words[3] = d.max
            var i = 4
            survivors.forEach { words[i++] = it }
            return RefineKey(words)
        }
        val holes = LongArrayList()
        d.forEachHole { holes.add(it) }
        val words = LongArray(5 + holes.size)
        words[0] = SPACE_INT
        words[1] = SEED_DOMAIN
        words[2] = d.min
        words[3] = d.max
        words[4] = holes.size.toLong()
        for (i in 0 until holes.size) words[5 + i] = holes[i]
        return RefineKey(words)
    }

    /** A distinguished-fixpoint seed for objective variable [v] in [space] (each is its own colour). */
    private fun objectiveSeed(space: Long, v: Int): RefineKey =
        RefineKey(longArrayOf(space, SEED_OBJECTIVE, v.toLong()))

    /**
     * Colour refinement seeding verified-symmetry candidates. Two variables
     * can be interchangeable only if they share a colour (colour is an automorphism invariant),
     * so the colour classes are the candidate groups — finer than grouping ints by domain and all
     * bools together. Returns `(intColour, boolColour)`, parallel to the variable ids.
     *
     * Initial colour separates kinds, distinct domains, and each objective variable (a distinguished
     * fixed point). Each round refines a variable's colour by its current colour plus, for every
     * incident factor, that factor's `Factor.structuralKey` computed with the focal variable
     * remapped to [WL_FOCAL] and every other variable to its current colour — the refinement "edge"
     * signature, derived generically for any keyed factor with no per-type code. Iterated to a
     * fixpoint (partition stops refining). Soundness never rests on this: the pairwise/block verifier
     * re-checks every candidate, so a wrong colouring can only miss symmetries, never invent one.
     */
    private fun refineColours(
        problem: Problem,
        columns: ColumnValues,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): Pair<IntArray, IntArray> {
        val seedInt = Array(problem.numIntVars) { v ->
            if (v in objectiveIntVars) objectiveSeed(SPACE_INT, v) else columns.seed(v)
        }
        val seedBool = Array(problem.numBoolVars) { v ->
            if (v in objectiveBoolVars) objectiveSeed(SPACE_BOOL, v) else columns.boolSeed(v)
        }
        return equitablePartition(problem, seedInt, seedBool)
    }

    /**
     * Weisfeiler–Leman refinement to an equitable partition (the colour-refinement core shared by
     * candidate seeding and the individualization–refinement generator search). [seedInt] / [seedBool]
     * are the initial colour signatures per variable — a domain seed for plain refinement, an
     * individualized-vertex marker, or an objective fixpoint seed. Colours
     * are assigned in a canonical order (sorted by signature, bool space below int) so a discrete
     * partition's colour *is* a labeling comparable across individualization branches.
     */
    private fun equitablePartition(
        problem: Problem,
        seedInt: Array<RefineKey>,
        seedBool: Array<RefineKey>,
        budget: IntArray? = null,
        cancellation: Cancellation = Cancellation.Never,
    ): Pair<IntArray, IntArray> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val intInc = Array(nInt) { IntArrayList() }
        val boolInc = Array(nBool) { IntArrayList() }
        problem.factors.forEachIndexed { fi, f ->
            for (v in f.intVars.distinct()) intInc[v].add(fi)
            for (v in f.boolVars.distinct()) boolInc[v].add(fi)
        }
        // A round visits every variable–factor incidence once (one [portSignature] port per arc); that
        // arc count is the deterministic work unit charged against the budget.
        var arcsPerRound = 0
        for (inc in intInc) arcsPerRound += inc.size
        for (inc in boolInc) arcsPerRound += inc.size
        val intColour = IntArray(nInt)
        val boolColour = IntArray(nBool)
        var numColours = assignColours(seedInt, seedBool, intColour, boolColour)
        // Working colour maps reused across all port queries in a round (rebuilt each round).
        val intMap = IntArray(nInt)
        val boolMap = IntArray(nBool)
        // The remap reads these arrays, so loading a round's colours into them re-aims it in place.
        val mapping = VarRemap(boolMap, intMap)
        repeat(nInt + nBool + 1) {
            // Bail before a fresh round once the search's work budget is spent — returning the current
            // (possibly not-yet-stable) partition. Callers treat a spent budget as "stop", so a partial
            // colouring is never mistaken for a discrete one.
            if ((budget != null && budget[0] <= 0) || cancellation()) return intColour to boolColour
            for (v in 0 until nInt) intMap[v] = intColour[v]
            for (v in 0 until nBool) boolMap[v] = boolColour[v]
            val sigInt = Array(
                nInt,
            ) { v -> portSignature(problem, intInc[v], v, isBool = false, intMap, boolMap, mapping, intColour[v]) }
            val sigBool =
                Array(
                    nBool,
                ) { v -> portSignature(problem, boolInc[v], v, isBool = true, intMap, boolMap, mapping, boolColour[v]) }
            // Charge the real per-round work, not just the arc count: each round also builds an
            // O(nInt + nBool) signature array per variable space and re-groups them in [assignColours]
            // (a sort over the distinct colours). On a sparse model — few factors over many variables,
            // so arcs per round is tiny while the per-variable work dominates — charging arcs alone
            // vastly under-counts, letting the refinement run thousands of rounds (hundred_doors:
            // 10 factors, 10394 vars, ~23s at budget=0). Including the per-variable term bounds the
            // round count by the work budget as intended.
            if (budget != null) budget[0] -= arcsPerRound + nInt + nBool
            cancellation.charge(arcsPerRound.toLong() + nInt + nBool)
            val next = assignColours(sigInt, sigBool, intColour, boolColour)
            if (next == numColours) return intColour to boolColour // partition stable
            numColours = next
        }
        return intColour to boolColour
    }

    /** WL signature of variable [v] this round: its [oldColour] plus the sorted multiset of incident
     *  factor-key *hashes*, each computed with [v] remapped to [WL_FOCAL] (the focal marker) and every
     *  other variable to its current colour (already loaded into [intMap]/[boolMap]).
     *
     *  Each port contributes a single hash word rather than its full [StructuralKey] words. A factor
     *  with large constant data (a table's tuple set, an element's array) has a large key, and
     *  embedding it verbatim into every incident variable's signature each round made the copy/hash of
     *  those long arrays dominate presolve. The hash is sufficient for refinement — colour refinement
     *  only needs to tell ports apart, and a hash collision merely merges two colours (a coarser
     *  partition, never a finer one). Soundness never rests on the colouring: every candidate the search
     *  produces is re-checked by [isAutomorphism], so a collision can only cost extra verification, never
     *  admit a false symmetry. */
    private fun portSignature(
        problem: Problem,
        incident: IntArrayList,
        v: Int,
        isBool: Boolean,
        intMap: IntArray,
        boolMap: IntArray,
        mapping: VarRemap,
        oldColour: Int,
    ): RefineKey {
        val portHashes = LongArray(incident.size)
        var n = 0
        incident.forEach { fi ->
            val saved: Int
            if (isBool) {
                saved = boolMap[v]
                boolMap[v] = WL_FOCAL
            } else {
                saved = intMap[v]
                intMap[v] = WL_FOCAL
            }
            portHashes[n++] = problem.factors[fi].remapStructuralHash(mapping).toLong()
            if (isBool) boolMap[v] = saved else intMap[v] = saved
        }
        portHashes.sort() // canonical order: the signature is the multiset of port hashes
        // space | SIG_PORT | oldColour | port count | each port's key hash, in canonical order.
        val words = LongArray(4 + portHashes.size)
        words[0] = if (isBool) SPACE_BOOL else SPACE_INT
        words[1] = SIG_PORT
        words[2] = oldColour.toLong()
        words[3] = portHashes.size.toLong()
        portHashes.copyInto(words, 4)
        return RefineKey(words)
    }

    /** Re-colour every variable by its signature, writing dense ids into [intColour]/[boolColour] and
     *  returning the number of distinct colours. Ids are assigned in **canonical** order — distinct
     *  signatures sorted, bool space ([SPACE_BOOL]) below int ([SPACE_INT]) via the leading signature
     *  word — so a discrete partition's colour is a labeling comparable across individualization
     *  branches (needed by the generator search), while plain refinement callers, which only read
     *  colour *classes*, are unaffected. */
    private fun assignColours(
        sigInt: Array<RefineKey>,
        sigBool: Array<RefineKey>,
        intColour: IntArray,
        boolColour: IntArray,
    ): Int {
        val distinct = HashSet<RefineKey>()
        for (s in sigInt) distinct.add(s)
        for (s in sigBool) distinct.add(s)
        val ids = HashMap<RefineKey, Int>(distinct.size)
        for (s in distinct.sorted()) ids[s] = ids.size
        for (v in sigInt.indices) intColour[v] = ids.getValue(sigInt[v])
        for (v in sigBool.indices) boolColour[v] = ids.getValue(sigBool[v])
        return ids.size
    }

    /** Test-only view of [refineColours] with no objective variables. */
    internal fun refineColoursForTest(problem: BakedProblem): Pair<IntArray, IntArray> =
        refineColours(problem, FiniteColumns(problem), emptySet(), emptySet())

    // Three guards on the generator search: a size skip for models too large to bother, a deterministic
    // work budget on the refinement, and bailing with the generators found so far when it runs out.
    // All three are sound: skipping or stopping early only ever finds *fewer* symmetries, never invents
    // one (every returned permutation is verified by [isAutomorphism]).

    /** Skip the search when a single colour-refinement round is too expensive. One round remaps every
     *  factor once per incident variable and rebuilds its `Factor.structuralKey`, so a factor of degree
     *  `d` and key weight `w` costs `Θ(d·w)` per round and the round costs `Σ_f d_f·w_f` (for a plain
     *  factor `w ≈ d`, so this reduces to `Σ d²`; a data-heavy factor like a wide table has `w ≫ d`).
     *  Per-arc work here is a structural-key rebuild rather than a graph-edge walk, so the realistic
     *  guard is on that weighted work rather than a raw node/arc count. Above this even the first
     *  round cannot complete within
     *  [GENERATOR_WORK_BUDGET], and such large models empirically carry no verifiable variable symmetry,
     *  so the search is skipped outright. Sound — skipping only finds fewer symmetries. Calibrated
     *  against the corpus: every instance that carries verifiable symmetry has a round cost at or below a
     *  crossword grid's ≈ 3.5·10⁵, while the many-rows / many-column models that only burn a fruitless
     *  search (constraint-programming rosters, rack placement, linear systems) sit from ≈ 5·10⁵ into the
     *  tens of millions. The cap sits above the symmetry-bearing band with margin: it skips the fruitless one
     *  (~0.25–0.35s of dead search each) without dropping any symmetry the corpus actually breaks. */
    private const val GENERATOR_ROUND_COST_BUDGET = 500_000L

    /** Deterministic work budget for the whole generator search, charged per refinement arc visited
     *  (a variable's incident factor, the unit of [equitablePartition] work). A work count, not wall-clock, so it is
     *  reproducible across machines; when it runs out the search bails with what it has found. */
    private const val GENERATOR_WORK_BUDGET = 200_000

    /** Size skip for value-symmetry detection (the same idea as the generator-search skip, applied to
     *  the other half of the pass): the value-incidence scan is `O((maxValue − minValue) · numIntVars)`,
     *  so a model with a wide value span across many variables is skipped rather than scanned. Sound —
     *  skipping only forgoes value-symmetry pins, never adds an unsound one. */
    private const val VALUE_ORBIT_SCAN_BUDGET = 50_000_000L

    /** Skip value-symmetry verification when keying the factor set (summed `Factor.structuralKeyWeight`)
     *  would exceed this — a wide-table model where the per-swap re-keying dominates and a value symmetry
     *  is almost never found, the value-side analog of [GENERATOR_ROUND_COST_BUDGET]. Sound: skipping
     *  only forgoes value pins. */
    private const val VALUE_ORBIT_KEY_BUDGET = 1_000_000L

    // Estimated cost of one refinement round: each factor is remapped and re-keyed once per incident
    // variable, at its Factor.structuralKeyWeight. The [GENERATOR_ROUND_COST_BUDGET] gate on this value is
    // what breakSymmetries uses to skip the whole pass on a model too large to carry symmetry.
    private fun generatorRoundCost(problem: Problem): Long {
        var roundCost = 0L
        for (f in problem.factors) {
            val deg = (f.intVars.size + f.boolVars.size).toLong()
            roundCost += deg * f.structuralKeyWeight
        }
        return roundCost
    }

    /**
     * Generators of the constraint-graph automorphism group, found by individualization–refinement
     * over the unified variable+factor colouring. Unlike the
     * transposition/same-shape-block heuristics this catches composite symmetries and — because the
     * automorphism is verified on the whole factor multiset ([isAutomorphism]) rather than per-kind
     * factor rows — symmetries whose factors mix bool and int variables (e.g. lowered set/list
     * structure). Every returned permutation `(intMap, boolMap)` is a *verified* automorphism, so the
     * downstream orbit/lex breaking is sound by construction; an imperfect search only finds fewer.
     */
    private fun findGenerators(
        problem: Problem,
        columns: ColumnValues,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
        cancellation: Cancellation = Cancellation.Never,
    ): List<Pair<IntArray, IntArray>> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        if (nInt + nBool == 0) return emptyList()

        // Cost skip: estimate one refinement round's work as Σ_f degree·keyWeight — a factor is remapped
        // and re-keyed once per incident variable, and each rebuild costs its `Factor.structuralKeyWeight`
        // (the variables for a plain factor, plus the constant payload for a data-heavy one like a wide
        // table). Bail before starting when a single round cannot fit [GENERATOR_WORK_BUDGET]. A
        // wide-but-shallow model stays cheap; a model whose factors carry large constant data — where the
        // search would burn the budget rebuilding huge keys without finding a verifiable symmetry — is
        // skipped.
        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return emptyList()

        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList())
        val seedIntBase = Array(nInt) { v ->
            if (v in objectiveIntVars) objectiveSeed(SPACE_INT, v) else columns.seed(v)
        }
        val seedBoolBase = Array(nBool) { v ->
            if (v in objectiveBoolVars) objectiveSeed(SPACE_BOOL, v) else columns.boolSeed(v)
        }

        // One deterministic work budget for the whole search; every refinement draws from it and the
        // search stops (keeping the generators found so far) when it is spent.
        val budget = intArrayOf(GENERATOR_WORK_BUDGET)

        // Cells of the base equitable partition: only same-colour variables can be interchangeable.
        val (intColour, boolColour) = equitablePartition(problem, seedIntBase, seedBoolBase, budget, cancellation)
        val cells = MutableIntObjectMap<MutableList<Int>>()
        for (v in 0 until nInt) if (v !in objectiveIntVars) cells.getOrPut(intColour[v]) { ArrayList() }.add(v)
        for (v in 0 until nBool) {
            if (v !in objectiveBoolVars) cells.getOrPut(boolColour[v]) { ArrayList() }.add(nInt + v)
        }

        val gens = ArrayList<Pair<IntArray, IntArray>>()
        val cellMembers = ArrayList<MutableList<Int>>(cells.size)
        cells.forEach { _, members -> cellMembers.add(members) }
        for (members in cellMembers) {
            if (cancellation()) break
            if (members.size < 2 || members.size > MAX_VERIFIED_GROUP) continue
            val sorted = members.sorted()
            val r = sorted[0]
            val refLeaf = refineToDiscrete(problem, seedIntBase, seedBoolBase, r, budget, cancellation) ?: continue
            // Disjoint set over this cell's members tracks r's orbit under generators found so far, so
            // a member already in the orbit is skipped (it would only re-derive an existing element).
            val index = MutableIntIntMap()
            sorted.forEachIndexed { i, g -> index.put(g, i) }
            val orbit = IntDisjointSet(sorted.size)
            for (v in sorted) {
                if (v == r || budget[0] <= 0 || cancellation()) continue
                if (orbit.connected(index.getOrDefault(r, 0), index.getOrDefault(v, 0))) continue
                val leaf = refineToDiscrete(problem, seedIntBase, seedBoolBase, v, budget, cancellation) ?: continue
                val perm = buildPerm(refLeaf, leaf, nInt, nBool) ?: continue
                // A permutation maps solutions to solutions only if it also maps every column's values onto
                // its image's, which the factor check below does not see; the seeds are those values.
                if ((0 until nInt).any { seedIntBase[it] != seedIntBase[perm.first[it]] }) continue
                if ((0 until nBool).any { seedBoolBase[it] != seedBoolBase[perm.second[it]] }) continue
                cancellation.charge(problem.factors.size.toLong())
                if (!isAutomorphism(problem, base, perm.second, perm.first)) continue
                gens.add(perm)
                index.forEach { g, gi ->
                    val img = if (g < nInt) perm.first[g] else nInt + perm.second[g - nInt]
                    if (index.containsKey(img)) orbit.union(gi, index.getOrDefault(img, 0))
                }
            }
        }
        return gens
    }

    /**
     * Individualize the global vertex [firstIndiv] (int `v` is id `v`; bool `v` is id `nInt+v`) and
     * refine to a discrete partition, individualizing the lowest vertex of the lowest non-singleton
     * cell at each subsequent step. Both the seed marker (`"@step"`) and the target rule are canonical,
     * so two calls that individualize structurally-equal vertices produce comparable labelings. Returns
     * `leaf[rank] = globalVertex` (the canonical colour is the rank), or `null` if the budget runs out.
     */
    @Suppress("ReturnCount")
    private fun refineToDiscrete(
        problem: Problem,
        seedIntBase: Array<RefineKey>,
        seedBoolBase: Array<RefineKey>,
        firstIndiv: Int,
        budget: IntArray,
        cancellation: Cancellation = Cancellation.Never,
    ): IntArray? {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val n = nInt + nBool
        val seedInt = seedIntBase.copyOf()
        val seedBool = seedBoolBase.copyOf()
        fun individualize(globalV: Int, step: Int) {
            if (globalV < nInt) {
                seedInt[globalV] = RefineKey(longArrayOf(SPACE_INT, SEED_INDIVIDUALIZED, step.toLong()))
            } else {
                seedBool[globalV - nInt] = RefineKey(longArrayOf(SPACE_BOOL, SEED_INDIVIDUALIZED, step.toLong()))
            }
        }
        individualize(firstIndiv, 0)
        var step = 1
        while (true) {
            // The refinement itself charges the budget per arc; a spent budget (or a fired presolve
            // cancellation) means the partition below may be partial, so abandon this leaf.
            if (budget[0] <= 0 || cancellation()) return null
            val before = budget[0]
            val (ic, bc) = equitablePartition(problem, seedInt, seedBool, budget)
            cancellation.charge(before.toLong() - budget[0])
            val leaf = IntArray(n) { -1 }
            val cellSize = IntArray(n)
            for (v in 0 until nInt) {
                leaf[ic[v]] = v
                cellSize[ic[v]]++
            }
            for (v in 0 until nBool) {
                leaf[bc[v]] = nInt + v
                cellSize[bc[v]]++
            }
            var target = -1
            for (c in 0 until n) {
                if (cellSize[c] > 1) {
                    target = c
                    break
                }
            }
            if (target == -1) return leaf // discrete
            // Lowest global vertex in the target cell.
            var chosen = Int.MAX_VALUE
            for (v in 0 until nInt) if (ic[v] == target && v < chosen) chosen = v
            for (v in 0 until nBool) if (bc[v] == target && nInt + v < chosen) chosen = nInt + v
            individualize(chosen, step)
            step++
        }
    }

    /** The permutation mapping [refLeaf]'s rank-`i` vertex to [leaf]'s, split into `(intMap, boolMap)`.
     *  `null` if any rank pairs an int with a bool vertex (then it is not a kind-preserving map). */
    private fun buildPerm(refLeaf: IntArray, leaf: IntArray, nInt: Int, nBool: Int): Pair<IntArray, IntArray>? {
        val intMap = IntArray(nInt) { it }
        val boolMap = IntArray(nBool) { it }
        for (i in refLeaf.indices) {
            val a = refLeaf[i]
            val b = leaf[i]
            when {
                a < nInt && b < nInt -> intMap[a] = b
                a >= nInt && b >= nInt -> boolMap[a - nInt] = b - nInt
                else -> return null
            }
        }
        return intMap to boolMap
    }

    /**
     * Total-order chains for orbits that are *scalar* symmetric — every member individually
     * interchangeable, i.e. each adjacent single transposition `(oⱼ oⱼ₊₁)` (moving only those two) is
     * itself an automorphism. Then the orbit's symmetric group acts on the variables as singletons and
     * `o₀ ≤ o₁ ≤ …` keeps exactly one representative (sound and strictly stronger than the generator
     * lex). A lockstep matrix orbit fails the single-transposition check (swapping one cell without its
     * row is not an automorphism), so it is left to the row-wise generator lex — never column-ordered.
     */
    private fun scalarTotalOrders(
        problem: Problem,
        generators: List<Pair<IntArray, IntArray>>,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): List<Factor> {
        // Orbits come only from the generators' variable unions, so with no generators there is nothing
        // to order — return before building the (potentially expensive, e.g. wide-table) structural-key
        // multiset that the per-swap automorphism check would need.
        if (generators.isEmpty()) return emptyList()
        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList())
        val dsInt = IntDisjointSet(problem.numIntVars)
        val dsBool = IntDisjointSet(problem.numBoolVars)
        for ((intMap, boolMap) in generators) {
            for (v in intMap.indices) if (intMap[v] != v) dsInt.union(v, intMap[v])
            for (v in boolMap.indices) if (boolMap[v] != v) dsBool.union(v, boolMap[v])
        }
        val intMapId = IntArray(problem.numIntVars) { it }
        val boolMapId = IntArray(problem.numBoolVars) { it }
        fun swapAuto(map: IntArray, a: Int, b: Int): Boolean {
            map[a] = b
            map[b] = a
            val ok = isAutomorphism(problem, base, boolMapId, intMapId)
            map[a] = a
            map[b] = b
            return ok
        }
        val extra = ArrayList<Factor>()
        for (orbit in dsInt.groups()) {
            if (orbit.size < 2 || orbit.any { it in objectiveIntVars }) continue
            val o = orbit.sorted()
            if ((0 until o.size - 1).all { swapAuto(intMapId, o[it], o[it + 1]) }) {
                for (j in 0 until o.size - 1) {
                    extra.add(Linear(intArrayOf(1, -1), intArrayOf(o[j], o[j + 1]), LinearOp.LE, 0))
                }
            }
        }
        for (orbit in dsBool.groups()) {
            if (orbit.size < 2 || orbit.any { it in objectiveBoolVars }) continue
            val o = orbit.sorted()
            if ((0 until o.size - 1).all { swapAuto(boolMapId, o[it], o[it + 1]) }) {
                for (j in 0 until o.size - 1) {
                    extra.add(Clause(intArrayOf(Lit.make(o[j], false), Lit.make(o[j + 1], true))))
                }
            }
        }
        return extra
    }

    /** Whether remapping every factor through [boolMap]/[intMap] leaves the factor multiset (by
     *  structural key) unchanged — i.e. the maps encode an automorphism of the constraint set. */
    private fun isAutomorphism(
        problem: Problem,
        base: Map<StructuralKey, Int>,
        boolMap: IntArray,
        intMap: IntArray,
    ): Boolean = PresolveShared.matchesMultiset(problem.factors.asList(), base) {
        it.remap(VarRemap(boolMap, intMap))
    }

    /** Test every unordered pair within [scope] with [verify] (skipping pairs already connected) and
     *  union the verified ones in [ds], charging [pairCost] per pair tested. The shared inner step of every
     *  verified-orbit grouping. */
    private inline fun unionVerifiedPairs(
        ds: IntDisjointSet,
        scope: IntArray,
        pairCost: Long,
        cancellation: Cancellation = Cancellation.Never,
        verify: (Int, Int) -> Boolean,
    ) {
        for (i in scope.indices) {
            for (j in i + 1 until scope.size) {
                // Each pair re-keys the whole factor set; on a wide orbit this is the dominant symmetry
                // cost, so bail on a fired budget with the unions found so far (sound — fewer merged
                // orbits). Polled per pair, not per row, so the bail is prompt on a wide candidate.
                if (cancellation()) return
                val u = scope[i]
                val v = scope[j]
                if (ds.connected(u, v)) continue
                cancellation.charge(pairCost)
                if (verify(u, v)) ds.union(u, v)
            }
        }
    }
}

/**
 * A `LongArray`-backed colour-refinement signature — the non-string analog of
 * [com.eignex.klause.ir.StructuralKey]. WL refinement composes signatures by appending integer
 * words (variable colours and folded-in factor keys) rather than building and hashing decimal
 * strings, which dominated presolve CPU and allocation on large structured models. Structural
 * [equals]/[hashCode] make it a hash key; [compareTo] gives the canonical total order colour ids are
 * assigned in.
 */
internal class RefineKey(private val words: LongArray) : Comparable<RefineKey> {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RefineKey && words.contentEquals(other.words))

    // Immutable, so the content hash is memoised — a colour signature is hashed once per `assignColours`
    // HashSet/HashMap pass and re-hashed every WL round. `0` is the not-yet-computed sentinel.
    private var cachedHash = 0

    override fun hashCode(): Int {
        var h = cachedHash
        if (h == 0) {
            h = words.contentHashCode()
            cachedHash = h
        }
        return h
    }

    override fun compareTo(other: RefineKey): Int {
        val shared = minOf(words.size, other.words.size)
        for (i in 0 until shared) {
            if (words[i] != other.words[i]) return if (words[i] < other.words[i]) -1 else 1
        }
        return words.size - other.words.size
    }
}
