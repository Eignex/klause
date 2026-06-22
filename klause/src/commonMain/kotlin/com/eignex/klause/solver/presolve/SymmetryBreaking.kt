package com.eignex.klause.solver.presolve

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.factor.bool.PseudoBoolean
import com.eignex.klause.solver.factor.bool.Xor
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.global.LexLess
import com.eignex.klause.solver.factor.global.ValuePrecede
import com.eignex.klause.util.IntDisjointSet

internal object SymmetryBreaking {

    /** Cap on a verified-symmetry candidate group; larger groups are skipped (#367 size guard). */
    private const val MAX_VERIFIED_GROUP = 40

    /** Widest bool row a binary-number lex-leader can encode: `2^(m−1)` must fit in `Int`, so
     *  `m ≤ 31`. Wider rows are left unbroken (#373); their lex needs an aux-var encoding. */
    private const val MAX_BOOL_LEX_WIDTH = 31

    /**
     * Symmetry breaking by detecting interchangeable variables (#317). Two variables are
     * *provably* interchangeable when they occur in exactly the same set of factors and play a
     * symmetric role in each — equal coefficient in a [Linear], both arguments of an
     * [AllDifferent], or same-polarity / same-weight literals in a [Clause] / [Cardinality] /
     * [Xor] / [PseudoBoolean]. These factor types are all order-insensitive (sum / set / parity),
     * so any permutation of such a group is a genuine automorphism, and ordering the group
     * (`x₀ ≤ x₁ ≤ …` for ints, `¬gⱼ ∨ gⱼ₊₁` for bools) keeps exactly one representative per orbit
     * — sound (never removes the last solution of an orbit).
     *
     * The "same factor set" requirement is what makes this sound: a variable appearing in a
     * factor its candidate-partner does not is *not* interchangeable. Variables touched by any
     * other factor type are conservatively excluded. Variables in [objectiveIntVars] /
     * [objectiveBoolVars] are excluded so an asymmetric objective can't be cut — keep those sets
     * empty for pure feasibility. (Per the issue policy this runs by default except in a pure
     * local-search portfolio.)
     *
     * Scope: this catches interchangeable *variables*; matrix-row/column and value symmetries and
     * full graph-automorphism detection remain follow-ups.
     */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
    ): Problem {
        // Prefer verified detection (any factor swap proven an automorphism); fall back to the
        // sufficient same-factor-set heuristic when a factor type isn't structurally keyed.
        val verified = verifiedSymmetryOrbits(problem, objectiveIntVars, objectiveBoolVars)
        val intGroups = verified?.first ?: interchangeableIntGroups(problem, objectiveIntVars)
        val boolGroups = verified?.second ?: interchangeableBoolGroups(problem, objectiveBoolVars)
        // Block/row symmetry (#367): interchangeable blocks of int vars (e.g. matrix rows defined by
        // isomorphic factors), ordered by lex-leader. Only when verified detection is available.
        val brokenInts = intGroups.flatMap { it.toList() }.toHashSet()
        val blockLex = if (verified == null) emptyList() else verifiedBlockLex(problem, objectiveIntVars, brokenInts)
        // Bool block/row symmetry (#373): the boolean analogue — rows of bool vars defined by
        // isomorphic bool-only factors, ordered by a binary-number lex-leader.
        val brokenBools = boolGroups.flatMap { it.toList() }.toHashSet()
        val boolBlockLex = if (verified == null) {
            emptyList()
        } else {
            verifiedBoolBlockLex(
                problem,
                objectiveBoolVars,
                brokenBools,
            )
        }
        val valuePins = breakValueSymmetry(problem, objectiveIntVars)
        if (intGroups.isEmpty() && boolGroups.isEmpty() && blockLex.isEmpty() &&
            boolBlockLex.isEmpty() && valuePins.isEmpty()
        ) {
            return problem
        }
        val extra = ArrayList<Factor>()
        for (group in intGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Linear(intArrayOf(1, -1), intArrayOf(group[j], group[j + 1]), LinearOp.LE, 0))
            }
        }
        for (group in boolGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Clause(intArrayOf(Lit.make(group[j], false), Lit.make(group[j + 1], true))))
            }
        }
        extra.addAll(blockLex)
        extra.addAll(boolBlockLex)
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
            val minValue = orbit.min()
            for (x in 0 until problem.numIntVars) {
                if (x in objectiveIntVars) continue
                if (domainWithin(problem.intDomains[x], orbitSet)) {
                    extra.add(Linear(intArrayOf(1), intArrayOf(x), LinearOp.EQ, minValue))
                    break
                }
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
        // Verified path needs every factor keyed; build the base multiset (bail if any is unkeyed).
        val base: Map<StructuralKey, Int>? = if (allAnonymous) {
            null
        } else {
            PresolveShared.structuralKeyMultiset(problem.factors.asList()) ?: return null
        }
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (d in problem.intDomains) {
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        if (lo > hi) return null
        // Group values by domain-incidence signature: same set of containing variables ⇒ a candidate
        // orbit (a swap within it maps every domain to itself).
        val incidence = HashMap<String, MutableList<Int>>()
        for (value in lo..hi) {
            val sig = StringBuilder()
            for (x in 0 until problem.numIntVars) if (value in problem.intDomains[x]) sig.append(x).append(',')
            if (sig.isNotEmpty()) incidence.getOrPut(sig.toString()) { ArrayList() }.add(value)
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

    /**
     * Verified interchangeable-variable detection (#334): a variable transposition is a genuine
     * symmetry iff swapping the two variables maps the factor multiset onto itself. Each candidate
     * swap is *checked* by remapping every factor and comparing structural keys — so it catches
     * symmetries the same-factor-set heuristic misses (variables in different but isomorphic
     * factors, matrix rows), and is sound by construction. Returns `null` when any factor lacks a
     * [Factor.structuralKey] (then the caller uses the conservative heuristic). Returns the int and
     * bool orbits (size ≥ 2) otherwise; objective variables are excluded.
     *
     * Candidate groups come from Weisfeiler–Leman colour refinement ([refineColours], #373): only
     * same-colour variables can be interchangeable, so the colour classes are exactly the candidate
     * groups, finer than the old domain-only / single-bool-group partition. This finds more (finer
     * classes fit under the [MAX_VERIFIED_GROUP] size guard that would skip a large coarse group)
     * and verifies fewer impossible pairs — and stays sound because each candidate is still verified.
     */
    private fun verifiedSymmetryOrbits(
        problem: Problem,
        objectiveIntVars: Set<Int>,
        objectiveBoolVars: Set<Int>,
    ): Pair<List<IntArray>, List<IntArray>>? {
        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList()) ?: return null
        val intMap = identityIntMap(problem)
        val boolMap = identityBoolMap(problem)

        val (intColour, boolColour) = refineColours(problem, objectiveIntVars, objectiveBoolVars)
        val intCandidates = HashMap<Int, MutableList<Int>>()
        for (v in 0 until problem.numIntVars) {
            if (v !in objectiveIntVars) intCandidates.getOrPut(intColour[v]) { ArrayList() }.add(v)
        }
        val intOrbits = buildVerifiedOrbits(problem.numIntVars, intCandidates.values.toList()) { u, v ->
            withSwap(intMap, u, v) { isAutomorphism(problem, base, boolMap, intMap) }
        }
        val boolCandidates = HashMap<Int, MutableList<Int>>()
        for (v in 0 until problem.numBoolVars) {
            if (v !in objectiveBoolVars) boolCandidates.getOrPut(boolColour[v]) { ArrayList() }.add(v)
        }
        val boolOrbits = buildVerifiedOrbits(problem.numBoolVars, boolCandidates.values.toList()) { u, v ->
            withSwap(boolMap, u, v) { isAutomorphism(problem, base, boolMap, intMap) }
        }
        return intOrbits to boolOrbits
    }

    /** Sentinel "variable id" marking the focal variable in a [refineColours] port signature; far
     *  above any colour id (colours are small dense counters) so it never collides with one. */
    private const val WL_FOCAL = 1_000_000_000

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
        val nInt = problem.numIntVars
        val nBool = problem.numBoolVars
        val intInc = Array(nInt) { ArrayList<Int>() }
        val boolInc = Array(nBool) { ArrayList<Int>() }
        problem.factors.forEachIndexed { fi, f ->
            for (v in f.intVars.distinct()) intInc[v].add(fi)
            for (v in f.boolVars.distinct()) boolInc[v].add(fi)
        }
        val intColour = IntArray(nInt)
        val boolColour = IntArray(nBool)
        val initInt = Array(nInt) { v ->
            if (v in objectiveIntVars) "o$v" else domainKey(problem.intDomains[v])
        }
        val initBool = Array(nBool) { v -> if (v in objectiveBoolVars) "o$v" else "b" }
        var numColours = assignColours(initInt, initBool, intColour, boolColour)
        // Working colour maps reused across all port queries in a round (rebuilt each round).
        val intMap = IntArray(nInt)
        val boolMap = IntArray(nBool)
        repeat(nInt + nBool + 1) {
            for (v in 0 until nInt) intMap[v] = intColour[v]
            for (v in 0 until nBool) boolMap[v] = boolColour[v]
            val sigInt = Array(
                nInt,
            ) { v -> portSignature(problem, intInc[v], v, isBool = false, intMap, boolMap, intColour[v]) }
            val sigBool =
                Array(
                    nBool,
                ) { v -> portSignature(problem, boolInc[v], v, isBool = true, intMap, boolMap, boolColour[v]) }
            val next = assignColours(sigInt, sigBool, intColour, boolColour)
            if (next == numColours) return intColour to boolColour // partition stable
            numColours = next
        }
        return intColour to boolColour
    }

    /** WL signature of variable [v] this round: its [oldColour] plus the sorted multiset of incident
     *  factor keys, each computed with [v] remapped to [WL_FOCAL] (the focal marker) and every other
     *  variable to its current colour (already loaded into [intMap]/[boolMap]). */
    private fun portSignature(
        problem: Problem,
        incident: List<Int>,
        v: Int,
        isBool: Boolean,
        intMap: IntArray,
        boolMap: IntArray,
        oldColour: Int,
    ): String {
        val ports = ArrayList<String>(incident.size)
        for (fi in incident) {
            val saved: Int
            if (isBool) {
                saved = boolMap[v]
                boolMap[v] = WL_FOCAL
            } else {
                saved = intMap[v]
                intMap[v] = WL_FOCAL
            }
            ports.add(problem.factors[fi].remap(boolMap, intMap).structuralKey()?.toString() ?: "?")
            if (isBool) boolMap[v] = saved else intMap[v] = saved
        }
        ports.sort()
        return "$oldColour|" + ports.joinToString(";")
    }

    /** Re-colour every variable by its signature, writing dense ids into [intColour]/[boolColour] and
     *  returning the number of distinct colours. Int and bool signatures are kept in disjoint spaces
     *  (prefixed) so the two kinds never share a colour. */
    private fun assignColours(
        sigInt: Array<String>,
        sigBool: Array<String>,
        intColour: IntArray,
        boolColour: IntArray,
    ): Int {
        val ids = HashMap<String, Int>()
        for (v in sigInt.indices) intColour[v] = ids.getOrPut("I" + sigInt[v]) { ids.size }
        for (v in sigBool.indices) boolColour[v] = ids.getOrPut("B" + sigBool[v]) { ids.size }
        return ids.size
    }

    /** Test-only view of [refineColours] with no objective variables (#373). */
    internal fun refineColoursForTest(problem: Problem): Pair<IntArray, IntArray> =
        refineColours(problem, emptySet(), emptySet())

    /** Whether remapping every factor through [boolMap]/[intMap] leaves the factor multiset (by
     *  structural key) unchanged — i.e. the maps encode an automorphism of the constraint set. */
    private fun isAutomorphism(
        problem: Problem,
        base: Map<StructuralKey, Int>,
        boolMap: IntArray,
        intMap: IntArray,
    ): Boolean = PresolveShared.matchesMultiset(problem.factors.asList(), base) { it.remap(boolMap, intMap) }

    /** A fresh identity remap over the int variables (`map[v] == v`). Callers mutate a few slots for a
     *  remap/automorphism check via [withSwap], which restores them. */
    private fun identityIntMap(problem: Problem) = IntArray(problem.numIntVars) { it }

    /** A fresh identity remap over the bool variables. */
    private fun identityBoolMap(problem: Problem) = IntArray(problem.numBoolVars) { it }

    /** Apply the transposition `u ↔ v` to [map], run [body], then restore both slots to their identity
     *  (the value they must hold on entry). Centralises the apply/verify/restore dance so a missed
     *  restore — which would silently corrupt later automorphism checks — can't happen per call site. */
    private inline fun withSwap(map: IntArray, u: Int, v: Int, body: () -> Boolean): Boolean {
        map[u] = v
        map[v] = u
        val ok = body()
        map[u] = u
        map[v] = v
        return ok
    }

    /** Apply the position-wise block swap `a[k] ↔ b[k]` to [map], run [body], then restore each
     *  touched slot to its identity. [a] and [b] must be disjoint, equal length, and identity on entry. */
    private inline fun withSwap(map: IntArray, a: IntArray, b: IntArray, body: () -> Boolean): Boolean {
        for (k in a.indices) {
            map[a[k]] = b[k]
            map[b[k]] = a[k]
        }
        val ok = body()
        for (k in a.indices) {
            map[a[k]] = a[k]
            map[b[k]] = b[k]
        }
        return ok
    }

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

    /**
     * Verified block / row symmetry (#367): groups of int variables defined by *isomorphic* factors
     * (e.g. matrix rows, each an AllDifferent over a distinct row) are interchangeable as blocks.
     * Candidate blocks are the sorted-variable sets of factors sharing a canonical shape; a block
     * pair is verified an automorphism via [isAutomorphism] (with position-wise equal domains), and
     * verified-equal blocks are ordered by a lex-leader [LexLess] chain. Skips bool-touching factors,
     * objective variables, and variables already broken as single-var orbits ([alreadyBroken]) so
     * row and cell breaking don't interact unsoundly.
     */
    private fun verifiedBlockLex(problem: Problem, objectiveIntVars: Set<Int>, alreadyBroken: Set<Int>): List<Factor> {
        val intMap = identityIntMap(problem)
        val boolIdentity = identityBoolMap(problem)
        return verifiedBlockLexShared(
            problem = problem,
            objectiveVars = objectiveIntVars,
            alreadyBroken = alreadyBroken,
            varsOf = { it.intVars },
            wrongKind = { it.boolVars.isNotEmpty() || it.intVars.isEmpty() },
            blockEligible = { true },
            shapeOf = { f, block -> canonicalShape(problem, f, block, isBool = false) },
            swapVerified = { base, a, b ->
                blockSwapVerified(
                    intMap,
                    a,
                    b,
                    positionOk = { x, y -> domainKey(problem.intDomains[x]) == domainKey(problem.intDomains[y]) },
                ) { isAutomorphism(problem, base, boolIdentity, intMap) }
            },
            emit = { a, b -> LexLess(a, b, strict = false) },
        )
    }

    /** Shared skeleton for verified block / row symmetry over either variable kind: bucket the
     *  candidate blocks by canonical shape, union the verified-swap pairs, and order each resulting
     *  class by a lex-leader chain. The kind-specific bits — which variables a factor contributes,
     *  which factors to skip, the per-row width guard, the shape key, the swap verifier (which folds
     *  in the int-only domain check), and the lex-leader factor — are supplied by the callers. */
    @Suppress("LongParameterList")
    private inline fun verifiedBlockLexShared(
        problem: Problem,
        objectiveVars: Set<Int>,
        alreadyBroken: Set<Int>,
        varsOf: (Factor) -> IntArray,
        wrongKind: (Factor) -> Boolean,
        blockEligible: (IntArray) -> Boolean,
        shapeOf: (Factor, IntArray) -> StructuralKey?,
        swapVerified: (Map<StructuralKey, Int>, IntArray, IntArray) -> Boolean,
        emit: (IntArray, IntArray) -> Factor,
    ): List<Factor> {
        val base = PresolveShared.structuralKeyMultiset(problem.factors.asList()) ?: return emptyList()
        val byShape = HashMap<StructuralKey, MutableList<IntArray>>()
        for (f in problem.factors) {
            if (wrongKind(f)) continue
            val block = varsOf(f).distinct().sorted().toIntArray()
            if (!blockEligible(block)) continue
            if (block.any { it in objectiveVars || it in alreadyBroken }) continue
            val shape = shapeOf(f, block) ?: continue
            byShape.getOrPut(shape) { ArrayList() }.add(block)
        }
        val extra = ArrayList<Factor>()
        for ((_, blocks) in byShape) {
            if (blocks.size < 2 || blocks.size > MAX_VERIFIED_GROUP) continue
            val ds = IntDisjointSet(blocks.size)
            unionVerifiedPairs(ds, IntArray(blocks.size) { it }) { i, j -> swapVerified(base, blocks[i], blocks[j]) }
            for (cls in ds.groups()) {
                val ordered = cls.map { blocks[it] }.sortedBy { it[0] }
                for (k in 0 until ordered.size - 1) extra.add(emit(ordered[k], ordered[k + 1]))
            }
        }
        return extra
    }

    /** Canonical structure key for a block of variables (bool when [isBool], else int): remap its
     *  (sorted) variables to `0..k-1` with the other kind left identity, so two isomorphic factors
     *  over disjoint variables share a key. `null` if the factor isn't keyed. */
    private fun canonicalShape(problem: Problem, f: Factor, block: IntArray, isBool: Boolean): StructuralKey? {
        val boolMap = identityBoolMap(problem)
        val intMap = identityIntMap(problem)
        val target = if (isBool) boolMap else intMap
        for (k in block.indices) target[block[k]] = k
        return f.remap(boolMap, intMap).structuralKey()
    }

    /** Whether swapping disjoint blocks [a] and [b] position-wise (`a[k] ↔ b[k]`) on the working
     *  remap [map] (identity outside the blocks) is an automorphism. [positionOk] is an extra
     *  per-position precondition — int blocks require position-wise equal domains (not encoded in
     *  factors, so checked here); bool blocks have none. Overlapping blocks are rejected (a swap
     *  would tangle). [automorphic] runs the structural check with the swap applied. */
    private inline fun blockSwapVerified(
        map: IntArray,
        a: IntArray,
        b: IntArray,
        positionOk: (Int, Int) -> Boolean,
        automorphic: () -> Boolean,
    ): Boolean {
        if (a.size != b.size) return false
        for (k in a.indices) {
            if (a[k] in b) return false // overlapping blocks: swap would tangle
            if (!positionOk(a[k], b[k])) return false
        }
        return withSwap(map, a, b, automorphic)
    }

    /**
     * Verified bool-block lex (#373): the boolean analogue of [verifiedBlockLex]. Blocks of Boolean
     * variables defined by *isomorphic* bool-only factors (rows of a 0/1 matrix) are interchangeable
     * as blocks; verified-equal blocks are ordered by a lexicographic-leader chain. Skips factors that
     * touch int variables, objective bools, and bools already broken as single-var orbits
     * ([alreadyBroken]) so row and cell breaking don't interact unsoundly.
     *
     * A Boolean lex-leader `a ≤ₗₑₓ b` is posted as a [PseudoBoolean] (see [boolLexLeader]): reading
     * each id-sorted row as a binary number with the first position most-significant, lexicographic
     * order on equal-length 0/1 vectors is exactly numeric order. The weights are powers of two, so a
     * row wider than [MAX_BOOL_LEX_WIDTH] (where `2^(m−1)` overflows `Int`) is skipped — sound, just
     * unbroken; the aux-variable lex encoding for wider rows is a follow-up.
     */
    private fun verifiedBoolBlockLex(
        problem: Problem,
        objectiveBoolVars: Set<Int>,
        alreadyBroken: Set<Int>,
    ): List<Factor> {
        val boolMap = identityBoolMap(problem)
        val intIdentity = identityIntMap(problem)
        return verifiedBlockLexShared(
            problem = problem,
            objectiveVars = objectiveBoolVars,
            alreadyBroken = alreadyBroken,
            varsOf = { it.boolVars },
            wrongKind = { it.intVars.isNotEmpty() || it.boolVars.isEmpty() },
            blockEligible = { it.size <= MAX_BOOL_LEX_WIDTH },
            shapeOf = { f, block -> canonicalShape(problem, f, block, isBool = true) },
            swapVerified = { base, a, b ->
                blockSwapVerified(boolMap, a, b, positionOk = { _, _ -> true }) {
                    isAutomorphism(problem, base, boolMap, intIdentity)
                }
            },
            emit = { a, b -> boolLexLeader(a, b) },
        )
    }

    /** Lex-leader `a ≤ₗₑₓ b` on two equal-length bool rows as a [PseudoBoolean]. Reading each row
     *  as a binary number (position 0 most-significant), lexicographic order is numeric order, so
     *  `Σ 2^(m−1−k)·a`k` − Σ 2^(m−1−k)·b`k` ≤ 0`. Callers must keep `a.size == b.size ≤`
     *  [MAX_BOOL_LEX_WIDTH] so the top weight `2^(m−1)` fits in `Int`. */
    private fun boolLexLeader(a: IntArray, b: IntArray): PseudoBoolean {
        val m = a.size
        val literals = IntArray(2 * m)
        val weights = IntArray(2 * m)
        for (k in 0 until m) {
            val w = 1 shl (m - 1 - k)
            literals[k] = Lit.make(a[k], true)
            weights[k] = w
            literals[m + k] = Lit.make(b[k], true)
            weights[m + k] = -w
        }
        return PseudoBoolean(weights, literals, PbOp.LE, 0)
    }

    /** Union the candidate variables whose pairwise transposition [verify]s as a symmetry, then
     *  return the resulting orbits of size ≥ 2 (each sorted). Transpositions generate the full
     *  symmetric group on an orbit, so a total order over it is a sound symmetry break. */
    private fun buildVerifiedOrbits(
        numVars: Int,
        candidateGroups: List<List<Int>>,
        verify: (Int, Int) -> Boolean,
    ): List<IntArray> {
        val ds = IntDisjointSet(numVars)
        for (group in candidateGroups) {
            // Size guard (#367): each group costs O(size² × factors) verifications. Skip groups
            // beyond the cap — fewer symmetries broken, never unsound.
            if (group.size > MAX_VERIFIED_GROUP) continue
            unionVerifiedPairs(ds, group.toIntArray(), verify)
        }
        val byRoot = HashMap<Int, MutableList<Int>>()
        for (group in candidateGroups) for (v in group) byRoot.getOrPut(ds.find(v)) { ArrayList() }.add(v)
        return byRoot.values.filter { it.size >= 2 }.map { it.sorted().toIntArray() }
    }

    /** Domain signature so only variables with the *same* domain (bounds and holes) can group. */
    private fun domainKey(d: IntDomain): String {
        val sb = StringBuilder()
        sb.append(d.min).append(':').append(d.max).append(':')
        d.forEachHole { sb.append(it).append('-') }
        return sb.toString()
    }

    private fun interchangeableIntGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> =
        interchangeableGroups(
            problem = problem,
            numVars = problem.numIntVars,
            objectiveVars = objectiveVars,
            fillRoles = ::fillIntRoles,
            groupKey = { v, role -> "${domainKey(problem.intDomains[v])}|$role" },
        )

    private fun interchangeableBoolGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> =
        interchangeableGroups(
            problem = problem,
            numVars = problem.numBoolVars,
            objectiveVars = objectiveVars,
            fillRoles = ::fillBoolRoles,
            groupKey = { _, role -> role },
        )

    /** Append factor [fi]'s int-variable roles (equal coefficient in a [Linear], membership in an
     *  [AllDifferent]); clear [eligible] for variables touched by any unsupported factor type. */
    private fun fillIntRoles(fi: Int, f: Factor, roles: Array<ArrayList<String>>, eligible: BooleanArray) {
        when (f) {
            is Linear -> for (i in f.vars.indices) roles[f.vars[i]].add("$fi:lin:${f.coeffs[i]}")
            is AllDifferent -> for (v in f.vars) roles[v].add("$fi:ad")
            else -> for (v in f.intVars) eligible[v] = false // unsupported factor type
        }
    }

    /** Append factor [fi]'s bool-variable roles (same-polarity / same-weight literals in a [Clause],
     *  [Cardinality], [Xor], or [PseudoBoolean]); clear [eligible] for any unsupported factor type. */
    private fun fillBoolRoles(fi: Int, f: Factor, roles: Array<ArrayList<String>>, eligible: BooleanArray) {
        when (f) {
            is Clause -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:cl:${Lit.isPositive(l)}")

            is Cardinality -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:card:${Lit.isPositive(l)}")

            is Xor -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:xor:${Lit.isPositive(l)}")

            is PseudoBoolean -> for (i in f.literals.indices) {
                roles[Lit.variable(f.literals[i])].add("$fi:pb:${f.weights[i]}:${Lit.isPositive(f.literals[i])}")
            }

            else -> for (v in f.boolVars) eligible[v] = false // unsupported factor type
        }
    }

    /** Shared skeleton for interchangeable-variable detection over either variable kind: fill each
     *  variable's per-factor role strings (clearing eligibility for unsupported factor types), then
     *  group eligible non-objective variables by their grouping key. The kind-specific bits — the
     *  per-factor role extraction and the grouping key (the int side prefixes the domain key) — are
     *  supplied by the callers. */
    private inline fun interchangeableGroups(
        problem: Problem,
        numVars: Int,
        objectiveVars: Set<Int>,
        fillRoles: (Int, Factor, Array<ArrayList<String>>, BooleanArray) -> Unit,
        groupKey: (Int, String) -> String,
    ): List<IntArray> {
        if (numVars == 0) return emptyList()
        val eligible = BooleanArray(numVars) { true }
        val roles = Array(numVars) { ArrayList<String>() }
        for (fi in problem.factors.indices) fillRoles(fi, problem.factors[fi], roles, eligible)
        val groups = HashMap<String, MutableList<Int>>()
        for (v in 0 until numVars) {
            if (!eligible[v] || v in objectiveVars) continue
            roles[v].sort()
            groups.getOrPut(groupKey(v, roles[v].joinToString(","))) { ArrayList() }.add(v)
        }
        return groups.values.filter { it.size >= 2 }.map { it.toIntArray() }
    }
}
