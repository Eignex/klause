package com.eignex.klause.factor.table.internals

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntIntMap

/** Cached domain refs for the variable-array Element unchanged-domains fast path. */
internal class ElementCache(val cachedDoms: Array<IntDomain?>, val posOf: IntIntMap)
