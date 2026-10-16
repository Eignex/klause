package com.eignex.klause.formats.dimacs

import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics

/** Exact Boolean CNF semantics, without configurable numeric declaration defaults. */
val dimacsSemantics: SourceSemantics = SourceSemantics(SourceFormat.DIMACS)

/** Exact Boolean clauses and integer costs, without configurable numeric declaration defaults. */
val wcnfSemantics: SourceSemantics = SourceSemantics(SourceFormat.WCNF)
