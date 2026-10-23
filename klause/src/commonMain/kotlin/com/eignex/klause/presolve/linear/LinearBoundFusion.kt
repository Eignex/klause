package com.eignex.klause.presolve.linear

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.ir.linearRows
import com.eignex.klause.presolve.PassDelta
import com.eignex.klause.presolve.PresolveShared
import com.eignex.klause.presolve.integralLinear
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.ScanMeter
import com.eignex.klause.util.abs
import com.eignex.klause.util.addExact
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.maxOf
import com.eignex.klause.util.minOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.rem
import com.eignex.klause.util.subExact
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

internal object LinearBoundFusion {
    private const val FUSION_WORK_WEIGHT = 16L

    private class Interrupted : RuntimeException()

    private fun visit(scan: ScanMeter) {
        if (!scan.visit(FUSION_WORK_WEIGHT)) throw Interrupted()
    }

    private class Group(val terms: Terms) {
        var upper: BigInt? = null
        var lower: BigInt? = null
        var originalUpper: BigInt? = null
        var originalLower: BigInt? = null
        var hasEquality = false
        val droppable = ArrayList<Int>()
        var guards: GuardBounds? = null
    }

    private class Seed(val literal: Int, val bound: BigInt)

    private class GuardBounds {
        val upper = ArrayList<Seed>()
        val lower = ArrayList<Seed>()
    }

    fun fuseLinearBounds(problem: Problem, cancellation: Cancellation = Cancellation.Never): PassDelta {
        val scan = ScanMeter(cancellation)
        return try {
            fuse(problem, scan)
        } catch (_: Interrupted) {
            PassDelta()
        } finally {
            scan.flush()
        }
    }

    private fun fuse(problem: Problem, scan: ScanMeter): PassDelta {
        if (!scan.check()) return PassDelta()
        val groups = HashMap<Terms, Group>()
        for ((index, factor) in problem.factors.withIndex()) {
            visit(scan)
            if (factor is Clause) continue
            for (row in factor.impliedLinearRows) {
                visit(scan)
                val canonical = canonicalize(row, scan) ?: continue
                for (variable in canonical.terms.vars) visit(scan)
                val group = groups.getOrPut(canonical.terms) {
                    Group(canonical.terms)
                }
                if (row.relation == LinearOp.EQ || canonical.upper) {
                    group.upper = group.upper?.let { minOf(it, canonical.bound) } ?: canonical.bound
                }
                if (row.relation == LinearOp.EQ || !canonical.upper) {
                    group.lower = group.lower?.let { maxOf(it, canonical.bound) } ?: canonical.bound
                }
                if (row.relation == LinearOp.EQ) {
                    group.hasEquality = true
                } else if (factor.linearForm is LinearForm.Conjunction && factor.linearRows.size == 1) {
                    group.droppable.add(index)
                }
            }
            val conjunction = factor.linearForm as? LinearForm.Conjunction ?: continue
            for (row in conjunction.rows) {
                visit(scan)
                if (row.activator !in 0 until problem.numBoolVars) continue
                if (row.strict && (row.relation == LinearOp.EQ || row.relation == LinearOp.NE)) continue
                val body = if (row.relation == LinearOp.NE) object : LinearRow by row {
                    override val relation = LinearOp.EQ
                } else row
                val canonical = canonicalize(body, scan, allowActivated = true) ?: continue
                val group = groups.getOrPut(canonical.terms) { Group(canonical.terms) }
                val guards = group.guards ?: GuardBounds().also { group.guards = it }
                val positive = Lit.make(row.activator, true)
                when (row.relation) {
                    LinearOp.EQ, LinearOp.NE -> {
                        val literal = if (row.relation == LinearOp.EQ) positive else positive xor 1
                        guards.upper.add(Seed(literal, canonical.bound))
                        guards.lower.add(Seed(literal, canonical.bound))
                    }
                    LinearOp.LE, LinearOp.GE -> {
                        if (canonical.upper) {
                            guards.upper.add(Seed(positive, canonical.bound))
                            guards.lower.add(Seed(positive xor 1, canonical.bound + BIG_ONE))
                        } else {
                            guards.lower.add(Seed(positive, canonical.bound))
                            guards.upper.add(Seed(positive xor 1, canonical.bound - BIG_ONE))
                        }
                    }
                }
            }
        }
        for (group in groups.values) {
            visit(scan)
            group.originalUpper = group.upper
            group.originalLower = group.lower
        }
        if (groups.values.any { it.guards != null } && problem.numBoolVars <= Int.MAX_VALUE / 2) {
            GuardEnvelope(problem, scan).derive(groups.values)
        }
        val dropped = ArrayList<Int>()
        val added = ArrayList<Factor>()
        for (group in groups.values) {
            visit(scan)
            val lower = group.lower
            val upper = group.upper
            if (lower != null && upper != null && lower > upper) return PassDelta(infeasible = true)
            if (lower != null && lower == upper && !group.hasEquality) {
                for (index in group.droppable) visit(scan)
                for (variable in group.terms.vars) visit(scan)
                dropped.addAll(group.droppable)
                added.add(integralLinear(group.terms.vars, group.terms.exactCoefficients(), LinearOp.EQ, lower) {
                    visit(scan)
                })
                continue
            }
            if (upper != null && (group.originalUpper == null || upper < checkNotNull(group.originalUpper))) {
                for (variable in group.terms.vars) visit(scan)
                added.add(integralLinear(group.terms.vars, group.terms.exactCoefficients(), LinearOp.LE, upper) {
                    visit(scan)
                })
            }
            if (lower != null && (group.originalLower == null || lower > checkNotNull(group.originalLower))) {
                for (variable in group.terms.vars) visit(scan)
                added.add(integralLinear(group.terms.vars, group.terms.exactCoefficients(), LinearOp.GE, lower) {
                    visit(scan)
                })
            }
        }
        if (!scan.check()) throw Interrupted()
        return PassDelta(droppedIndices = dropped.toIntArray(), addedFactors = added)
    }

