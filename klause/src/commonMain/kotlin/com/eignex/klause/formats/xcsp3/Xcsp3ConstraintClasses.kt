package com.eignex.klause.formats.xcsp3

import com.eignex.klause.ir.Factor
import com.eignex.klause.util.MutableIntLongMap

/**
 * The XCSP3 constraint classes the solver reads, inherited from every enclosing `block`, `group` and
 * `slide` by the constraints and templates inside it. `symmetryBreaking` declares source symmetry
 * breaking, and both it and `redundantConstraints` mark the factors a classified constraint emits as
 * implied. Every other class is inert.
 */
internal class Xcsp3ConstraintClasses(private val factors: List<Factor>) {
    private var symmetry = false
    private var redundant = false
    private var scopeStart = 0

    // Indexed by factor; allocated by the first classified constraint.
    private var implied: BooleanArray? = null

    // Defined variable -> packed `[start, end)` factor range of a shared definition a classified
    // constraint emitted first.
    private val definitions = MutableIntLongMap()

    private var lastClasses: String? = null
    private var lastFlags = 0

    /** Whether some constraint inherited the symmetry-breaking class. */
    var hasSymmetryBreaking = false
        private set

    private val classified: Boolean get() = symmetry || redundant

    /** Run [body] with [classes] added to the inherited classes. */
    inline fun within(classes: String, body: () -> Unit) {
        if (classes.isEmpty()) return body()
        val saved = enter(classes)
        try {
            body()
        } finally {
            exit(saved)
        }
    }

    /** Add [classes] to the inherited classes; returns the state [exit] restores. */
    fun enter(classes: String): Int {
        val saved = (if (symmetry) SYMMETRY else 0) or (if (redundant) REDUNDANT else 0)
        val flags = flagsOf(classes)
        if (saved == 0 && flags != 0) scopeStart = factors.size
        if ((flags and SYMMETRY) != 0) {
            symmetry = true
            hasSymmetryBreaking = true
        }
        if ((flags and REDUNDANT) != 0) redundant = true
        return saved
    }

    /** Leave the scope [enter] opened. The outermost classified scope marks every factor it emitted. */
    fun exit(saved: Int) {
        if (saved == 0 && classified) mark(scopeStart, factors.size)
        symmetry = (saved and SYMMETRY) != 0
        redundant = (saved and REDUNDANT) != 0
    }

    /**
     * Record a cached definition of [variable] whose lookup started at factor [start]. A lookup that
     * emitted no factor reused an earlier definition, and an unclassified reuse leaves the definition
     * unmarked: its factors are implied only while every constraint reading them is classified.
     */
    fun define(variable: Int, start: Int) {
        val end = factors.size
        when {
            start < end -> if (classified) definitions.put(variable, (start.toLong() shl Int.SIZE_BITS) or end.toLong())
            !classified && !definitions.isEmpty() -> release(variable)
        }
    }

    private fun release(variable: Int) {
        val range = definitions.getOrDefault(variable, -1L)
        if (range < 0L) return
        definitions.remove(variable)
        implied?.fill(false, (range ushr Int.SIZE_BITS).toInt(), range.toInt())
    }

    /** Whether factor [index] is marked implied. */
    private fun isImplied(index: Int): Boolean = implied?.let { index < it.size && it[index] } == true

    /** Mark the factor appended at [index] implied when every one of its [sources] is. */
    fun inherit(index: Int, sources: IntArray) {
        if (sources.isNotEmpty() && sources.all(::isImplied)) mark(index, index + 1)
    }

    /** Drop the marks of the [removed] factors, keeping the survivors' order. */
    fun compact(removed: Set<Int>) {
        val marks = implied ?: return
        var next = 0
        for (index in marks.indices) if (index !in removed) marks[next++] = marks[index]
        marks.fill(false, next)
    }

    /** The per-factor implied flags over [count] factors, or null when no factor is implied. */
    fun mask(count: Int): BooleanArray? {
        val marks = implied ?: return null
        val mask = marks.copyOf(count)
        return if (mask.any { it }) mask else null
    }

    private fun mark(start: Int, end: Int) {
        if (start < end) withCapacity(end).fill(true, start, end)
    }

    private fun withCapacity(size: Int): BooleanArray {
        val marks = implied
        if (marks != null && marks.size >= size) return marks
        val grown = BooleanArray(maxOf(size, MIN_CAPACITY, 2 * (marks?.size ?: 0)))
        marks?.copyInto(grown)
        implied = grown
        return grown
    }

    // A group template's attribute value is one string shared by every row, so the last parse is reused.
    private fun flagsOf(classes: String): Int {
        if (classes === lastClasses) return lastFlags
        var flags = 0
        for (name in classes.splitWs()) {
            when (name) {
                in SYMMETRY_CLASSES -> flags = flags or SYMMETRY
                in REDUNDANT_CLASSES -> flags = flags or REDUNDANT
            }
        }
        lastClasses = classes
        lastFlags = flags
        return flags
    }

    private companion object {
        const val SYMMETRY = 1
        const val REDUNDANT = 2
        const val MIN_CAPACITY = 16

        // The specification's names, and the hyphenated spellings generated instances carry.
        val SYMMETRY_CLASSES = setOf("symmetryBreaking", "symmetry-breaking")
        val REDUNDANT_CLASSES = setOf("redundantConstraints", "redundant-constraints")
    }
}
