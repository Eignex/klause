package com.eignex.klause.lp

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// A constant array gives the exact convex hull: one-hot selectors `y(p)` over `idx`'s root box (pinned to 0
// off the live domain), `Σ y(p) = 1`, the index channel and the result channel. A variable array relaxes the
// bilinear result channel to two big-M rows per position. The one-hot row confines `idx` to the enumerated
// box, so an open-sided `idx` is declined: the hull would drop positions the model admits.
internal fun Element.emitLpRelaxation(builder: RelaxationBuilder) {
    if (!builder.hullEnabled()) return
    if (!builder.statesBothBounds(idx)) return
    val choices = retainedChoices(builder.rootDomain(idx)) ?: return
    val selCols = IntArrayList()
    val positions = IntArrayList()
    emitSelectorsAndIndexChannel(builder, choices, selCols, positions)
    val k = selCols.size
    if (k == 0) return
    if (arrIsVars) emitResultBigM(builder, selCols, positions) else emitResultChannel(builder, selCols, positions)
}

internal fun Element.estimateLpHull(boxes: RootBoxes): LpSizeEstimate? {
    var result: LpSizeEstimate? = null
    sequence<Unit> { result = prepareLpHull(this@estimateLpHull, boxes) }.forEach { }
    return result
}

internal suspend fun SequenceScope<Unit>.prepareLpHull(
    factor: Element,
    boxes: RootBoxes,
    shared: ElementLpChoices = ElementLpChoices(),
): LpSizeEstimate? {
    val arr = factor.arr
    val idx = factor.idx
    val result = factor.result
    val arrIsVars = factor.arrIsVars
    val indexOffset = factor.indexOffset

    if (!boxes.statesBothBounds(idx)) return null
    val choices = factor.retainedChoices(boxes.domain(idx)) ?: return null
    // Size big-M rows only where the build emits them: both boxes stated by the model.
    val linkable = arrIsVars && boxes.statesBothBounds(result)
    var linked = 0L
    var added = 0L
    for ((position, value) in choices.withIndex()) {
        if ((position + 1) % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
        val p = (value - indexOffset.toLong()).toInt()
        if (shared.columns.add(idx to value)) added++
        if (linkable && boxes.statesBothBounds(arr[p].toInt())) linked++
    }
    // Retained source regions can each carry the same channel rows while sharing their columns.
    return LpSizeEstimate(cols = added, rows = 2L + if (arrIsVars) 2L * linked else 1L)
}

internal class ElementLpChoices {
    val columns = HashSet<Pair<Int, Long>>()
}

internal interface SharedIndexChoices {
    fun indexChoiceColumn(index: Int, value: Long): Int
    fun indexChoiceBlock(index: Int, values: LongArray): Boolean
}

private fun Element.retainedChoices(domain: IntDomain): LongArray? {
    if (arr.isEmpty()) return null
    val lo = indexOffset.toLong()
    val hi = lo + arr.size - 1L
    if (domain.max < lo || domain.min > hi) return null
    val retained = LongArrayList()
    var value = maxOf(lo, domain.min)
    if (value !in domain) value = domain.higher(value)
    while (value <= hi) {
        if (retained.size == MAX_CHOICES) return null
        retained.add(value)
        if (value == domain.max) break
        value = domain.higher(value)
    }
    return retained.toLongArray().takeIf { it.isNotEmpty() }
}

// `Σ y(p) = 1` and the index channel `Σ (p + off)·y(p) = idx`.
private fun Element.emitSelectorsAndIndexChannel(
    builder: RelaxationBuilder,
    choices: LongArray,
    selCols: IntArrayList,
    positions: IntArrayList,
) {
    val off = indexOffset
    val live = builder.liveDomain(idx)
    val shared = builder as? SharedIndexChoices
    for (idxVal in choices) {
        val p = (idxVal - off.toLong()).toInt()
        selCols.add(
            shared?.indexChoiceColumn(idx, idxVal) ?: builder.auxColumn(
                0L,
                if (idxVal in live) 1L else 0L,
                presence = longArrayOf(idx.toLong(), idxVal),
                definition = LpAuxiliaryColumn(listOf(p.toLong()), 1L, true),
            ),
        )
        positions.add(p)
    }
    val k = selCols.size
    if (k == 0) return
    if (shared != null && !shared.indexChoiceBlock(idx, choices)) return
    builder.row(selCols.toIntArray(), LongArray(k) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
    val idxCols = IntArray(k + 1)
    val idxVals = LongArray(k + 1)
    for (t in 0 until k) {
        idxCols[t] = selCols[t]
        idxVals[t] = (positions[t].toLong() + off.toLong())
    }
    idxCols[k] = builder.intColumn(idx)
    idxVals[k] = -1L
    builder.row(idxCols, idxVals, LinearOp.EQ, 0L, Contribution.HULL)
}

private fun Element.emitResultChannel(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
    val k = selCols.size
    val resCols = IntArray(k + 1)
    val resVals = LongArray(k + 1)
    for (t in 0 until k) {
        resCols[t] = selCols[t]
        resVals[t] = arr[positions[t]]
    }
    resCols[k] = builder.intColumn(result)
    resVals[k] = -1L
    builder.row(resCols, resVals, LinearOp.EQ, 0L, Contribution.HULL)
}

private fun Element.emitResultBigM(builder: RelaxationBuilder, selCols: IntArrayList, positions: IntArrayList) {
    // `M` bounds the gap only while both boxes are the model's own; a position over an invented
    // endpoint stays unlinked, which is sound because its selector stays free.
    if (!builder.statesBothBounds(result)) return
    val resCol = builder.intColumn(result)
    val rDom = builder.rootDomain(result)
    for (t in 0 until selCols.size) {
        val arrVar = arr[positions[t]].toInt()
        if (!builder.statesBothBounds(arrVar)) continue
        val aDom = builder.rootDomain(arrVar)
        val m = maxOf(rDom.max, aDom.max) - minOf(rDom.min, aDom.min)
        if (m < 0L) continue
        val arrCol = builder.intColumn(arrVar)
        val y = selCols[t]
        builder.row(intArrayOf(resCol, arrCol, y), longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
        builder.row(intArrayOf(arrCol, resCol, y), longArrayOf(1L, -1L, m), LinearOp.LE, m, Contribution.HULL)
    }
}

private const val MAX_CHOICES: Int = 1024
