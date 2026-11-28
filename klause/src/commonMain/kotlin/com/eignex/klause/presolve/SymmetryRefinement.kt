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

// Marks the focal variable in a port signature; far above any dense colour id, so it never collides.
private const val WL_FOCAL = 1_000_000_000

// The leading [RefineKey] word is a space tag so an int and a bool variable never share a colour (bool
// sorts below int); the next word tags the seed or signature shape so distinct shapes never collide.
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
private const val SEED_PARTITION = 8L

internal fun partitionSeed(space: Long, colour: Int): RefineKey =
    RefineKey(longArrayOf(space, SEED_PARTITION, colour.toLong()))

// The values each integer column admits, as far as the lane running the search knows them.
internal interface SymmetryColumns {
    fun active(v: Int): Boolean = true

    // Null when [v] has an open side.
    fun finite(v: Int): IntDomain?

    fun contains(v: Int, value: Long): Boolean

    // Equal for two columns exactly when they admit the same values.
    fun seed(v: Int): RefineKey

    // Equal for two Boolean columns exactly when they admit the same values.
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

// A root-fixed Boolean may no longer be read by any factor, so only its seed keeps it from being swapped
// with a free or oppositely fixed one.
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

// A column with an open side declares only a range, never read through the box it was stated in.
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

// Only variables with the same domain (bounds and holes) can group.
private fun domainSeed(d: IntDomain): RefineKey {
    // Fingerprint by the smaller of holes and survivors, so a wide sparse domain costs
    // O(min(holes, survivors)) rather than O(span). Distinct marker words keep the two forms apart.
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

// Each objective variable is its own colour.
internal fun objectiveSeed(space: Long, v: Int): RefineKey =
    RefineKey(longArrayOf(space, SEED_OBJECTIVE, v.toLong()))

// Colour refinement seeding symmetry candidates: interchangeable variables share a colour, so colour
// classes are the candidate groups. Seeds separate kinds, domains and each objective variable. Soundness
// never rests on this: every candidate is re-verified against the factors, so a wrong colouring can only
// miss symmetries. Returns `(intColour, boolColour)`.
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

// Weisfeiler–Leman refinement to an equitable partition, shared by candidate seeding and the
// individualization–refinement generator search. Colours are assigned in canonical order, so a discrete
// partition's colouring is a labeling comparable across individualization branches.
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
    val arcsPerRound = incidence.arcs
    val intColour = IntArray(nInt)
    val boolColour = IntArray(nBool)
    var numColours = assignColours(seedInt, seedBool, intColour, boolColour)
    val intMap = IntArray(nInt)
    val boolMap = IntArray(nBool)
    // The remap reads these arrays, so loading a round's colours into them re-aims it in place.
    val mapping = VarRemap.borrowed(boolMap, intMap)
    repeat(nInt + nBool + 1) {
        // Callers treat a spent budget as a stop, so the partial colouring returned here is never mistaken
        // for a discrete one.
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
        if (next == numColours) return intColour to boolColour
        numColours = next
    }
    return intColour to boolColour
}

/**
 * [oldColour] plus the sorted incident factor-key hashes with [v] remapped to [WL_FOCAL]. One hash word per
 * port rather than the full [StructuralKey] keeps large keys (a table's tuples) cheap; a collision only
 * coarsens the partition, and every candidate is verified, so it never admits a false symmetry.
 */
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

// Dense ids in canonical order (sorted signatures, bool below int), so a discrete partition is a labeling
// comparable across individualization branches. Returns the number of distinct colours.
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

// [compareTo] is the canonical order colour ids are assigned in.
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
