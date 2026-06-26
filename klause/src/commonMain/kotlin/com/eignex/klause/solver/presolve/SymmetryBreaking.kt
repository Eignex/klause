package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.global.ValuePrecede
import com.eignex.klause.solver.factor.symmetry.SymmetryHandling
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntDisjointSet

internal object SymmetryBreaking {

    /** Cap on a verified-symmetry candidate group; larger groups are skipped (#367 size guard). */
    private const val MAX_VERIFIED_GROUP = 40

    /**
     * Symmetry breaking by detecting interchangeable variables (#317, #334). A variable transposition
     * is broken only when swapping the two variables maps the factor multiset onto itself — verified by
     * remapping every factor and comparing [Factor.structuralKey] counts, so it is sound by
     * construction. Candidate groups come from Weisfeiler–Leman colour refinement (only same-colour
     * variables can be interchangeable); each candidate swap is then checked. Ordering a verified orbit
     * (`x₀ ≤ x₁ ≤ …` for ints, `¬gⱼ ∨ gⱼ₊₁` for bools) keeps exactly one representative per orbit —
     * sound (never removes the last solution of an orbit).
     *
     * Variables in [objectiveIntVars] / [objectiveBoolVars] are excluded so an asymmetric objective
     * can't be cut — keep those sets empty for pure feasibility. (Per the issue policy this runs by
     * default except in a pure local-search portfolio.)
     *
     * Also breaks value symmetry ([breakValueSymmetry]).
     */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
    ): Problem {
        // Symmetry breaking is a one-shot transformation: once a [SymmetryHandling] factor is present
        // the generators have been found and posted. The presolve round engine re-enables this pass
        // whenever another pass changes the problem, but re-running would (a) re-search from scratch and
        // (b) have to remap (conjugate every generator) and re-key the heavy [SymmetryHandling] factor it
        // just added — an O(rounds) blow-up that dominated presolve on symmetric models. So detect once.
        if (problem.factors.any { it is SymmetryHandling }) return problem

        // Generator-based detection: individualization–refinement over the unified variable+factor
        // colouring yields verified automorphism generators (catching composite and bool/int-mixed
        // symmetries the per-kind heuristics miss). The whole group is handled dynamically by one
        // [SymmetryHandling] factor whose [SymmetryPropagator] enforces every generator's lex-leader
        // `V ≤lex σ(V)` at each search node — sound (the orbit lex-minimum satisfies it) and with no
        // static enumeration of group elements.
        val generators = findGenerators(problem, objectiveIntVars, objectiveBoolVars)
        // For an orbit whose members are *individually* interchangeable (each single transposition is
        // itself an automorphism — a scalar symmetric group, not a lockstep matrix), the full total
        // order is sound and strictly stronger than the generator lex, so post it too.
        val scalarLex = scalarTotalOrders(problem, generators, objectiveIntVars, objectiveBoolVars)
        val valuePins = breakValueSymmetry(problem, objectiveIntVars)
        if (generators.isEmpty() && scalarLex.isEmpty() && valuePins.isEmpty()) {
            return problem
        }
        val extra = ArrayList<Factor>()
        if (generators.isNotEmpty()) extra.add(SymmetryHandling(generators))
        extra.addAll(scalarLex)
        extra.addAll(valuePins)
        return PresolveShared.rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /**
     * Value symmetry breaking (#366, #374). A permutation of values that maps every domain to itself
     * and the factor set to itself is a symmetry. Candidate orbits are values with the same
     * domain-incidence (the set of variables whose domain contains them) — so any transposition
     * within an orbit already maps every domain to itself. Each transposition is then *verified*
     * against the factors: applying it via [Factor.remapValues] and comparing the [Factor.structuralKey]
     * multiset proves the swap is a symmetry, the value analog of the [Factor.remap]-based automorphism check
     * (#334). Transpositions generate the full symmetric group on a verified orbit, so one variable
     * whose domain lies entirely within an orbit is pinned to the orbit minimum — a sound break (a
     * solution can always be relabeled within the orbit so that variable takes the minimum).
     *
     * When every factor is value-anonymous ([Factor.isValueAnonymous] — AllDifferent), verification is
     * skipped: anonymity means every relabeling is a symmetry, so the whole incidence group is one
     * orbit (the #366 fast path). Otherwise verification widens detection to problems with
     * value-relabelable factors (GlobalCardinality, Table, …) that the anonymity gate switched off; a
     * factor that is unkeyed or returns `null` from [Factor.remapValues] conservatively blocks it.
     *
     * The stronger Law–Lee value precedence (ordering first-occurrences across all variables) needs
     * auxiliary variables and a var-growing reconstruction, and is a follow-up.
     */
    private fun breakValueSymmetry(problem: Problem, objectiveIntVars: Set<Int>): List<Factor> {
        val orbits = verifiedValueOrbits(problem) ?: return emptyList()
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            val orbitSet = orbit.toHashSet()
            val internal = (0 until problem.numIntVars)
                .filter { it !in objectiveIntVars && domainWithin(problem.intDomains[it], orbitSet) }
            when {
                // Law–Lee value precedence (the default value break): introduce the orbit's values in
                // sorted order across the interchangeable variables — one representative per value-symmetry
                // class survives, strictly stronger than pinning a single variable. Needs ≥ 2 variables to
                // chain; with one it degrades to the single-variable pin (the only sound break available).
                internal.size >= 2 -> {
                    val sortedValues = orbit.sorted()
                    val seq = internal.toIntArray()
                    for (i in 0 until sortedValues.size - 1) {
                        extra.add(ValuePrecede(sortedValues[i], sortedValues[i + 1], seq))
                    }
                }

                internal.size == 1 -> extra.add(
                    Linear(intArrayOf(1), intArrayOf(internal[0]), LinearOp.EQ, orbit.min()),
                )
            }
        }
        return extra
    }

    /**
     * The verified-interchangeable value orbits shared by [breakValueSymmetry] and
     * [breakValuePrecedence]: values grouped by domain-incidence, then refined against the factors
     * ([verifyValueOrbits]) unless every factor is value-anonymous (the #366 fast path skips
     * verification). Returns the orbits of size ≥ 2, or `null` when nothing is eligible (no int
     * variables, an unkeyed factor on the verified path, or an empty value range) — each caller maps
     * `null` to its own "post nothing" result. Objective-variable exclusion happens at each caller's
     * per-orbit action, not here.
     */
    private fun verifiedValueOrbits(problem: Problem): List<List<Int>>? {
        if (problem.numIntVars == 0) return null
        val allAnonymous = problem.factors.all { it.isValueAnonymous() }
        // The anonymous fast path skips the multiset entirely; otherwise key every factor.
        val base: Map<StructuralKey, Int>? = if (allAnonymous) {
            null
        } else {
            PresolveShared.structuralKeyMultiset(problem.factors.asList())
        }
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (d in problem.intDomains) {
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return null
        // Size skip: the incidence scan below visits every value in [lo, hi] against every int
        // variable, so a wide span across many variables is too costly to be worth the value pins.
        if ((hi.toLong() - lo + 1) * problem.numIntVars > VALUE_ORBIT_SCAN_BUDGET) return null
        // Group values by domain-incidence signature: same set of containing variables ⇒ a candidate
        // orbit (a swap within it maps every domain to itself). The incident variable ids, in
        // ascending order, are the signature words.
        val incidence = HashMap<RefineKey, MutableList<Int>>()
        for (value in lo..hi) {
            val sig = ArrayList<Long>()
            for (x in 0 until problem.numIntVars) if (value in problem.intDomains[x]) sig.add(x.toLong())
            if (sig.isNotEmpty()) incidence.getOrPut(RefineKey(sig.toLongArray())) { ArrayList() }.add(value)
        }
        val orbits = ArrayList<List<Int>>()
        for (candidate in incidence.values) {
            if (candidate.size < 2) continue
            // Anonymous: the whole group is one orbit. Otherwise refine into verified-equal orbits.
            val refined =
                if (allAnonymous) listOf(candidate) else verifyValueOrbits(problem, requireNotNull(base), candidate)
            for (orbit in refined) if (orbit.size >= 2) orbits.add(orbit)
        }
        return orbits
    }

    /**
     * Law–Lee value precedence (#374), the strong value-symmetry break, posted with the native
     * [ValuePrecede] propagator (#432). For the value-anonymous case (#366: every factor is
     * [Factor.isValueAnonymous], so any value relabeling is a symmetry), each orbit of interchangeable
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
    fun breakValuePrecedence(problem: Problem, objectiveIntVars: Set<Int> = emptySet()): Problem {
        val n = problem.numIntVars
        // A verified orbit is interchangeable; ordering its first occurrences is sound. A
        // fully-internal variable (domain ⊆ orbit) exists only when the orbit equals the whole
        // incidence group, so a split orbit simply posts nothing — never unsound.
        val orbits = verifiedValueOrbits(problem) ?: return problem
        val extra = ArrayList<Factor>()
        for (orbit in orbits) {
            val orbitSet = orbit.toHashSet()
            val seq = ArrayList<Int>()
            for (x in 0 until n) {
                if (x !in objectiveIntVars && domainWithin(problem.intDomains[x], orbitSet)) seq.add(x)
            }
            if (seq.size < 2) continue
            val sortedValues = orbit.sorted()
            val seqArray = seq.toIntArray()
            for (i in 0 until sortedValues.size - 1) {
                extra.add(ValuePrecede(sortedValues[i], sortedValues[i + 1], seqArray))
            }
        }
        if (extra.isEmpty()) return problem
        return PresolveShared.rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /** Refine a domain-incidence candidate [values] into verified-interchangeable value orbits: union
     *  the value pairs whose transposition is verified a symmetry ([verifyValueSwap]). Transpositions
     *  generate the full symmetric group on each resulting orbit. Groups beyond [MAX_VERIFIED_GROUP]
     *  are skipped (the O(n²·factors) guard, as for variables). */
    private fun verifyValueOrbits(problem: Problem, base: Map<StructuralKey, Int>, values: List<Int>): List<List<Int>> {
        val n = values.size
        if (n > MAX_VERIFIED_GROUP) return emptyList()
        val ds = IntDisjointSet(n)
        unionVerifiedPairs(ds, IntArray(n) { it }) { i, j -> verifyValueSwap(problem, base, values[i], values[j]) }
        return ds.groups().map { group -> group.map { values[it] } }
    }

    /** Whether the value transposition `(v w)` maps the factor multiset to itself — relabel every
     *  factor via [Factor.remapValues] and compare [Factor.structuralKey] counts against [base].
     *  `false` if any factor is not value-relabelable (returns `null`). The value analog of
     *  [isAutomorphism]. */
    private fun verifyValueSwap(problem: Problem, base: Map<StructuralKey, Int>, v: Int, w: Int): Boolean {
        val swap = { x: Int ->
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

    /** Whether every value in [d] lies in [values]. */
    private fun domainWithin(d: IntDomain, values: Set<Int>): Boolean {
        for (v in d.min..d.max) if (v in d && v !in values) return false
        return true
    }

    /** Sentinel "variable id" marking the focal variable in a [refineColours] port signature; far
     *  above any colour id (colours are small dense counters) so it never collides with one. */
    private const val WL_FOCAL = 1_000_000_000

    // Colour-refinement signatures are [RefineKey]s (LongArray-backed, the non-string analog of
    // StructuralKey). The leading word is a *space* tag so an int and a bool variable never share a
    // colour; bool sorts below int (preserving the former "B" < "I" canonical order). The next word
    // discriminates the seed/signature shape so distinct logical signatures never collide.
    private const val SPACE_BOOL = 0L
    private const val SPACE_INT = 1L
    private const val SEED_DOMAIN = 0L
    private const val SEED_OBJECTIVE = 1L
    private const val SEED_BOOL = 2L
    private const val SEED_INDIVIDUALIZED = 3L
    private const val SIG_PORT = 4L

    /** Domain signature so only variables with the *same* domain (bounds and holes) can group. */
    private fun domainSeed(d: IntDomain): RefineKey {
        // Build the word array directly — this is computed for every int variable on every symmetry
        // search, and a boxed `ArrayList<Long>` per variable (one box per bound and per hole) was a
        // measurable cost on wide, holey domains. Holes collect into a primitive list first.
        val holes = IntArrayList()
        d.forEachHole { holes.add(it) }
        val words = LongArray(5 + holes.size)
        words[0] = SPACE_INT
        words[1] = SEED_DOMAIN
        words[2] = d.min.toLong()
        words[3] = d.max.toLong()
        words[4] = holes.size.toLong()
        for (i in 0 until holes.size) words[5 + i] = holes[i].toLong()
        return RefineKey(words)
    }

    /** A distinguished-fixpoint seed for objective variable [v] in [space] (each is its own colour). */
    private fun objectiveSeed(space: Long, v: Int): RefineKey =
        RefineKey(longArrayOf(space, SEED_OBJECTIVE, v.toLong()))

    /**
     * Weisfeiler–Leman colour refinement (#373) seeding verified-symmetry candidates. Two variables
     * can be interchangeable only if they share a WL colour (colour is an automorphism invariant),
     * so the colour classes are the candidate groups — finer than grouping ints by domain and all
     * bools together. Returns `(intColour, boolColour)`, parallel to the variable ids.
     *
     * Initial colour separates kinds, distinct domains, and each objective variable (a distinguished
     * fixed point). Each round refines a variable's colour by its current colour plus, for every
     * incident factor, that factor's [Factor.structuralKey] computed with the focal variable
     * remapped to [WL_FOCAL] and every other variable to its current colour — the WL "edge"
     * signature, derived generically for any keyed factor with no per-type code. Iterated to a
     * fixpoint (partition stops refining). Soundness never rests on this: the pairwise/block verifier
     * re-checks every candidate, so a wrong colouring can only miss symmetries, never invent one.
     */
    private fun refineColours(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): Pair<IntArray, IntArray> {
        val seedInt = Array(problem.numIntVars) { v ->
            if (v in objectiveIntVars) objectiveSeed(SPACE_INT, v) else domainSeed(problem.intDomains[v])
        }
        val seedBool = Array(problem.numBoolVars) { v ->
            if (v in objectiveBoolVars) objectiveSeed(SPACE_BOOL, v) else RefineKey(longArrayOf(SPACE_BOOL, SEED_BOOL))
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
    ): Pair<IntArray, IntArray> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val intInc = Array(nInt) { ArrayList<Int>() }
        val boolInc = Array(nBool) { ArrayList<Int>() }
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
        repeat(nInt + nBool + 1) {
            // Bail before a fresh round once the search's work budget is spent — returning the current
            // (possibly not-yet-stable) partition. Callers treat a spent budget as "stop", so a partial
            // colouring is never mistaken for a discrete one.
            if (budget != null && budget[0] <= 0) return intColour to boolColour
            for (v in 0 until nInt) intMap[v] = intColour[v]
            for (v in 0 until nBool) boolMap[v] = boolColour[v]
            val sigInt = Array(
                nInt,
            ) { v -> portSignature(problem, intInc[v], v, isBool = false, intMap, boolMap, intColour[v]) }
            val sigBool =
                Array(
                    nBool,
                ) { v -> portSignature(problem, boolInc[v], v, isBool = true, intMap, boolMap, boolColour[v]) }
            if (budget != null) budget[0] -= arcsPerRound
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
        incident: List<Int>,
        v: Int,
        isBool: Boolean,
        intMap: IntArray,
        boolMap: IntArray,
        oldColour: Int,
    ): RefineKey {
        val portHashes = LongArray(incident.size)
        var n = 0
        for (fi in incident) {
            val saved: Int
            if (isBool) {
                saved = boolMap[v]
                boolMap[v] = WL_FOCAL
            } else {
                saved = intMap[v]
                intMap[v] = WL_FOCAL
            }
            portHashes[n++] = problem.factors[fi].remapStructuralHash(boolMap, intMap).toLong()
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

    /** Test-only view of [refineColours] with no objective variables (#373). */
    internal fun refineColoursForTest(problem: Problem): Pair<IntArray, IntArray> =
        refineColours(problem, emptySet(), emptySet())

    // Three guards on the generator search, mirroring CP-SAT's FindCpModelSymmetries
    // (ortools/sat/cp_model_symmetries.cc): a size skip for models too large to bother, a deterministic
    // work budget on the refinement, and bailing with the generators found so far when it runs out.
    // All three are sound: skipping or stopping early only ever finds *fewer* symmetries, never invents
    // one (every returned permutation is still verified by [isAutomorphism]).

    /** Skip the search when a single colour-refinement round is too expensive. One round remaps every
     *  factor once per incident variable and rebuilds its [Factor.structuralKey], so a factor of degree
     *  `d` and key weight `w` costs `Θ(d·w)` per round and the round costs `Σ_f d_f·w_f` (for a plain
     *  factor `w ≈ d`, so this reduces to `Σ d²`; a data-heavy factor like a wide table has `w ≫ d`).
     *  CP-SAT skips on raw node/arc counts (`cp_model_symmetries.cc:665,685`); klause's per-arc work is
     *  far heavier (a structural-key rebuild, not a graph-edge walk), so the realistic guard is on that
     *  weighted work, not a 1e6 node count. Above this even the first round cannot complete within
     *  [GENERATOR_WORK_BUDGET], and such large models empirically carry no verifiable variable symmetry,
     *  so the search is skipped outright. Sound — skipping only finds fewer symmetries. Calibrated
     *  against the corpus: instances that carry verifiable symmetry have a round cost up to a few
     *  hundred thousand (a crossword grid is ≈ 3.5·10⁵), while a model that only burns the search — a
     *  wide-table preference problem, a thousands-of-rows linear system — runs into the tens of millions
     *  and beyond, so this cap cleanly separates the two. */
    private const val GENERATOR_ROUND_COST_BUDGET = 1_000_000L

    /** Deterministic work budget for the whole generator search, charged per refinement arc visited
     *  (a variable's incident factor, the unit of [equitablePartition] work) — the analog of CP-SAT's
     *  `symmetry_detection_deterministic_time_limit`. A work count, not wall-clock, so it is
     *  reproducible across machines; when it runs out the search bails with what it has found. */
    private const val GENERATOR_WORK_BUDGET = 200_000

    /** Size skip for value-symmetry detection (the same idea as the generator-search skip, applied to
     *  the other half of the pass): the value-incidence scan is `O((maxValue − minValue) · numIntVars)`,
     *  so a model with a wide value span across many variables is skipped rather than scanned. Sound —
     *  skipping only forgoes value-symmetry pins, never adds an unsound one. */
    private const val VALUE_ORBIT_SCAN_BUDGET = 50_000_000L

    /**
     * Generators of the constraint-graph automorphism group, found by individualization–refinement
     * (a CP-SAT / nauty-style search) over the unified variable+factor colouring. Unlike the
     * transposition/same-shape-block heuristics this catches composite symmetries and — because the
     * automorphism is verified on the whole factor multiset ([isAutomorphism]) rather than per-kind
     * factor rows — symmetries whose factors mix bool and int variables (e.g. lowered set/list
     * structure). Every returned permutation `(intMap, boolMap)` is a *verified* automorphism, so the
     * downstream orbit/lex breaking is sound by construction; an imperfect search only finds fewer.
     */
    private fun findGenerators(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): List<Pair<IntArray, IntArray>> {
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        if (nInt + nBool == 0) return emptyList()

        // Cost skip: estimate one refinement round's work as Σ_f degree·keyWeight — a factor is remapped
        // and re-keyed once per incident variable, and each rebuild costs its [Factor.structuralKeyWeight]
        // (the variables for a plain factor, plus the constant payload for a data-heavy one like a wide
        // table). Bail before starting when a single round cannot fit [GENERATOR_WORK_BUDGET]. A
        // wide-but-shallow model stays cheap; a model whose factors carry large constant data — where the
        // search would burn the budget rebuilding huge keys without finding a verifiable symmetry — is
        // skipped.
        var roundCost = 0L
        for (f in problem.factors) {
            val deg = (f.intVars.size + f.boolVars.size).toLong()
            roundCost += deg * f.structuralKeyWeight
        }
        if (roundCost > GENERATOR_ROUND_COST_BUDGET) return emptyList()

        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList())
        val seedIntBase = Array(nInt) { v ->
            if (v in objectiveIntVars) objectiveSeed(SPACE_INT, v) else domainSeed(problem.intDomains[v])
        }
        val seedBoolBase = Array(nBool) { v ->
            if (v in objectiveBoolVars) objectiveSeed(SPACE_BOOL, v) else RefineKey(longArrayOf(SPACE_BOOL, SEED_BOOL))
        }

        // One deterministic work budget for the whole search; every refinement draws from it and the
        // search stops (keeping the generators found so far) when it is spent.
        val budget = intArrayOf(GENERATOR_WORK_BUDGET)

        // Cells of the base equitable partition: only same-colour variables can be interchangeable.
        val (intColour, boolColour) = equitablePartition(problem, seedIntBase, seedBoolBase, budget)
        val cells = HashMap<Int, MutableList<Int>>()
        for (v in 0 until nInt) if (v !in objectiveIntVars) cells.getOrPut(intColour[v]) { ArrayList() }.add(v)
        for (v in 0 until nBool) {
            if (v !in objectiveBoolVars) cells.getOrPut(boolColour[v]) { ArrayList() }.add(nInt + v)
        }

        val gens = ArrayList<Pair<IntArray, IntArray>>()
        for (members in cells.values) {
            if (members.size < 2 || members.size > MAX_VERIFIED_GROUP) continue
            val sorted = members.sorted()
            val r = sorted[0]
            val refLeaf = refineToDiscrete(problem, seedIntBase, seedBoolBase, r, budget) ?: continue
            // Disjoint set over this cell's members tracks r's orbit under generators found so far, so
            // a member already in the orbit is skipped (it would only re-derive an existing element).
            val index = HashMap<Int, Int>()
            sorted.forEachIndexed { i, g -> index[g] = i }
            val orbit = IntDisjointSet(sorted.size)
            for (v in sorted) {
                if (v == r || budget[0] <= 0) continue
                if (orbit.connected(index.getValue(r), index.getValue(v))) continue
                val leaf = refineToDiscrete(problem, seedIntBase, seedBoolBase, v, budget) ?: continue
                val perm = buildPerm(refLeaf, leaf, nInt, nBool) ?: continue
                if (!isAutomorphism(problem, base, perm.second, perm.first)) continue
                gens.add(perm)
                for ((g, gi) in index) {
                    val img = if (g < nInt) perm.first[g] else nInt + perm.second[g - nInt]
                    index[img]?.let { orbit.union(gi, it) }
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
            // The refinement itself charges the budget per arc; a spent budget means the partition
            // below may be partial, so abandon this leaf (the search then bails with what it has).
            if (budget[0] <= 0) return null
            val (ic, bc) = equitablePartition(problem, seedInt, seedBool, budget)
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
    ): Boolean = PresolveShared.matchesMultiset(problem.factors.asList(), base) { it.remap(boolMap, intMap) }

    /** Test every unordered pair within [scope] with [verify] (skipping pairs already connected) and
     *  union the verified ones in [ds]. The shared inner step of every verified-orbit grouping. */
    private inline fun unionVerifiedPairs(ds: IntDisjointSet, scope: IntArray, verify: (Int, Int) -> Boolean) {
        for (i in scope.indices) {
            for (j in i + 1 until scope.size) {
                val u = scope[i]
                val v = scope[j]
                if (!ds.connected(u, v) && verify(u, v)) ds.union(u, v)
            }
        }
    }
}

/**
 * A `LongArray`-backed colour-refinement signature — the non-string analog of
 * [com.eignex.klause.solver.StructuralKey]. WL refinement composes signatures by appending integer
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
