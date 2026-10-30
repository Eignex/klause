package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.SearchGroup
import com.eignex.klause.ir.SearchHints
import com.eignex.klause.ir.ValueSelection
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.VariableSelection
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun PreparedSourcePlan.sourceCoordinates(cancellation: Cancellation): VarRemap? {
    if (cancellation()) return null
    for (id in 0 until sourceRealCount) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coordinate = workingCoordinate(SourceCoordinate(SourceCoordinate.Kind.Real, id))
        if (coordinate.kind != SourceCoordinate.Kind.Real || coordinate.id != id) return null
    }
    val bools = IntArray(sourceBoolCount)
    val ints = IntArray(sourceIntCount)
    for (id in bools.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        bools[id] = booleanCoordinate(id)
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val coordinate = workingCoordinate(SourceCoordinate(SourceCoordinate.Kind.Integer, id))
        if (coordinate.kind != SourceCoordinate.Kind.Integer) return null
        ints[id] = coordinate.id
    }
    return VarRemap(bools, ints)
}

internal fun SearchHints.remapped(mapping: VarRemap, cancellation: Cancellation): SearchHints? {
    val groups = ArrayList<SearchGroup>(tiers.size)
    for (group in tiers) {
        cancellation.charge(1)
        if (cancellation()) return null
        val bools = mappedRoles(group.boolVars, mapping.boolCount, mapping::bool, cancellation) ?: return null
        val ints = mappedRoles(group.intVars, mapping.intCount, mapping::int, cancellation) ?: return null
        if (bools.isNotEmpty() || ints.isNotEmpty()) groups += group.copy(boolVars = bools, intVars = ints)
    }
    return if (cancellation()) null else copy(tiers = groups)
}

internal fun SearchHints.remapped(mapping: SourceMapping, cancellation: Cancellation): SearchHints? {
    val direct = mapping.retainedCoordinates(cancellation) ?: return null
    val translated = mapping.translatedCoordinates(cancellation) ?: return null
    val groups = ArrayList<SearchGroup>(tiers.size)
    for (group in tiers) {
        // Bound comparisons and midpoint choices need the source values, not just their ordering.
        val coordinates = if (group.varSelector != VariableSelection.SmallestLowerBound &&
            group.varSelector != VariableSelection.LargestUpperBound &&
            group.valueSelector in setOf(ValueSelection.Minimum, ValueSelection.Maximum)
        ) translated else direct
        val mapped = copy(tiers = listOf(group)).remapped(coordinates, cancellation) ?: return null
        groups += mapped.tiers
    }
    return if (cancellation()) null else copy(tiers = groups)
}

private fun SourceMapping.translatedCoordinates(cancellation: Cancellation): VarRemap? {
    var translated = VarRemap(IntArray(source.numBoolVars) { it }, IntArray(source.numIntVars) { it })
    for (stage in stages) {
        val retained = stage.retainedCoordinates(cancellation) ?: return null
        val ints = IntArray(retained.intCount) { retained.int(it) }
        for (step in stage.rebuild.steps) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (step is RebuildStep.AffineValue && step.termVars.size == 1 &&
                step.termCoeffs.single() == step.divisor && step.divisor != 0L &&
                step.constTerm % step.divisor == 0L
            ) {
                ints[step.variable] = ints[step.termVars.single()]
            }
        }
        translated = translated.followedBy(
            VarRemap(IntArray(retained.boolCount) { retained.bool(it) }, ints), cancellation,
        ) ?: return null
    }
    return if (cancellation()) null else translated
}

internal fun VarRemap.followedBy(next: VarRemap, cancellation: Cancellation): VarRemap? {
    if (cancellation()) return null
    val bools = IntArray(boolCount)
    val ints = IntArray(intCount)
    for (id in bools.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val target = bool(id)
        if (target >= next.boolCount) return null
        bools[id] = if (target < 0) -1 else next.bool(target)
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        val target = int(id)
        if (target >= next.intCount) return null
        ints[id] = if (target < 0) -1 else next.int(target)
    }
    return VarRemap(bools, ints)
}

private fun mappedRoles(ids: IntArray, count: Int, rename: (Int) -> Int, cancellation: Cancellation): IntArray? {
    val result = ArrayList<Int>(ids.size)
    for (id in ids) {
        cancellation.charge(1)
        if (cancellation() || id !in 0 until count) return null
        val target = rename(id)
        if (target >= 0) result += target
    }
    return result.toIntArray()
}

/** Exact integer reconstruction `(constTerm + Σ coefficients·terms) / divisor` over retained columns. */
internal class AffineReconstruction(
    val constTerm: Long,
    val terms: IntArray,
    val coefficients: LongArray,
    val divisor: Long,
)

/**
 * Integer columns [this] rebuilds by a single affine step that reads only columns it retains, keyed in its
 * source coordinates. Presolve preserves column slots, so the terms are direct target coordinates; a column
 * written by any other step, or rebuilt from a rebuilt column, has no direct reconstruction.
 */
