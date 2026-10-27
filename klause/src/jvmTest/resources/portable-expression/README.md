# Portable expression conformance

`conformance.json` is the shared conformance fixture of the
[portable expression profile](../../../../../docs/03-input-semantics.md#portable-expressions).
It is self-authored. Kumulant keeps a byte-identical copy, so change both together.

Each case gives an `expr`, the operand sort `type` (`number` or `boolean`), numeric
`bindings` for its references and one outcome per environment: `integer` for Klause
integer expressions and `binary64` for Kumulant scalar expressions. An outcome is the
value the expression evaluates to (`"NaN"`, `"Infinity"` or `"-Infinity"` for
non-finite binary64 values), `"accept"` when the environment accepts the shape but the
bindings lie outside its domain, or `"reject"` when it must refuse the case before
producing a value. An accepted case re-encodes to `canonical`, or to `expr` when absent.
