# Rewrite structure controls

Self-authored Eignex Klause sources, Apache-2.0. Version 1 fixes the source bytes
and catalog identities for affine Table-column and Element-index substitution.

`affine-table` has x=y+1, x=z+1 and y=w, with z,w in 0..2. Its minimum
z+w is 0 at x=1,y=z=w=0. Both affine coordinates have structured readers.
`element-cone` maps x=1,2,3 to result=2,4,6, with x=y+1 and y=w.
Its minimum result is 2. The Element creates a source-oriented result definition.

These are applicability and representation controls, not evidence of corpus speedup.
The lab's existing XCSP checker independently validates reconstructed source points.

The Element source uses Klause's legacy outer startIndex placement. Official
XCSP checking in job9219 rejected its points in both rewrite arms. It is retained
as a source-format negative, not a validated outcome. The separately named
rewrite-structure-canonical-v1 source places startIndex on list and retains its
own source hash and checks; original bytes and catalog membership remain fixed.