internal fun SourceMapping.affineReconstructions(cancellation: Cancellation): Map<Int, AffineReconstruction>? {
    val retained = retainedCoordinates(cancellation) ?: return null
    val writes = HashMap<Int, Int>()
    val affine = HashMap<Int, RebuildStep.AffineValue>()
    for (stage in stages) {
        for (step in stage.rebuild.steps) {
            cancellation.charge(1)
            if (cancellation()) return null
            val written = when (step) {
                is RebuildStep.AffineValue -> step.variable.also { affine[it] = step }
                is RebuildStep.IntegerFromLiteral -> step.variable
                is RebuildStep.TupleValue -> step.variable
                is RebuildStep.QuotientValue -> step.variable
                else -> continue
            }
            writes[written] = (writes[written] ?: 0) + 1
        }
    }
    val reconstructions = HashMap<Int, AffineReconstruction>()
    for ((variable, step) in affine) {
        cancellation.charge(1)
        if (cancellation()) return null
        if (writes[variable] != 1) continue
        if (step.termVars.any { it == variable || it !in 0 until retained.intCount || retained.int(it) < 0 }) continue
        reconstructions[variable] = AffineReconstruction(step.constTerm, step.termVars, step.termCoeffs, step.divisor)
    }
    return if (cancellation()) null else reconstructions
}

/** [this] with every reconstructed column that equals one retained column exactly renamed onto it. */
internal fun VarRemap.withAliases(reconstructions: Map<Int, AffineReconstruction>): VarRemap {
    val ints = IntArray(intCount) { int(it) }
    for ((variable, reconstruction) in reconstructions) {
        if (variable !in ints.indices || reconstruction.constTerm != 0L || reconstruction.terms.size != 1) continue
        if (reconstruction.coefficients[0] == reconstruction.divisor) ints[variable] = int(reconstruction.terms[0])
    }
    return VarRemap(IntArray(boolCount) { bool(it) }, ints)
}

/**
 * The source row with each reconstructed column replaced by its reconstruction, the row scaled by that
 * reconstruction's divisor so every coefficient stays exact. A reconstructed output passes to its
 * reconstruction's single term, the column presolve kept in its place. A row whose output cancels, an
 * output reconstructed from several terms, or an overflowing coefficient declines.
 */
internal fun DefinitionHint.Linear.reconstructed(
    reconstructions: Map<Int, AffineReconstruction>,
): DefinitionHint.Linear? = try {
    val terms = LinkedHashMap<Int, Long>()
    var rhs = bound
    for (k in inputs.indices) {
        when (val operand = inputs[k]) {
            is DefinitionHint.Operand.Constant -> rhs = subExact(rhs, mulExact(coefficients[k], operand.value))
            is DefinitionHint.Operand.Variable -> terms[operand.id] = addExact(terms[operand.id] ?: 0L, coefficients[k])
        }
    }
    for (variable in terms.keys.filter { it in reconstructions }) {
        val reconstruction = reconstructions.getValue(variable)
        val coefficient = terms.getValue(variable)
        terms.remove(variable)
        for (entry in terms.entries) entry.setValue(mulExact(entry.value, reconstruction.divisor))
        rhs = subExact(mulExact(rhs, reconstruction.divisor), mulExact(coefficient, reconstruction.constTerm))
        for (j in reconstruction.terms.indices) {
            val term = reconstruction.terms[j]
            terms[term] = addExact(terms[term] ?: 0L, mulExact(coefficient, reconstruction.coefficients[j]))
        }
    }
    val target = reconstructions[output]?.terms?.singleOrNull() ?: output.takeIf { it !in reconstructions }
    val kept = terms.filterValues { it != 0L }
    if (target == null || target !in kept) {
        null
    } else {
        DefinitionHint.Linear(
            target, kept.values.toLongArray(),
            kept.keys.map { DefinitionHint.Operand.Variable(it) }.toTypedArray(), rhs,
        )
    }
} catch (_: CheckedLongOverflowException) {
    null
}

internal fun DefinitionHint.Comparison.reconstructed(
    reconstructions: Map<Int, AffineReconstruction>,
    cancellation: Cancellation,
): DefinitionHint.Comparison? {
    if (coefficients.size != inputs.size || cancellation()) return null
    return try {
        val terms = LinkedHashMap<Int, Long>()
        var rhs = bound
        for (k in inputs.indices) {
            cancellation.charge(1)
            if (cancellation()) return null
            val coefficient = coefficients[k]
            when (val operand = inputs[k]) {
                is DefinitionHint.Operand.Constant -> rhs = subExact(rhs, mulExact(coefficient, operand.value))
                is DefinitionHint.Operand.Variable -> {
                    val reconstruction = reconstructions[operand.id]
                    if (reconstruction == null) {
                        terms[operand.id] = addExact(terms[operand.id] ?: 0L, coefficient)
                    } else {
                        if (reconstruction.divisor != 1L) return null
                        rhs = subExact(rhs, mulExact(coefficient, reconstruction.constTerm))
                        for (j in reconstruction.terms.indices) {
                            cancellation.charge(1)
                            if (cancellation()) return null
                            val term = reconstruction.terms[j]
                            val added = mulExact(coefficient, reconstruction.coefficients[j])
                            terms[term] = addExact(terms[term] ?: 0L, added)
                        }
                    }
                }
            }
        }
        cancellation.charge(3L * terms.size)
        if (cancellation()) return null
        val kept = terms.filterValues { it != 0L }
        DefinitionHint.Comparison(output, kept.values.toLongArray(),
            kept.keys.map { DefinitionHint.Operand.Variable(it) }.toTypedArray(), rhs, relation, strict)
    } catch (_: CheckedLongOverflowException) {
        null
    }
}