    private class GuardEnvelope(problem: Problem, private val scan: ScanMeter) {
        private val size = problem.numBoolVars * 2
        private val reverse = arrayOfNulls<IntArrayList>(size)
        private val occurrences = arrayOfNulls<IntArrayList>(size)
        private val clauses = ArrayList<Clause>()
        private val knownAt = IntArray(size)
        private val facts = arrayOfNulls<BigInt>(size)
        private val reached = IntArrayList()
        private var generation = 0

        init {
            for (literal in 0 until size) visit(scan)
            for (factor in problem.factors) {
                visit(scan)
                if (factor !is Clause) continue
                val index = clauses.size
                clauses.add(factor)
                for (literal in factor.literals) {
                    visit(scan)
                    val list = occurrences[literal] ?: IntArrayList().also { occurrences[literal] = it }
                    list.add(index)
                }
                if (factor.literals.size == 2) {
                    val first = factor.literals[0]
                    val second = factor.literals[1]
                    antecedent(first, second xor 1)
                    antecedent(second, first xor 1)
                }
            }
        }

        private fun antecedent(consequent: Int, premise: Int) {
            visit(scan)
            val list = reverse[consequent] ?: IntArrayList().also { reverse[consequent] = it }
            list.add(premise)
        }

        fun derive(groups: Collection<Group>) {
            for (group in groups) {
                visit(scan)
                val guards = group.guards ?: continue
                derive(group, guards.upper, upper = true)
                derive(group, guards.lower, upper = false)
            }
        }

