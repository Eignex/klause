package com.eignex.klause.propagation

/** Initial scheduling for an integer row and its first-UIP clause. */
enum class IntegerRowAttachmentOrder {
    /** Propagate the row to a fixpoint before installing the clause. */
    SEPARATE,

    /** Seed one fixpoint with the row before its unit order-bound clause. */
    ROW_FIRST,

    /** Seed one fixpoint with the unit order-bound clause before its row. */
    CLAUSE_FIRST,
}
