package com.eignex.klause.formats.xcsp3

import com.eignex.klause.ir.Factor
import com.eignex.util.collections.DynamicBits
import com.eignex.util.collections.MutableIntLongMap

// Constraint classes are inherited from every enclosing `block`, `group` and `slide`. `symmetryBreaking` and
// `redundantConstraints` mark the factors a classified constraint emits as implied; every other class is inert.
internal class Xcsp3ConstraintClasses(private val factors: List<Factor>) {
    private var symmetry = false
    private var redundant = false
    private var scopeStart = 0

    // Indexed by factor; allocated by the first classified constraint.
    private var implied: DynamicBits? = null

    // Defined variable -> packed `[start, end)` factor range of a shared definition a classified
    // constraint emitted first.
    private val definitions = MutableIntLongMap()

    private var lastClasses: String? = null
    private var lastFlags = 0

    var hasSymmetryBreaking = false
        private set

    private val classified: Boolean get() = symmetry || redundant

    inline fun within(classes: String, body: () -> Unit) {
        if (classes.isEmpty()) return body()
        val saved = enter(classes)
        try {
            body()
        } finally {
            exit(saved)
        }
    }

    // Returns the state [exit] restores.
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

    // The outermost classified scope marks every factor it emitted.
    fun exit(saved: Int) {
        if (saved == 0 && classified) mark(scopeStart, factors.size)
        symmetry = (saved and SYMMETRY) != 0
        redundant = (saved and REDUNDANT) != 0
    }

    // A lookup from factor [start] that emitted nothing reused an earlier definition. An unclassified reuse unmarks
    // it: shared definition factors are implied only while every constraint reading them is classified.
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
        implied?.clearRange((range ushr Int.SIZE_BITS).toInt(), range.toInt())
    }

    private fun isImplied(index: Int): Boolean = implied?.let { index < it.size && it[index] } == true

    fun inherit(index: Int, sources: IntArray) {
        if (sources.isNotEmpty() && sources.all(::isImplied)) mark(index, index + 1)
    }

    fun compact(removed: Set<Int>) {
        val marks = implied ?: return
        var next = 0
        for (index in 0 until marks.size) if (index !in removed) marks[next++] = marks[index]
        marks.resize(next)
    }

    fun mask(count: Int): BooleanArray? {
        val marks = implied ?: return null
        val mask = BooleanArray(count) { it < marks.size && marks[it] }
        return if (mask.any { it }) mask else null
    }

    private fun mark(start: Int, end: Int) {
        if (start < end) (implied ?: DynamicBits().also { implied = it }).setRange(start, end)
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

        // The specification's names, and the hyphenated spellings generated instances carry.
        val SYMMETRY_CLASSES = setOf("symmetryBreaking", "symmetry-breaking")
        val REDUNDANT_CLASSES = setOf("redundantConstraints", "redundant-constraints")
    }
}
