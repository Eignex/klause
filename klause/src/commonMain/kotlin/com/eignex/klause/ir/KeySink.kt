package com.eignex.klause.ir

/**
 * A single declaration of a factor's structural key, consumed twice: [MaterializingKeySink] builds the
 * canonical [StructuralKey]; [HashingKeySink] folds the hash of the key the same
 * factor would have *after* a variable remap, without allocating either the remapped factor or the key
 * (`remapStructuralHash`). The two sinks emit the identical word sequence for the same remap, so a
 * factor gets both behaviours from one `buildKey` description via [materializeKey] and [hashRemappedKey].
 *
 * Methods split arguments into constants (carried verbatim) and variable references (remapped by
 * [HashingKeySink]): [intVar]/[intVars]/[sortedIntVars] are integer-variable ids, [boolVar] a Boolean
 * variable id, [boolLit]/[boolLits]/[sortedBoolLits] Boolean literals (variable remapped, polarity kept),
 * and [pairsByVarKey]/[pairsByLitKey] `(varKey, constValue)` pairs ordered by the *remapped* key. The
 * `const*` methods and the scalar [int]/[long]/[bool]/[enum] carry values no remap touches.
 */
internal interface KeySink {
    fun int(value: Int)
    fun long(value: Long)
    fun bool(value: Boolean)
    fun enum(value: Enum<*>)

    /** A positional constant int array (order significant), not remapped. */
    fun constInts(xs: IntArray)

    /** A positional constant long array (order significant), not remapped. */
    fun constLongs(xs: LongArray)

    /** A pre-built constant fragment (no variable ids inside) spliced verbatim. */
    fun constWords(fragment: LongArray)

    /** One integer-variable id. */
    fun intVar(id: Int)

    /** One integer-variable id, or a negative sentinel carried verbatim (e.g. an absent count var). */
    fun intVarOrSelf(id: Int)

    /** Positional integer-variable ids (order significant). */
    fun intVars(ids: IntArray)

    /** Set-semantics integer-variable ids (order insignificant): remapped, then sorted ascending. */
    fun sortedIntVars(ids: IntArray)

    /** One real-variable id. */
    fun realVar(id: Int)

    /** One Boolean-variable id. */
    fun boolVar(id: Int)

    /** Set-semantics Boolean-variable ids: remapped, then sorted ascending. */
    fun sortedBoolVars(ids: IntArray)

    /** One Boolean literal (variable remapped, polarity kept). */
    fun boolLit(lit: Int)

    /** Positional Boolean literals (order significant). */
    fun boolLits(lits: IntArray)

    /** Set-semantics Boolean literals: remapped, then sorted ascending. */
    fun sortedBoolLits(lits: IntArray)

    /** `(varKey, value)` pairs ordered by the remapped key ascending; the value for original index `i` is
     *  `valueOf(i)`, a constant. Non-coalescing: keys sharing an image stay as separate pairs. */
    fun pairsByVarKey(varKeys: IntArray, valueOf: (Int) -> Long)

    /** `(litKey, value)` pairs ordered by the remapped literal ascending; value at original index `i` is
     *  `valueOf(i)`, a constant. Non-coalescing. */
    fun pairsByLitKey(litKeys: IntArray, valueOf: (Int) -> Long)

    /** `(varKey, value)` pairs ordered by the remapped variable ascending, **coalescing** keys that share
     *  an image by summing their `valueOf(i)` values — mirroring a factor (e.g. Linear) whose `remap`
     *  merges same-variable terms via its constructor, so the port hash matches `remap().structuralKey()`
     *  even when the map collapses two variables. Under an injective map it is equivalent to
     *  [pairsByVarKey]. */
    fun pairsByVarKeyCoalescing(varKeys: IntArray, valueOf: (Int) -> Long)
}

/**
 * The canonical [StructuralKey] of [kind] whose payload is emitted by [build] — the materialising half
 * of a factor's single key declaration. A factor's `structuralKey` is `materializeKey(KIND, ::buildKey)`.
 */
