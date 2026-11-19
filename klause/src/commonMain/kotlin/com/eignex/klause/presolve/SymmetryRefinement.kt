package com.eignex.klause.presolve

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

/** Sentinel "variable id" marking the focal variable in a [refineColours] port signature; far
 *  above any colour id (colours are small dense counters) so it never collides with one. */
private const val WL_FOCAL = 1_000_000_000

// Colour-refinement signatures are [RefineKey]s (LongArray-backed, the non-string analog of
// StructuralKey). The leading word is a *space* tag so an int and a bool variable never share a
// colour; bool sorts below int, matching the "B" < "I" canonical order. The next word
// discriminates the seed/signature shape so distinct logical signatures never collide.
internal const val SPACE_BOOL = 0L
internal const val SPACE_INT = 1L
private const val SEED_DOMAIN = 0L
private const val SEED_OBJECTIVE = 1L
private const val SEED_BOOL = 2L
internal const val SEED_INDIVIDUALIZED = 3L
private const val SIG_PORT = 4L
private const val SEED_DOMAIN_SURVIVORS = 5L
private const val SEED_OPEN_RANGE = 6L
private const val SEED_BOOL_FIXED = 7L

/** The values each integer column admits, as far as the lane running the search knows them. */
internal interface SymmetryColumns {
    fun active(v: Int): Boolean = true

    /** The finite value set of column [v], or null when [v] has an open side. */
    fun finite(v: Int): IntDomain?

    /** Whether column [v] admits [value]. */
    fun contains(v: Int, value: Long): Boolean

    /** A colour seed equal for two columns exactly when they admit the same values. */
    fun seed(v: Int): RefineKey

    /** A colour seed equal for two Boolean columns exactly when they admit the same values. */
    fun boolSeed(v: Int): RefineKey
}

internal class ActiveSymmetryColumns(
    private val columns: SymmetryColumns,
    private val readInts: BooleanArray,
    private val readBools: BooleanArray,
) : SymmetryColumns by columns {
    override fun active(v: Int): Boolean = readInts[v]

    override fun finite(v: Int): IntDomain? = if (readInts[v]) columns.finite(v) else null

    override fun contains(v: Int, value: Long): Boolean = readInts[v] && columns.contains(v, value)

    fun heldInts(objective: Set<Int>): Set<Int> = objective + readInts.indices.filter { !readInts[it] }

    fun heldBools(objective: Set<Int>): Set<Int> = objective + readBools.indices.filter { !readBools[it] }
}

internal fun activeColumns(
    problem: Problem, columns: SymmetryColumns, cancellation: ExecutionControl,
): ActiveSymmetryColumns? =
    PresolveScan(cancellation).use { activeColumnsWithin(problem, columns, it) }

private fun activeColumnsWithin(problem: Problem, columns: SymmetryColumns,
    cancellation: PresolveScan): ActiveSymmetryColumns? {
    cancellation.charge(problem.numIntVars.toLong() + problem.numBoolVars)
    if (cancellation.poll()) return null
    val readInts = BooleanArray(problem.numIntVars)
    val readBools = BooleanArray(problem.numBoolVars)
    for (factor in problem.factors) {
        cancellation.charge(1L)
        if (cancellation.poll()) return null
        for (variable in factor.intVars) {
            cancellation.charge(1L)
            if ( cancellation.poll()) return null
            readInts[variable] = true
        }
        for (variable in factor.boolVars) {
            cancellation.charge(1L)
            if ( cancellation.poll()) return null
            readBools[variable] = true
        }
    }
    // Reconstructed columns have no remaining readers; posting a break would put them back into search.
    if (cancellation()) return null
    return ActiveSymmetryColumns(columns, readInts, readBools)
}

/**
 * Root-propagated domains of a finite projection.
 *
 * A Boolean the root fixes is a value no factor need carry: a presolve view's factors may no longer
 * read it at all, so only its seed keeps it from being swapped with a free or oppositely fixed one.
 */