        private fun derive(group: Group, seeds: ArrayList<Seed>, upper: Boolean) {
            if (seeds.isEmpty()) return
            seeds.sortWith { left, right ->
                visit(scan)
                val order = if (upper) left.bound.compareTo(right.bound) else right.bound.compareTo(left.bound)
                if (order == 0) left.literal.compareTo(right.literal) else order
            }
            if (generation == Int.MAX_VALUE) {
                for (literal in knownAt.indices) {
                    visit(scan)
                    knownAt[literal] = 0
                }
                generation = 0
            }
            generation++
            reached.clear()
            // Strongest seeds run first: the first reaching path proves the strongest transferred bound.
            for (seed in seeds) {
                visit(scan)
                if (knownAt[seed.literal] == generation) continue
                var next = reached.size
                remember(seed.literal, seed.bound)
                while (next < reached.size) {
                    visit(scan)
                    val literal = reached[next++]
                    val predecessors = reverse[literal] ?: continue
                    for (i in 0 until predecessors.size) {
                        visit(scan)
                        val predecessor = predecessors[i]
                        if (knownAt[predecessor] != generation) remember(predecessor, seed.bound)
                    }
                }
            }
            val covers = HashMap<Int, Cover>()
            for (i in 0 until reached.size) {
                visit(scan)
                val literal = reached[i]
                val bound = checkNotNull(facts[literal])
                val opposite = literal xor 1
                if (Lit.isPositive(literal) && knownAt[opposite] == generation) {
                    accept(group, weakest(bound, checkNotNull(facts[opposite]), upper), upper)
                }
                val readers = occurrences[literal] ?: continue
                for (j in 0 until readers.size) {
                    visit(scan)
                    val clause = readers[j]
                    val cover = covers.getOrPut(clause) { Cover(bound) }
                    cover.bound = weakest(cover.bound, bound, upper)
                    cover.seen++
                    if (cover.seen == clauses[clause].literals.size) accept(group, cover.bound, upper)
                }
            }
        }

        private fun remember(literal: Int, bound: BigInt) {
            knownAt[literal] = generation
            facts[literal] = bound
            reached.add(literal)
        }

        private fun weakest(left: BigInt, right: BigInt, upper: Boolean): BigInt =
            if (upper) maxOf(left, right) else minOf(left, right)

        private fun accept(group: Group, bound: BigInt, upper: Boolean) {
            visit(scan)
            // A cover has a true literal, whose bound implies this weakest common consequence.
            if (upper) group.upper = group.upper?.let { minOf(it, bound) } ?: bound
            else group.lower = group.lower?.let { maxOf(it, bound) } ?: bound
        }

        private class Cover(var bound: BigInt) {
            var seen = 0
        }
    }