internal fun materializeKey(kind: FactorKind, build: (KeySink) -> Unit): StructuralKey =
    MaterializingKeySink(kind).also(build).toKey()

/** [materializeKey] with a factor-provided payload-size estimate. */
internal fun materializeKey(kind: FactorKind, expectedWords: Int, build: (KeySink) -> Unit): StructuralKey =
    MaterializingKeySink(kind, expectedWords).also(build).toKey()

/**
 * The `hashCode` of the key `build` describes *after* renumbering variables through [mapping] — the
 * hashing half, allocation-free (no `remap()` copy, no key object). Equals
 * `remap(mapping).structuralKey().hashCode()`. A factor's `remapStructuralHash` is
 * `hashRemappedKey(KIND, mapping, ::buildKey)`, sharing the same `buildKey` as [materializeKey].
 */
internal fun hashRemappedKey(kind: FactorKind, mapping: VarRemap, build: (KeySink) -> Unit): Int =
    HashingKeySink(kind, mapping).also(build).hash()

/** Builds the canonical [StructuralKey] with the identity remap. */
internal class MaterializingKeySink(private val kind: FactorKind, expectedWords: Int = 0) : KeySink {
    private val b = StructuralKeyBuilder(expectedWords)

    override fun int(value: Int) = b.int(value)
    override fun long(value: Long) = b.long(value)
    override fun bool(value: Boolean) = b.bool(value)
    override fun enum(value: Enum<*>) = b.enum(value)
    override fun constInts(xs: IntArray) = b.ints(xs)
    override fun constLongs(xs: LongArray) = b.longs(xs)
    override fun constWords(fragment: LongArray) = b.words(fragment)
    override fun intVar(id: Int) = b.int(id)
    override fun intVarOrSelf(id: Int) = b.int(id)
    override fun intVars(ids: IntArray) = b.ints(ids)
    override fun sortedIntVars(ids: IntArray) = b.sortedInts(ids)
    override fun realVar(id: Int) = b.int(id)
    override fun boolVar(id: Int) = b.int(id)
    override fun sortedBoolVars(ids: IntArray) = b.sortedInts(ids)
    override fun boolLit(lit: Int) = b.int(lit)
    override fun boolLits(lits: IntArray) = b.ints(lits)
    override fun sortedBoolLits(lits: IntArray) = b.sortedInts(lits)
    override fun pairsByVarKey(varKeys: IntArray, valueOf: (Int) -> Long) = b.pairsByKey(varKeys, valueOf)
    override fun pairsByLitKey(litKeys: IntArray, valueOf: (Int) -> Long) = b.pairsByKey(litKeys, valueOf)

    override fun pairsByVarKeyCoalescing(varKeys: IntArray, valueOf: (Int) -> Long) = b.pairsByKey(varKeys, valueOf)

    fun toKey(): StructuralKey = b.build(kind)
}

/**
 * Folds the hash of the key the factor would have after renumbering variables through [mapping],
 * mirroring [StructuralKeyBuilder]'s word layout without allocating. The fold matches
 * `remap(mapping).structuralKey().hashCode()`: `contentHashCode` over the payload words
 * (seed 1, `h = 31*h + longWordHash(w)`), then `31*kind.ordinal + h`.
 */
internal class HashingKeySink(private val kind: FactorKind, private val mapping: VarRemap) : KeySink {
    private var h = 1

    private fun word(w: Long) {
        h = 31 * h + (w xor (w ushr Int.SIZE_BITS)).toInt()
    }

    private fun mapLit(lit: Int): Int = Lit.make(mapping.bool(Lit.variable(lit)), Lit.isPositive(lit))

    override fun int(value: Int) = word(value.toLong())
    override fun long(value: Long) = word(value)
    override fun bool(value: Boolean) = word(if (value) 1L else 0L)
    override fun enum(value: Enum<*>) = word(value.ordinal.toLong())

