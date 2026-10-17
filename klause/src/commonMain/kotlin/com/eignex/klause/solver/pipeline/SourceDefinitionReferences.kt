package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.util.Cancellation

internal class SourceDefinitionReferences(definedVars: IntArray, boolFolds: List<BoolFoldDefinition>) {
    val integers = definedVars.copyOf()
    val folds = boolFolds.map { BoolFoldDefinition(it.out, it.lits.copyOf(), it.isAnd) }

    fun remapped(coordinates: VarRemap, cancellation: Cancellation): SourceDefinitionReferences? {
        if (cancellation()) return null
        val mappedIntegers = ArrayList<Int>()
        for (id in integers) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (id in 0 until coordinates.intCount) {
                coordinates.int(id).takeIf { it >= 0 }?.let(mappedIntegers::add)
            }
        }
        val mappedFolds = ArrayList<BoolFoldDefinition>()
        for (fold in folds) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (fold.out !in 0 until coordinates.boolCount) continue
            val output = coordinates.bool(fold.out)
            if (output < 0) continue
            val literals = IntArray(fold.lits.size)
            var retained = true
            for (i in fold.lits.indices) {
                cancellation.charge(1)
                if (cancellation()) return null
                val variable = Lit.variable(fold.lits[i])
                if (variable !in 0 until coordinates.boolCount || coordinates.bool(variable) < 0) {
                    retained = false
                    break
                }
                literals[i] = Lit.make(coordinates.bool(variable), Lit.isPositive(fold.lits[i]))
            }
            if (retained) mappedFolds += BoolFoldDefinition(output, literals, fold.isAnd)
        }
        return if (cancellation()) null else SourceDefinitionReferences(mappedIntegers.toIntArray(), mappedFolds)
    }
}