internal class FiniteSymmetryColumns(private val problem: BakedProblem) : SymmetryColumns {
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
internal class SourceSymmetryColumns(problem: Problem) : SymmetryColumns {
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
    // Fingerprint by whichever of the holes or the survivors is the smaller set, so a wide-but-sparse
    // domain costs O(min(holes, survivors)) rather than O(span). The two forms carry distinct marker
    // words so they never collide; within a form equal domains yield equal keys. A wide domain with no
    // span to hand over takes the hole form.
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
internal fun objectiveSeed(space: Long, v: Int): RefineKey =
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
 * fixpoint. Soundness never rests on this: every candidate is re-verified against the factors, so a
 * wrong colouring can only miss symmetries, never invent one.
 */
internal fun refineColours(
    problem: Problem,
    columns: SymmetryColumns,
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

internal class SymmetryIncidence(val problem: Problem) {
    val ints = Array(problem.numIntVars) { IntArrayList() }
    val bools = Array(problem.numBoolVars) { IntArrayList() }
    val arcs: Int

    init {
        problem.factors.forEachIndexed { fi, factor ->
            for (v in factor.intVars.distinct()) ints[v].add(fi)
            for (v in factor.boolVars.distinct()) bools[v].add(fi)
        }
        arcs = ints.sumOf { it.size } + bools.sumOf { it.size }
    }
}

/**
 * Weisfeiler–Leman refinement to an equitable partition (the colour-refinement core shared by
 * candidate seeding and the individualization–refinement generator search). [seedInt] / [seedBool]
 * are the initial colour signatures per variable — a domain seed for plain refinement, an
 * individualized-vertex marker, or an objective fixpoint seed. Colours
 * are assigned in a canonical order (sorted by signature, bool space below int) so a discrete
 * partition's colour *is* a labeling comparable across individualization branches.
 */
internal fun equitablePartition(
    problem: Problem,
    seedInt: Array<RefineKey>,
    seedBool: Array<RefineKey>,
    budget: IntArray? = null,
    cancellation: ExecutionControl = ExecutionControl.Never,
    incidence: SymmetryIncidence = SymmetryIncidence(problem),
): Pair<IntArray, IntArray> {
    require(incidence.problem === problem)
    val nInt = problem.numIntVars
    val nBool = problem.numBoolVars
    val intInc = incidence.ints
    val boolInc = incidence.bools
    // A round visits every variable–factor incidence once (one [portSignature] port per arc); that
    // arc count is the deterministic work unit charged against the budget.
    val arcsPerRound = incidence.arcs
    val intColour = IntArray(nInt)
    val boolColour = IntArray(nBool)
    var numColours = assignColours(seedInt, seedBool, intColour, boolColour)
    // Working colour maps reused across all port queries in a round (rebuilt each round).
    val intMap = IntArray(nInt)
    val boolMap = IntArray(nBool)
    // The remap reads these arrays, so loading a round's colours into them re-aims it in place.
    val mapping = VarRemap.borrowed(boolMap, intMap)
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
        // Signature construction and color regrouping cost O(nInt + nBool) even on sparse models;
        // charge those variables alongside arcs so the work budget bounds refinement rounds.
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
 *  Each port contributes a single hash word rather than its full [StructuralKey] words, since a factor
 *  with large constant data (a table's tuple set) has a large key. A hash collision merely merges two
 *  colours (a coarser partition); every candidate undergoes an automorphism check, so a collision
 *  can only cost extra verification, never admit a false symmetry. */
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
    portHashes.sort()
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

/**
 * A `LongArray`-backed colour-refinement signature, composed of integer words (variable colours and
 * folded-in factor key hashes). Structural [equals]/[hashCode] make it a hash key; [compareTo] gives the
 * canonical total order colour ids are assigned in.
 */
internal class RefineKey(private val words: LongArray) : Comparable<RefineKey> {
    fun withCoefficient(coefficient: Long?): RefineKey =
        if (coefficient == null) this else RefineKey(words + coefficient)

    override fun equals(other: Any?): Boolean =
        this === other || (other is RefineKey && words.contentEquals(other.words))

    // Immutable, so the content hash is memoised. `0` is the not-yet-computed sentinel.
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