    override fun constInts(xs: IntArray) {
        word(xs.size.toLong())
        for (x in xs) word(x.toLong())
    }

    override fun constLongs(xs: LongArray) {
        word(xs.size.toLong())
        for (x in xs) word(x)
    }

    override fun constWords(fragment: LongArray) {
        for (w in fragment) word(w)
    }

    override fun intVar(id: Int) = word(mapping.int(id).toLong())

    override fun intVarOrSelf(id: Int) = word((if (id >= 0) mapping.int(id) else id).toLong())

    override fun intVars(ids: IntArray) {
        word(ids.size.toLong())
        for (x in ids) word(mapping.int(x).toLong())
    }

    override fun sortedIntVars(ids: IntArray) = sortedImages(ids) { mapping.int(it) }

    override fun realVar(id: Int) = word(mapping.real(id).toLong())

    override fun boolVar(id: Int) = word(mapping.bool(id).toLong())

    override fun sortedBoolVars(ids: IntArray) = sortedImages(ids) { mapping.bool(it) }

    override fun boolLit(lit: Int) = word(mapLit(lit).toLong())

    override fun boolLits(lits: IntArray) {
        word(lits.size.toLong())
        for (l in lits) word(mapLit(l).toLong())
    }

    override fun sortedBoolLits(lits: IntArray) = sortedImages(lits) { mapLit(it) }

    override fun pairsByVarKey(varKeys: IntArray, valueOf: (Int) -> Long) = pairs(varKeys, { mapping.int(it) }, valueOf)

    override fun pairsByLitKey(litKeys: IntArray, valueOf: (Int) -> Long) = pairs(litKeys, { mapLit(it) }, valueOf)

    /** Emit `length` then the images of [ids] under [image], ascending — mirrors `sortedInts` on the
     *  remapped array. */
    private inline fun sortedImages(ids: IntArray, image: (Int) -> Int) {
        word(ids.size.toLong())
        val images = IntArray(ids.size) { image(ids[it]) }
        images.sort()
        for (x in images) word(x.toLong())
    }

    /** Emit `length` then `(image, value)` pairs ordered by image ascending (ties by original index),
     *  mirroring `pairsByKey` on the remapped keys — non-coalescing. */
    private inline fun pairs(keys: IntArray, image: (Int) -> Int, valueOf: (Int) -> Long) {
        word(keys.size.toLong())
        val n = keys.size
        val order = IntArray(n) { it }
        val images = IntArray(n) { image(keys[it]) }
        val packed = LongArray(n) { (images[it].toLong() shl Int.SIZE_BITS) or (it.toLong() and LOW_WORD) }
        packed.sort()
        for (i in 0 until n) order[i] = (packed[i] and LOW_WORD).toInt()
        for (i in order) {
            word(images[i].toLong())
            word(valueOf(i))
        }
    }

    override fun pairsByVarKeyCoalescing(varKeys: IntArray, valueOf: (Int) -> Long) {
        val n = varKeys.size
        val packed = LongArray(
            n,
        ) { (mapping.int(varKeys[it]).toLong() shl Int.SIZE_BITS) or (it.toLong() and LOW_WORD) }
        packed.sort()
        var distinct = 0
        var i = 0
        while (i < n) {
            val img = packed[i] ushr Int.SIZE_BITS
            var j = i + 1
            while (j < n && packed[j] ushr Int.SIZE_BITS == img) j++
            distinct++
            i = j
        }
        word(distinct.toLong())
        i = 0
        while (i < n) {
            val img = packed[i] ushr Int.SIZE_BITS
            var sum = 0L
            var j = i
            while (j < n && packed[j] ushr Int.SIZE_BITS == img) {
                sum += valueOf((packed[j] and LOW_WORD).toInt())
                j++
            }
            word(img)
            word(sum)
            i = j
        }
    }

    fun hash(): Int = 31 * kind.ordinal + h

    private companion object {
        const val LOW_WORD = 0xFFFFFFFFL
    }
}
