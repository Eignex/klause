package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpComponentShape

private const val MIN_COMPONENT_CELLS = 4096L
private const val MAX_COMPONENT_BYTES = 16L * 1024 * 1024
private const val MAX_COMPONENTS = 1024

internal fun LpEngine.admitRetainedComponents(shape: LpComponentShape): Boolean {
    val outcome = componentReuseAdmission(shape)
    sink.lp.observeComponentAdmission(outcome, shape.blocks, shape.modeledBytes)
    return outcome == "ADMITTED"
}

internal fun componentReuseAdmission(shape: LpComponentShape): String = when {
    shape.blocks !in 2..MAX_COMPONENTS -> "COMPONENT_COUNT"
    shape.monolithicCells < MIN_COMPONENT_CELLS -> "SMALL_MODEL"
    shape.modeledBytes > MAX_COMPONENT_BYTES -> "STORAGE"
    shape.componentCells > shape.monolithicCells - shape.monolithicCells / 4L -> "DOMINANT_COMPONENT"
    else -> "ADMITTED"
}