    private class Terms(
        val vars: IntArray,
        private val coefficients: LongArray?,
        private val wideCoefficients: Array<BigInt>? = null,
    ) {
        private val hash =
            31 * vars.contentHashCode() + (coefficients?.contentHashCode() ?: wideCoefficients.contentHashCode())

        fun exactCoefficients(): Array<BigInt> = wideCoefficients ?: Array(vars.size) {
            bigIntOf(checkNotNull(coefficients)[it])
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Terms && vars.contentEquals(other.vars) &&
            coefficients.contentEquals(other.coefficients) && wideCoefficients.contentEquals(other.wideCoefficients)
    }

    private class Canonical(val terms: Terms, val bound: BigInt, val upper: Boolean)

    private fun canonicalize(row: LinearRow, scan: ScanMeter, allowActivated: Boolean = false): Canonical? {
        val constants = row.constants as? IntegralConstants ?: return null
        if ((!allowActivated && row.activator != LinearRow.ALWAYS) || row.relation == LinearOp.NE) return null
        if ((0 until row.size).any {
            visit(scan)
            !Term.isInt(row.ref(it))
        }) return null
        if (constants is IntegerConstants) {
            try {
                return canonicalizeLong(row, constants, scan)
            } catch (_: CheckedLongOverflowException) {
                // Coalescing, strictness and orientation must preserve values outside Long as well.
            }
        }
        return canonicalizeWide(row, constants, scan)
    }

    private fun canonicalizeLong(row: LinearRow, constants: IntegerConstants, scan: ScanMeter): Canonical? {
        val order = (0 until row.size).sortedBy {
            visit(scan)
            Term.intVar(row.ref(it))
        }
        val vars = IntArray(row.size)
        val coefficients = LongArray(row.size)
        var count = 0
        for (k in order) {
            visit(scan)
            val variable = Term.intVar(row.ref(k))
            if (count > 0 && vars[count - 1] == variable) {
                coefficients[count - 1] = addExact(coefficients[count - 1], constants.coeff(k))
            } else {
                vars[count] = variable
                coefficients[count++] = constants.coeff(k)
            }
        }
        var nonzero = 0
        for (k in 0 until count) {
            visit(scan)
            if (coefficients[k] == 0L) continue
            if (coefficients[k] == Long.MIN_VALUE) {
                throw CheckedLongOverflowException("coefficient magnitude exceeds Long")
            }
            vars[nonzero] = vars[k]
            coefficients[nonzero++] = coefficients[k]
        }
        if (nonzero == 0) return null
        val compact = coefficients.copyOf(nonzero)
        val gcd = PresolveShared.gcdOf(compact) { visit(scan) }
        var bound = constants.bound
        if (row.strict && row.relation == LinearOp.LE) bound = subExact(bound, 1L)
        if (row.strict && row.relation == LinearOp.GE) bound = addExact(bound, 1L)
        val negate = row.relation == LinearOp.GE
        if (negate) bound = subExact(0L, bound)
        if (row.relation == LinearOp.EQ && bound % gcd != 0L) return null
        val reducedBound = bound.floorDiv(gcd)
        val flip = (compact[0] < 0L) != negate
        for (k in compact.indices) {
            visit(scan)
            compact[k] /= gcd
            if (flip != negate) compact[k] = -compact[k]
        }
        return Canonical(
            Terms(vars.copyOf(nonzero), compact),
            bigIntOf(if (flip) subExact(0L, reducedBound) else reducedBound),
            upper = !flip,
        )
    }

    private fun canonicalizeWide(row: LinearRow, constants: IntegralConstants, scan: ScanMeter): Canonical? {
        val terms = HashMap<Int, BigInt>()
        for (k in 0 until row.size) {
            visit(scan)
            val variable = Term.intVar(row.ref(k))
            terms[variable] = (terms[variable] ?: BIG_ZERO) + constants.exactCoeff(k)
        }
        val ordered = terms.entries.filter {
            visit(scan)
            it.value != BIG_ZERO
        }.sortedBy {
            visit(scan)
            it.key
        }
        if (ordered.isEmpty()) return null
        val gcd = ordered.fold(BIG_ZERO) { value, term ->
            visit(scan)
            value.gcd(term.value.abs())
        }
        var bound = constants.exactBound
        if (row.strict && row.relation == LinearOp.LE) bound -= BIG_ONE
        if (row.strict && row.relation == LinearOp.GE) bound += BIG_ONE
        val sign = if (row.relation == LinearOp.GE) -BIG_ONE else BIG_ONE
        bound *= sign
        if (row.relation == LinearOp.EQ && bound % gcd != BIG_ZERO) return null
        val quotient = bound / gcd
        val reducedBound = if (bound < BIG_ZERO && bound % gcd != BIG_ZERO) {
            quotient - BIG_ONE
        } else {
            quotient
        }
        val flip = ordered.first().value * sign < BIG_ZERO
        val orientation = if (flip) -sign else sign
        val coefficients = ordered.map {
            visit(scan)
            it.value / gcd * orientation
        }.toTypedArray()
        val vars = ordered.map {
            visit(scan)
            it.key
        }.toIntArray()
        val key = if (coefficients.all {
            visit(scan)
            it.fitsLong()
        }) {
            Terms(vars, LongArray(coefficients.size) {
                visit(scan)
                coefficients[it].toLongExact()
            })
        } else {
            Terms(vars, null, coefficients)
        }
        return Canonical(
            key,
            if (flip) -reducedBound else reducedBound,
            upper = !flip,
        )
    }
}
