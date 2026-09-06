package com.eignex.klause.util

/** Thrown when a checked 64-bit integer operation would overflow. */
class CheckedLongOverflowException(message: String) : ArithmeticException(message)

/** `a` + `b`, or throw [CheckedLongOverflowException] on 64-bit overflow. */
fun addExact(a: Long, b: Long): Long {
    val r = a + b
    if ((a xor r) and (b xor r) < 0L) throw CheckedLongOverflowException("addExact overflow: $a + $b")
    return r
}

/** `a` - `b`, or throw [CheckedLongOverflowException] on 64-bit overflow. */
fun subExact(a: Long, b: Long): Long {
    val r = a - b
    if ((a xor b) and (a xor r) < 0L) throw CheckedLongOverflowException("subExact overflow: $a - $b")
    return r
}

/** `a` * `b`, or throw [CheckedLongOverflowException] on 64-bit overflow. */
fun mulExact(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    val r = a * b
    if (r / a != b || (a == -1L && b == Long.MIN_VALUE)) {
        throw CheckedLongOverflowException("mulExact overflow: $a * $b")
    }
    return r
}
