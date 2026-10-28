package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.floorOrNull
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

// The planned moves only choose which inputs to free: the consuming repair enforces every row, pin and
// definition, so a plan that overshoots or ignores the objective cutoff cannot admit an unsound candidate.
internal class SourceRowCompletion(
    private val model: BakedProblem,
    private val source: SourceProofContext,
    private val funding: SourceLpFunding,
    private val frozen: (Int) -> Boolean,
    private val stopped: () -> Boolean,
) {
    enum class Outcome { Complete, Immovable, Unrepairable, Limited }

    class Member(val coordinate: SourceCoordinate, val value: BigFraction, val row: String?)

    class Packet(val seed: SourceCoordinate, val outcome: Outcome, val members: List<Member>, val blockedRow: String?)

    private class Row(
        val id: String,
        val inputs: IntArray,
        val coefficients: Array<BigFraction>,
        val rhs: BigFraction,
        val strict: Boolean,
        val conditions: IntArray,
        val conditionValues: Array<BigFraction>,
    )

    private val count = model.numBoolVars + model.numIntVars
    private val maxMembers = 4096
    private val rootBools = model.baked as? PropagationResult.Implied
    private var rows = emptyArray<Row>()
    private var incidentRows = emptyArray<IntArray>()
    private var incidentPositions = emptyArray<IntArray>()
    private var centerActivity = emptyArray<BigFraction?>()

    fun complete(center: Sample, seeds: List<Pair<Int, Int>>, supported: (Int) -> Boolean): List<Packet>? {
        if (!index()) return null
        centerActivity = arrayOfNulls(rows.size)
        val packets = ArrayList<Packet>(seeds.size)
        for ((seed, direction) in seeds) {
            packets += chain(center, seed, direction, supported) ?: return null
        }
        return packets
    }

    private fun index(): Boolean {
        val premises = source.premises
        val built = ArrayList<Row>(premises.size)
        val rowLists = Array(count) { ArrayList<Int>(2) }
        val positionLists = Array(count) { ArrayList<Int>(2) }
        for ((id, premise) in premises) {
            val coefficients = premise.inequality.coefficients
            val pins = premise.integerPins
            val guards = premise.guards
            val entries = coefficients.size + pins.size + guards.size
            funding.charge(1L + entries, 64L + entries * 48L)
            if (stopped()) return false
            val inputs = IntArray(coefficients.size)
            val values = arrayOfNulls<BigFraction>(coefficients.size)
            for ((position, entry) in coefficients.entries.withIndex()) {
                inputs[position] = checkNotNull(index(entry.key))
                values[position] = entry.value
            }
            val conditions = IntArray(guards.size + pins.size)
            val required = arrayOfNulls<BigFraction>(conditions.size)
            for ((position, entry) in guards.entries.withIndex()) {
                conditions[position] = checkNotNull(index(SourceCoordinate(SourceCoordinate.Kind.Boolean, entry.key)))
                required[position] = if (entry.value) BigFraction.ONE else BigFraction.ZERO
            }
            for ((position, entry) in pins.entries.withIndex()) {
                conditions[guards.size + position] = checkNotNull(index(entry.key))
                required[guards.size + position] = entry.value
            }
            val row = built.size
            built += Row(id, inputs, values.requireNoNulls(), premise.inequality.rhs, premise.inequality.strict,
                conditions, required.requireNoNulls())
            for ((position, input) in inputs.withIndex()) {
                rowLists[input] += row
                positionLists[input] += position
            }
            for (condition in conditions) {
                if (rowLists[condition].lastOrNull() != row) {
                    rowLists[condition] += row
                    positionLists[condition] += -1
                }
            }
        }
        rows = built.toTypedArray()
        incidentRows = Array(count) { rowLists[it].toIntArray() }
        incidentPositions = Array(count) { positionLists[it].toIntArray() }
        return true
    }

    private fun chain(center: Sample, seed: Int, direction: Int, supported: (Int) -> Boolean): Packet? {
        val seedCoordinate = coordinate(seed)
        val start = (if (frozen(seed)) null else shift(center, seed, direction, 1L))
            ?: return Packet(seedCoordinate, Outcome.Immovable, emptyList(), null)
        val planned = LinkedHashMap<Int, BigFraction>()
        val members = ArrayList<Member>()
        val delta = HashMap<Int, BigFraction>()
        val queue = ArrayDeque<Int>()
        val queued = HashSet<Int>()
        fun current(index: Int): BigFraction = planned[index] ?: centerValue(center, index)
        fun move(index: Int, target: BigFraction, row: String?) {
            funding.charge(1L + incidentRows[index].size, 96L)
            val change = target - centerValue(center, index)
            planned[index] = target
            members += Member(coordinate(index), target, row)
            for ((offset, incident) in incidentRows[index].withIndex()) {
                val position = incidentPositions[index][offset]
                if (position >= 0) {
                    val coefficient = rows[incident].coefficients[position]
                    delta[incident] = (delta[incident] ?: BigFraction.ZERO) + coefficient * change
                }
                if (queued.add(incident)) queue.addLast(incident)
            }
        }
        fun excess(row: Int): BigFraction? {
            val shape = rows[row]
            funding.charge(1L + shape.conditions.size)
            for ((position, condition) in shape.conditions.withIndex()) {
                if (current(condition) != shape.conditionValues[position]) return null
            }
            val total = activity(center, row) + (delta[row] ?: BigFraction.ZERO)
            val over = total - shape.rhs
            return over.takeIf { it.signum() > 0 || (shape.strict && it.isZero) }
        }
        move(seed, start, null)
        while (queue.isNotEmpty()) {
            if (stopped()) return null
            val row = queue.removeFirst()
            queued -= row
            if (excess(row) == null) continue
            val shape = rows[row]
            val candidates = (shape.inputs.toList() + shape.conditions.toList()).distinct()
                .filter { it !in planned && !frozen(it) }
                .sortedWith(compareBy({ if (supported(it)) 0 else 1 }, { it }))
            funding.charge(1L + candidates.size, 16L * candidates.size)
            for (candidate in candidates) {
                if (stopped()) return null
                val over = excess(row) ?: break
                val target = compensation(center, shape, candidate, over) ?: continue
                move(candidate, target, shape.id)
                if (members.size > maxMembers) return Packet(seedCoordinate, Outcome.Limited, members, shape.id)
            }
            if (stopped()) return null
            if (excess(row) != null) return Packet(seedCoordinate, Outcome.Unrepairable, members, shape.id)
        }
        return Packet(seedCoordinate, Outcome.Complete, members, null)
    }

    private fun compensation(center: Sample, row: Row, input: Int, over: BigFraction): BigFraction? {
        val position = row.inputs.indexOf(input)
        if (position < 0) return shift(center, input, 1, 1L) ?: shift(center, input, -1, 1L)
        val coefficient = row.coefficients[position]
        val magnitude = if (coefficient.signum() < 0) coefficient.negated() else coefficient
        val quotient = over * magnitude.reciprocal()
        val ceiling = quotient.ceilInteger()
        val integral = quotient.den == BIG_ONE
        val steps = if (!ceiling.fitsLong()) Long.MAX_VALUE else ceiling.toLongExact().let {
            if (row.strict && integral && it < Long.MAX_VALUE) it + 1 else it
        }
        return shift(center, input, -coefficient.signum(), steps.coerceAtLeast(1L))
    }

    private fun shift(center: Sample, index: Int, direction: Int, steps: Long): BigFraction? {
        if (index < model.numBoolVars) {
            if (rootBools?.boolValueOrNull(index) != null) return null
            val current = center.boolValue(index)
            return when {
                direction > 0 && !current -> BigFraction.ONE
                direction < 0 && current -> BigFraction.ZERO
                else -> null
            }
        }
        val domain = model.rootIntDomain(index - model.numBoolVars)
        val current = center.intValue(index - model.numBoolVars)
        // A negative room is a wrapped difference across a domain wider than Long; it bounds nothing.
        val target = if (direction > 0) {
            if (current >= domain.max) return null
            val room = domain.max - current
            if (room < 0 || steps >= room) domain.max else domain.ceilingOrNull(current + steps) ?: domain.max
        } else {
            if (current <= domain.min) return null
            val room = current - domain.min
            if (room < 0 || steps >= room) domain.min else domain.floorOrNull(current - steps) ?: domain.min
        }
        return BigFraction.ofLong(target)
    }

    private fun activity(center: Sample, row: Int): BigFraction {
        centerActivity[row]?.let { return it }
        val shape = rows[row]
        funding.charge(1L + shape.inputs.size, 48L)
        var total = BigFraction.ZERO
        for ((position, input) in shape.inputs.withIndex()) {
            total += shape.coefficients[position] * centerValue(center, input)
        }
        centerActivity[row] = total
        return total
    }

    private fun centerValue(sample: Sample, index: Int): BigFraction = if (index < model.numBoolVars) {
        if (sample.boolValue(index)) BigFraction.ONE else BigFraction.ZERO
    } else BigFraction.ofLong(sample.intValue(index - model.numBoolVars))

    private fun coordinate(index: Int) = if (index < model.numBoolVars) {
        SourceCoordinate(SourceCoordinate.Kind.Boolean, index)
    } else SourceCoordinate(SourceCoordinate.Kind.Integer, index - model.numBoolVars)

    private fun index(coordinate: SourceCoordinate): Int? = when (coordinate.kind) {
        SourceCoordinate.Kind.Boolean -> coordinate.id.takeIf { it in 0 until model.numBoolVars }
        SourceCoordinate.Kind.Integer -> coordinate.id.takeIf { it in 0 until model.numIntVars }
            ?.let { model.numBoolVars + it }
        SourceCoordinate.Kind.Real -> null
    }
}
