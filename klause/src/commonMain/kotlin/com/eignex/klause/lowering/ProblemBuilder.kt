package com.eignex.klause.lowering

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem

/**
 * Mutable construction context for a solver [Problem].
 *
 * Front-ends and schema compilation allocate source columns and auxiliary variables through this
 * context, emit factors into [factors], and finish with [build]. Source names remain available to
 * callers that need to decode or render a solution, while the built problem stays name-free.
 */
internal class ProblemBuilder : CnfLowering {
    override val factors = mutableListOf<Factor>()
    override var trueLitCache: Int = -1

    private val allocatedIntDomains = mutableListOf<IntDomain>()
    private val realLowerBounds = mutableListOf<Double>()
    private val realUpperBounds = mutableListOf<Double>()

    val boolVarIdByName = mutableMapOf<String, Int>()
    val intVarIdByName = mutableMapOf<String, Int>()
    val realVarIdByName = mutableMapOf<String, Int>()

    val intDomains: List<IntDomain> get() = allocatedIntDomains
    val numBoolVars: Int get() = nextBool
    val numIntVars: Int get() = allocatedIntDomains.size
    val numRealVars: Int get() = realLowerBounds.size

    private var nextBool = 0

    override fun newBool(): Int = newBool(name = null)

    fun newBool(name: String?): Int {
        val id = nextBool++
        if (name != null) bindBoolName(name, id)
        return id
    }

    fun reserveBoolVars(count: Int) {
        require(count >= nextBool) { "cannot reserve $count Boolean variables after allocating $nextBool" }
        nextBool = count
    }

    fun newInt(domain: IntDomain, name: String? = null): Int {
        val id = allocatedIntDomains.size
        allocatedIntDomains += domain
        if (name != null) bindIntName(name, id)
        return id
    }

    fun newReal(lower: Double, upper: Double, name: String? = null): Int {
        val id = realLowerBounds.size
        realLowerBounds += lower
        realUpperBounds += upper
        if (name != null) bindRealName(name, id)
        return id
    }

    fun bindBoolName(name: String, id: Int): Int {
        require(id in 0 until nextBool) { "Boolean variable $id has not been allocated" }
        boolVarIdByName[name] = id
        return id
    }

    fun bindIntName(name: String, id: Int): Int {
        require(id in allocatedIntDomains.indices) { "integer variable $id has not been allocated" }
        intVarIdByName[name] = id
        return id
    }

    fun bindRealName(name: String, id: Int): Int {
        require(id in realLowerBounds.indices) { "real variable $id has not been allocated" }
        realVarIdByName[name] = id
        return id
    }

    fun build(): Problem = Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = allocatedIntDomains.toTypedArray(),
        factors = factors.toTypedArray(),
        numRealVars = numRealVars,
        realLower = realLowerBounds.toDoubleArray(),
        realUpper = realUpperBounds.toDoubleArray(),
    )
}
