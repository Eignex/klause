package com.eignex.klause.ir

// One declaration of a factor's structural key, consumed twice: MaterializingKeySink builds the key and
// HashingKeySink folds the hash the key would have after a variable remap, allocating neither. Both emit
// the identical word sequence for the same remap. Variable and literal methods are remapped by the
// hashing sink; `const*` and scalar methods carry values verbatim.
internal interface KeySink {
    fun int(value: Int)
    fun long(value: Long)
    fun bool(value: Boolean)
    fun enum(value: Enum<*>)

    fun constInts(xs: IntArray)

    fun constLongs(xs: LongArray)

    // A pre-built fragment with no variable ids inside, spliced verbatim.
    fun constWords(fragment: LongArray)

    fun intVar(id: Int)

    // A negative sentinel, such as an absent count variable, is carried verbatim.
    fun intVarOrSelf(id: Int)

    fun intVars(ids: IntArray)

    fun sortedIntVars(ids: IntArray)

    fun realVar(id: Int)

    fun boolVar(id: Int)

    fun sortedBoolVars(ids: IntArray)

    fun boolLit(lit: Int)

    fun boolLits(lits: IntArray)

    fun sortedBoolLits(lits: IntArray)

    // Ordered by remapped key; keys sharing an image stay separate pairs.
    fun pairsByVarKey(varKeys: IntArray, valueOf: (Int) -> Long)

    fun pairsByLitKey(litKeys: IntArray, valueOf: (Int) -> Long)

    // Sums the values of keys sharing an image, mirroring a factor whose `remap` merges same-variable
    // terms, so the hash matches `remap().structuralKey()` even when the map collapses two variables.
    fun pairsByVarKeyCoalescing(varKeys: IntArray, valueOf: (Int) -> Long)
}

internal fun materializeKey(kind: FactorKind, build: (KeySink) -> Unit): StructuralKey =
    MaterializingKeySink(kind).also(build).toKey()

internal fun materializeKey(kind: FactorKind, expectedWords: Int, build: (KeySink) -> Unit): StructuralKey =
    MaterializingKeySink(kind, expectedWords).also(build).toKey()

// Equals `remap(mapping).structuralKey().hashCode()` without allocating the remapped factor or key.
internal fun hashRemappedKey(kind: FactorKind, mapping: VarRemap, build: (KeySink) -> Unit): Int =
    HashingKeySink(kind, mapping).also(build).hash()

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

// Mirrors StructuralKeyBuilder's word layout: `contentHashCode` over the payload words, then
// `31*kind.ordinal + h`, so the fold matches `remap(mapping).structuralKey().hashCode()`.
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

    // Mirrors `sortedInts` on the remapped array.
    private inline fun sortedImages(ids: IntArray, image: (Int) -> Int) {
        word(ids.size.toLong())
        val images = IntArray(ids.size) { image(ids[it]) }
        images.sort()
        for (x in images) word(x.toLong())
    }

    // Mirrors `pairsByKey` on the remapped keys; ties break by original index.
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
