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
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntDisjointSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableIntObjectMap

internal object SymmetryBreaking {

    // Verification is quadratic in the group size; skipping only forgoes symmetries.
    private const val MAX_VERIFIED_GROUP = 40

    // Breaks a variable transposition only when it maps the factor multiset onto itself, verified by
    // comparing remapped `Factor.structuralKey` counts, so it is sound by construction. Candidates come from
    // colour refinement and individualization–refinement (McKay and Piperno, "Practical graph isomorphism,
    // II", J. Symbolic Computation 60, 2014, https://doi.org/10.1016/j.jsc.2013.09.003). Ordering a verified
    // orbit keeps one representative. Objective variables are excluded so an asymmetric objective is never
    // cut. Also breaks value symmetry ([breakValueSymmetry]).
    fun breakSymmetries(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta {
        val extra =
            breakings(problem, FiniteSymmetryColumns(problem), objectiveIntVars, objectiveBoolVars, cancellation)
        return if (extra.isEmpty()) PassDelta() else PassDelta(addedFactors = extra)
    }

    fun supportsObjectiveSymmetries(problem: BakedProblem, context: PresolveContext): Boolean =
        context.linearObjective != null && !context.solutionSetSensitive && !context.modelBreaksSymmetry &&
            problem.numRealVars == 0 && !problem.semantics.relaxesRows(0) &&
            context.linearObjective.realCoefficientValues.all { it == 0.0 }

    fun breakObjectiveSymmetries(problem: BakedProblem, context: PresolveContext): PassDelta {
        if (!supportsObjectiveSymmetries(problem, context)) return PassDelta()
        val objective = requireNotNull(context.linearObjective)
        val ints = objective.intCoefficientValues
        val bools = objective.boolWeightValues
        context.cancellation.charge(ints.size.toLong() + bools.size)
        if (context.cancellation()) return PassDelta()
        val objectiveInts = ints.indices.filter { ints[it] != 0L }.toSet()
        val objectiveBools = bools.indices.filter { bools[it] != 0L }.toSet()
        val extra = breakings(
            problem, FiniteSymmetryColumns(problem), objectiveInts, objectiveBools,
            context.cancellation, objective,
        )
        return if (extra.isEmpty()) PassDelta() else PassDelta(addedFactors = extra)
    }

    // Two columns are interchangeable only when their declarations match; a value orbit groups values by
    // which columns admit them, open columns included. Only breaks some theory can own are posted (scalar
    // total orders, Boolean orders, the single-column value pin), since a CP-only factor over an open column
    // leaves it no lane; the generator lex and value-precedence chain wait for the finite lane. Total orders
    // need only transpositions, so pairs inside each colour class are tested directly.
    fun breakSourceSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SourceDelta {
        val bounds = problem.intBounds
        if (problem.numRealVars == 0 &&
            (0 until problem.numIntVars).all { bounds.hasLower(it) && bounds.hasUpper(it) }
        ) return SourceDelta()
        if (problem.factors.any { it is SymmetryHandling }) return SourceDelta()
        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return SourceDelta()
        val columns = activeColumns(problem, SourceSymmetryColumns(problem), cancellation) ?: return SourceDelta()
        val heldInts = columns.heldInts(objectiveIntVars)
        val heldBools = columns.heldBools(objectiveBoolVars)
        val extra = ArrayList<Factor>()
        extra.addAll(transpositionOrders(problem, columns, heldInts, heldBools, cancellation))
        extra.addAll(breakValueSymmetry(problem, columns, heldInts, cancellation, chainPrecedence = false))
        return if (extra.isEmpty()) SourceDelta() else SourceDelta(addedFactors = extra)
    }

    private fun breakings(
        problem: Problem,
        columns: SymmetryColumns,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
        cancellation: ExecutionControl,
        objective: LinearObjective? = null,
    ): List<Factor> {
        // One-shot: once a [SymmetryHandling] factor is present the generators have been posted. The round
        // engine re-enables this pass whenever another pass changes the problem, and re-running would
        // re-search from scratch and re-key the heavy [SymmetryHandling] factor it just added.
        if (problem.factors.any { it is SymmetryHandling }) return emptyList()

        // A model too large for even one generator-refinement round ([GENERATOR_ROUND_COST_BUDGET]) is
        // skipped outright, value / scalar heuristics included: such models rarely carry verifiable
        // symmetry, and the value-orbit keying alone is O(factors). Skipping only forgoes a reduction.
        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return emptyList()

        // The whole group is handled dynamically by one [SymmetryHandling] factor whose SymmetryPropagator
        // enforces every generator's lex-leader `V ≤lex σ(V)` at each search node, sound because the orbit
        // lex-minimum satisfies it (Crawford, Ginsberg, Luks and Roy, "Symmetry-breaking predicates for
        // search problems", KR 1996).
        val active = activeColumns(problem, columns, cancellation) ?: return emptyList()
        val heldInts = active.heldInts(objectiveIntVars)
        val heldBools = active.heldBools(objectiveBoolVars)
        val variableHeldInts = if (objective == null) heldInts else active.heldInts(emptySet())
        val variableHeldBools = if (objective == null) heldBools else active.heldBools(emptySet())
        val generators = findGenerators(problem, active, variableHeldInts, variableHeldBools, cancellation, objective)
        // For an orbit whose members are *individually* interchangeable (each single transposition is
        // itself an automorphism — a scalar symmetric group, not a lockstep matrix), the full total
        // order is sound and strictly stronger than the generator lex, so post it too.
        val scalarLex = scalarTotalOrders(
            problem, generators, variableHeldInts, variableHeldBools, objective, cancellation,
        )
        val valuePins = breakValueSymmetry(problem, active, heldInts, cancellation, chainPrecedence = true)
        val extra = ArrayList<Factor>()
        if (generators.isNotEmpty()) {
            val order = objective?.let { cost ->
                val nInt = problem.numIntVars
                (0 until nInt + problem.numBoolVars).sortedBy { v ->
                    val coefficient = if (v < nInt) cost.intCoefficientValues.getOrElse(v) { 0L }
                    else cost.boolWeightValues.getOrElse(v - nInt) { 0L }
                    coefficient == 0L
                }.toIntArray()
            }
            extra.add(SymmetryHandling(generators, order))
        }
        extra.addAll(scalarLex)
        extra.addAll(valuePins)
        return extra
    }

    // Transpositions connecting a set generate its whole symmetric group, so ordering each union of verified
    // same-colour pairs keeps one representative per orbit. An exhausted budget only finds fewer.
    private fun transpositionOrders(
        problem: Problem,
        columns: SymmetryColumns,
        heldInts: Set<Int>,
        heldBools: Set<Int>,
        cancellation: ExecutionControl,
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
        val mapping = VarRemap.borrowed(boolMap, intMap)
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

    private fun colourCells(colour: IntArray, held: Set<Int>): List<IntArray> {
        val cells = MutableIntObjectMap<IntArrayList>()
        for (v in colour.indices) if (v !in held) cells.getOrPut(colour[v]) { IntArrayList() }.add(v)
        val out = ArrayList<IntArray>()
        cells.forEach { _, members -> if (members.size in 2..MAX_VERIFIED_GROUP) out.add(members.toIntArray()) }
        out.sortBy { it[0] }
        return out
    }

    private fun verifiedGroups(
        problem: Problem,
        cell: IntArray,
        incident: Array<IntArrayList>,
        identity: IntArray,
        mapping: VarRemap,
        cancellation: ExecutionControl,
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

    // Only factors reading [a] or [b] can change under the swap. [identity] is restored before returning.
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

    // A value permutation mapping every domain and the factor set to itself is a symmetry. Candidate orbits
    // share domain incidence, so a swap within one maps every domain to itself; each swap is verified via
    // `Factor.remapValues`. One variable whose domain lies within a verified orbit is pinned to the orbit
    // minimum, since any solution can be relabeled so. If every factor is value-anonymous, verification is
    // skipped; an unkeyed or non-relabelable factor blocks it.
    private fun breakValueSymmetry(
        problem: Problem,
        columns: SymmetryColumns,
        objectiveIntVars: Set<Int>,
        cancellation: ExecutionControl,
        chainPrecedence: Boolean,
    ): List<Factor> {
        val orbits = verifiedValueOrbits(problem, columns, cancellation) ?: return emptyList()
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            if (objectiveIntVars.any { columns.contains(it, orbit.first()) }) continue
            val orbitSet = LongHashSet()
            orbit.forEach { orbitSet.add(it) }
            val internal = (0 until problem.numIntVars)
                .filter { it !in objectiveIntVars && domainWithin(columns.finite(it), orbitSet) }
            when {
                // Value precedence (see [breakValuePrecedence]): one representative per value-symmetry class
                // survives, strictly stronger than pinning a single variable. With one variable it degrades
                // to the single-variable pin.
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

    // Values grouped by domain incidence, then verified unless every factor is value-anonymous. Orbits of
    // size ≥ 2, or null when nothing is eligible; callers handle objective-variable exclusion.
    private fun verifiedValueOrbits(
        problem: Problem,
        columns: SymmetryColumns,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): List<List<Long>>? {
        if (problem.numIntVars == 0 || cancellation()) return null
        val active = (0 until problem.numIntVars).filter { columns.active(it) }
        if (active.isEmpty()) return null
        val allAnonymous = problem.factors.all { it.isValueAnonymous() }
        // Keying wide-table factor sets dominates while almost never yielding a value symmetry, so bail
        // before keying when the multiset would be that expensive; skipping only forgoes value pins.
        if (!allAnonymous) {
            var keyCost = 0L
            for (f in problem.factors) {
                keyCost += f.structuralKeyWeight.toLong()
                if (keyCost > VALUE_ORBIT_KEY_BUDGET) return null
            }
        }
        // Keyed lazily, so a candidate-free model or a fired budget never pays for it.
        val base: Map<StructuralKey, Int> by lazy { PresolveShared.structuralKeyMultiset(problem.factors.asList()) }
        var lo = Long.MAX_VALUE
        var hi = Long.MIN_VALUE
        for (v in active) {
            val d = columns.finite(v) ?: continue
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return null
        // The incidence scan visits every value in [lo, hi] against every int variable. Written as
        // `hi > lo + perVarSpan` to stay overflow-safe at the Long extremes.
        val perVarSpan = VALUE_ORBIT_SCAN_BUDGET / active.size
        if (hi > lo + perVarSpan) return null
        // Same set of containing variables ⇒ a candidate orbit; the ascending variable ids are the signature.
        val incidence = HashMap<RefineKey, MutableList<Long>>()
        for (value in lo..hi) {
            // Returning what is grouped so far only forgoes value pins.
            if (cancellation()) return null
            cancellation.charge(active.size.toLong())
            val sig = LongArrayList()
            for (x in active) if (columns.contains(x, value)) sig.add(x.toLong())
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

    // Value precedence via the native [ValuePrecede] propagator (Law and Lee, "Global Constraints for Integer
    // and Set Value Precedence", CP 2004, https://doi.org/10.1007/978-3-540-30201-8_28): each verified orbit's
    // values are introduced in sorted order over the variables whose domain lies within it. Every solution
    // can be relabeled into this form, so one representative per class survives. Objective variables are
    // excluded.
    fun breakValuePrecedence(
        problem: BakedProblem,
        objectiveIntVars: Set<Int> = emptySet(),
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): PassDelta {
        val n = problem.numIntVars
        val columns = activeColumns(problem, FiniteSymmetryColumns(problem), cancellation) ?: return PassDelta()
        val heldInts = columns.heldInts(objectiveIntVars)
        val orbits = verifiedValueOrbits(problem, columns, cancellation) ?: return PassDelta()
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            if (cancellation()) break
            if (heldInts.any { columns.contains(it, orbit.first()) }) continue
            val orbitSet = LongHashSet()
            orbit.forEach { orbitSet.add(it) }
            val seq = IntArrayList()
            for (x in 0 until n) {
                if (x !in heldInts && domainWithin(columns.finite(x), orbitSet)) seq.add(x)
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

    // Unions the value pairs whose transposition verifies; groups beyond [MAX_VERIFIED_GROUP] are skipped.
    private fun verifyValueOrbits(
        problem: Problem,
        base: Map<StructuralKey, Int>,
        values: List<Long>,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): List<List<Long>> {
        val n = values.size
        if (n > MAX_VERIFIED_GROUP) return emptyList()
        val ds = IntDisjointSet(n)
        unionVerifiedPairs(ds, IntArray(n) { it }, problem.factors.size.toLong(), cancellation) { i, j ->
            verifyValueSwap(problem, base, values[i], values[j])
        }
        return ds.groups().map { group -> group.map { values[it] } }
    }

    // False if any factor is not value-relabelable.
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

    internal fun refineColoursForTest(problem: BakedProblem): Pair<IntArray, IntArray> =
        refineColours(problem, FiniteSymmetryColumns(problem), emptySet(), emptySet())

    // Guards on the generator search: a size skip, a deterministic work budget, and bailing with what was
    // found. All are sound: every returned permutation is verified by [isAutomorphism].

    // A factor of degree `d` and key weight `w` costs `Θ(d·w)` per refinement round, and a data-heavy
    // factor such as a wide table has `w ≫ d`.
    private const val GENERATOR_ROUND_COST_BUDGET = 500_000L

    // Charged per refinement arc; a work count rather than wall-clock, so it is reproducible.
    private const val GENERATOR_WORK_BUDGET = 200_000

    // The value-incidence scan is `O((maxValue − minValue) · numIntVars)`.
    private const val VALUE_ORBIT_SCAN_BUDGET = 50_000_000L

    // Bound on summed `Factor.structuralKeyWeight` for value-symmetry verification.
    private const val VALUE_ORBIT_KEY_BUDGET = 1_000_000L

    private fun generatorRoundCost(problem: Problem): Long {
        var roundCost = 0L
        for (f in problem.factors) {
            val deg = (f.intVars.size + f.boolVars.size).toLong()
            roundCost += deg * f.structuralKeyWeight
        }
        return roundCost
    }

    // Individualization–refinement over the unified variable+factor colouring. Unlike single transpositions
    // this finds composite symmetries and ones mixing bool and int variables; every returned
    // `(intMap, boolMap)` is a verified automorphism.
    private fun findGenerators(
        problem: Problem,
        columns: SymmetryColumns,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
        cancellation: ExecutionControl = ExecutionControl.Never,
        objective: LinearObjective? = null,
    ): List<Pair<IntArray, IntArray>> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        if (nInt + nBool == 0) return emptyList()

        if (generatorRoundCost(problem) > GENERATOR_ROUND_COST_BUDGET) return emptyList()

        val seedIntBase = Array(nInt) { v ->
            if (v in objectiveIntVars) objectiveSeed(SPACE_INT, v) else columns.seed(v)
                .withCoefficient(objective?.intCoefficientValues?.getOrElse(v) { 0L })
        }
        val seedBoolBase = Array(nBool) { v ->
            if (v in objectiveBoolVars) objectiveSeed(SPACE_BOOL, v) else columns.boolSeed(v)
                .withCoefficient(objective?.boolWeightValues?.getOrElse(v) { 0L })
        }

        // Singleton seeds cannot share an orbit; avoid keying and refining an asymmetric cost vector.
        if (seedIntBase.toSet().size == nInt && seedBoolBase.toSet().size == nBool) return emptyList()

        val budget = intArrayOf(GENERATOR_WORK_BUDGET)

        // Cells of the base equitable partition: only same-colour variables can be interchangeable.
        val incidence = SymmetryIncidence(problem)
        val (intColour, boolColour) = equitablePartition(
            problem, seedIntBase, seedBoolBase, budget, cancellation, incidence,
        )
        val cells = MutableIntObjectMap<MutableList<Int>>()
        for (v in 0 until nInt) if (v !in objectiveIntVars) cells.getOrPut(intColour[v]) { ArrayList() }.add(v)
        for (v in 0 until nBool) {
            if (v !in objectiveBoolVars) cells.getOrPut(boolColour[v]) { ArrayList() }.add(nInt + v)
        }
        if (cancellation() || budget[0] <= 0) return emptyList()
        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList())

        val gens = ArrayList<Pair<IntArray, IntArray>>()
        val cellMembers = ArrayList<MutableList<Int>>(cells.size)
        cells.forEach { _, members -> cellMembers.add(members) }
        for (members in cellMembers) {
            if (cancellation()) break
            if (members.size < 2 || members.size > MAX_VERIFIED_GROUP) continue
            val sorted = members.sorted()
            val r = sorted[0]
            val refLeaf = refineToDiscrete(problem, incidence, intColour, boolColour, r, budget, cancellation)
                ?: continue
            // Disjoint set over this cell's members tracks r's orbit under generators found so far, so
            // a member already in the orbit is skipped (it would only re-derive an existing element).
            val index = MutableIntIntMap()
            sorted.forEachIndexed { i, g -> index.put(g, i) }
            val orbit = IntDisjointSet(sorted.size)
            for (v in sorted) {
                if (v == r || budget[0] <= 0 || cancellation()) continue
                if (orbit.connected(index.getOrDefault(r, 0), index.getOrDefault(v, 0))) continue
                val leaf = refineToDiscrete(problem, incidence, intColour, boolColour, v, budget, cancellation)
                    ?: continue
                val perm = buildPerm(refLeaf, leaf, nInt, nBool) ?: continue
                // A permutation maps solutions to solutions only if it also maps every column's values onto
                // its image's, which the factor check below does not see; the seeds are those values.
                if ((0 until nInt).any { seedIntBase[it] != seedIntBase[perm.first[it]] }) continue
                if ((0 until nBool).any { seedBoolBase[it] != seedBoolBase[perm.second[it]] }) continue
                cancellation.charge(problem.factors.size.toLong() + if (objective == null) 0 else nInt + nBool)
                if (cancellation()) break
                if (!preservesObjective(objective, perm.first, perm.second)) continue
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

    // Individualizes [firstIndiv] (int `v` is id `v`, bool `v` is `nInt + v`), then the lowest vertex of the
    // lowest non-singleton cell until discrete. Both rules are canonical, so leaves are comparable labelings.
    // Returns `leaf(rank) = globalVertex`, or null when the budget runs out.
    @Suppress("ReturnCount")
    private fun refineToDiscrete(
        problem: Problem,
        incidence: SymmetryIncidence,
        intBaseColour: IntArray,
        boolBaseColour: IntArray,
        firstIndiv: Int,
        budget: IntArray,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): IntArray? {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val n = nInt + nBool
        val seedInt = Array(nInt) { partitionSeed(SPACE_INT, intBaseColour[it]) }
        val seedBool = Array(nBool) { partitionSeed(SPACE_BOOL, boolBaseColour[it]) }
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
            val (ic, bc) = equitablePartition(problem, seedInt, seedBool, budget, incidence = incidence)
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
            if (target == -1) return leaf
            var chosen = Int.MAX_VALUE
            for (v in 0 until nInt) if (ic[v] == target && v < chosen) chosen = v
            for (v in 0 until nBool) if (bc[v] == target && nInt + v < chosen) chosen = nInt + v
            // Refinement only splits cells; retain those splits instead of reconstructing them from the root seeds.
            for (v in 0 until nInt) seedInt[v] = partitionSeed(SPACE_INT, ic[v])
            for (v in 0 until nBool) seedBool[v] = partitionSeed(SPACE_BOOL, bc[v])
            individualize(chosen, step)
            step++
        }
    }

    // Null if a rank pairs an int with a bool vertex.
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

    // When every adjacent single transposition of an orbit is an automorphism, `o₀ ≤ o₁ ≤ …` is sound and
    // strictly stronger than the generator lex. A lockstep matrix orbit fails that check and is left to the
    // row-wise generator lex.
    private fun scalarTotalOrders(
        problem: Problem,
        generators: List<Pair<IntArray, IntArray>>,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
        objective: LinearObjective? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): List<Factor> {
        // Skips building the (potentially expensive) structural-key multiset when there is nothing to order.
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
            cancellation.charge(problem.factors.size.toLong() + problem.numIntVars + problem.numBoolVars)
            if (cancellation()) return false
            map[a] = b
            map[b] = a
            val ok = preservesObjective(objective, intMapId, boolMapId) &&
                isAutomorphism(problem, base, boolMapId, intMapId)
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

    private fun preservesObjective(objective: LinearObjective?, intMap: IntArray, boolMap: IntArray): Boolean {
        if (objective == null) return true
        val ints = objective.intCoefficientValues
        val bools = objective.boolWeightValues
        return intMap.indices.all { ints.getOrElse(it) { 0L } == ints.getOrElse(intMap[it]) { 0L } } &&
            boolMap.indices.all { bools.getOrElse(it) { 0L } == bools.getOrElse(boolMap[it]) { 0L } }
    }

    private fun isAutomorphism(
        problem: Problem,
        base: Map<StructuralKey, Int>,
        boolMap: IntArray,
        intMap: IntArray,
    ): Boolean {
        val mapping = VarRemap.borrowed(boolMap, intMap)
        return PresolveShared.matchesMultiset(problem.factors.asList(), base) { it.remap(mapping) }
    }

    // Charges [pairCost] per pair tested, skipping pairs already connected.
    private inline fun unionVerifiedPairs(
        ds: IntDisjointSet,
        scope: IntArray,
        pairCost: Long,
        cancellation: ExecutionControl = ExecutionControl.Never,
        verify: (Int, Int) -> Boolean,
    ) {
        for (i in scope.indices) {
            for (j in i + 1 until scope.size) {
                // Each pair may re-key the whole factor set, so the budget is polled per pair; bailing keeps
                // the unions found so far.
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
