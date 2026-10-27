package com.eignex.klause.formats.json

// Every factor a source model can hold. Engine projections that solving appends to a baked model
// (`DifferenceSystem`, `ExactSearchRow`, encoded theory rows and the objective-bound factor) carry
// derived or mutable search state rather than source relations, so they have no codec.
internal fun builtinFactorCodecs(): List<FactorCodec<*, *>> =
    booleanFactorCodecs() + linearFactorCodecs() + arithmeticFactorCodecs() + globalFactorCodecs() +
        structureFactorCodecs()
