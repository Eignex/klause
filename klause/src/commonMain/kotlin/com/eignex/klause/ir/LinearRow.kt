package com.eignex.klause.ir

/**
 * One exact comparison over tagged integer columns, Boolean literals and real columns.
 * A Boolean literal contributes its 0/1 truth value. Discrete terms precede real terms when
 * [constants] are [RealConstants]. An [activator] reifies the comparison in both directions.
 */
interface LinearRow {
    /** Number of terms. */
    val size: Int

    /** Tagged [Term] reference of term [k]. */
    fun ref(k: Int): Int

    /** Coefficients and bound, with their exact width preserved. */
    val constants: LinearConstants

    /** Comparison against the right-hand side. */
    val relation: LinearOp

    /** Whether the comparison excludes equality. */
    val strict: Boolean get() = (constants as? RealConstants)?.strict ?: false

    /** Reifying Boolean variable, or [ALWAYS] for an unconditional comparison. */
    val activator: Int get() = ALWAYS

    /** Coefficient of term [k] for a row with [IntegerConstants]. */
    fun coeff(k: Int): Long = (constants as IntegerConstants).coeff(k)

    /** Right-hand side for a row with [IntegerConstants]. */
    val bound: Long get() = (constants as IntegerConstants).bound

    /** Whether every term is an integer column. */
    val isIntegerOnly: Boolean get() = (0 until size).all { Term.isInt(ref(it)) }

    /** Whether an unconditional, non-strict row admits 64-bit presolve arithmetic. */
    val isLongUnconditional: Boolean get() = constants is IntegerConstants && activator == ALWAYS && !strict &&
        (0 until size).none { Term.isReal(ref(it)) }

    /** Row factories retaining compact coefficient storage. */
    companion object {
        /** Sentinel for an unconditional row. */
        const val ALWAYS: Int = -1

        /** A row over integer column ids. */
        fun ofInts(vars: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            ofColumns(vars, intArrayOf(), IntegerConstants(constsOf(coeffs), bound), op)

        /** A row over Boolean literals with explicit weights. */
        fun ofBools(lits: IntArray, coeffs: LongArray, op: LinearOp, bound: Long): LinearRow =
            BooleanLinearRow(lits, IntegerConstants(constsOf(coeffs), bound), op)

        /** A row over Boolean literals with unit weights. */
        fun ofBools(lits: IntArray, op: LinearOp, bound: Long): LinearRow = BooleanLinearRow(
            lits,
            IntegerConstants(UnitConsts(lits.size), bound),
            op,
        )

        /** A row over integer and real columns, with an optional reifying Boolean variable. */
        fun ofColumns(
            ints: IntArray,
            reals: IntArray,
            constants: LinearConstants,
            op: LinearOp,
            activator: Int = ALWAYS,
        ): LinearRow = ColumnLinearRow(ints, reals, constants, op, activator)
    }
}

private class ColumnLinearRow(
    private val ints: IntArray,
    private val reals: IntArray,
    override val constants: LinearConstants,
    override val relation: LinearOp,
    override val activator: Int,
) : LinearRow {
    init {
        require(activator >= LinearRow.ALWAYS) { "invalid row activator" }
        when (constants) {
            is IntegerConstants -> require(
                size == constants.coefficients.size,
            ) { "row term and coefficient counts differ" }

            is WideConstants -> require(
                size == constants.coefficients.size,
            ) { "row term and coefficient counts differ" }

            is RealConstants -> require(
                ints.size == constants.intCoefficients.size && reals.size == constants.realCoefficients.size,
            ) { "row term and coefficient counts differ" }
        }
    }

    override val size: Int get() = ints.size + reals.size
    override val isIntegerOnly: Boolean get() = reals.isEmpty()
    override fun ref(k: Int): Int = if (k < ints.size) Term.ofIntVar(ints[k]) else Term.ofRealVar(reals[k - ints.size])
}

private class BooleanLinearRow(
    private val literals: IntArray,
    override val constants: IntegerConstants,
    override val relation: LinearOp,
) : LinearRow {
    init {
        require(literals.size == constants.coefficients.size) { "row term and coefficient counts differ" }
    }

    override val size: Int get() = literals.size
    override val isIntegerOnly: Boolean get() = false
    override fun ref(k: Int): Int = Term.ofLit(literals[k])
}

/** Immutable tagged row, with discrete terms before real terms for [RealConstants]. */
class TaggedLinearRow(
    private val refs: IntArray,
    override val constants: LinearConstants,
    override val relation: LinearOp,
    override val activator: Int = LinearRow.ALWAYS,
) : LinearRow {
    init {
        val coefficientCount = when (constants) {
            is IntegerConstants -> constants.coefficients.size
            is WideConstants -> constants.coefficients.size
            is RealConstants -> constants.intCoefficients.size + constants.realCoefficients.size
        }
        require(refs.size == coefficientCount) { "row term and coefficient counts differ" }
        require(activator >= LinearRow.ALWAYS) { "invalid row activator" }
        if (constants is RealConstants) {
            require(refs.indices.all { Term.isReal(refs[it]) == (it >= constants.intCoefficients.size) }) {
                "real terms must follow discrete terms"
            }
        }
    }

    override val size: Int get() = refs.size
    override fun ref(k: Int): Int = refs[k]
}

/** Disjoint references to integer columns, Boolean literals and real columns. */
object Term {
    /** Reference to integer variable [v]. */
    fun ofIntVar(v: Int): Int = v shl 2

    /** Reference to Boolean literal [lit]. */
    fun ofLit(lit: Int): Int = (lit shl 2) or 1

    /** Reference to real variable [v]. */
    fun ofRealVar(v: Int): Int = (v shl 2) or 2

    /** Whether [ref] references an integer column. */
    fun isInt(ref: Int): Boolean = (ref and 3) == 0

    /** Whether [ref] references a Boolean literal. */
    fun isBool(ref: Int): Boolean = (ref and 3) == 1

    /** Whether [ref] references a real column. */
    fun isReal(ref: Int): Boolean = (ref and 3) == 2

    /** Integer variable id of an integer reference. */
    fun intVar(ref: Int): Int = ref shr 2

    /** Boolean literal of a Boolean reference. */
    fun lit(ref: Int): Int = ref shr 2

    /** Real variable id of a real reference. */
    fun realVar(ref: Int): Int = ref shr 2
}
