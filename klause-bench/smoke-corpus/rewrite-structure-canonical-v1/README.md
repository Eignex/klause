# Canonical Element rewrite control

Self-authored Eignex Klause source, Apache-2.0. This control places startIndex on
the XCSP3 list. It maps x=1,2,3 to result=2,4,6, with x=y+1 and y=w; the
mathematical optimum is 2. It has a distinct source identity from
rewrite-structure-v1/element-cone.xml, whose legacy outer startIndex placement
produced independently rejected points. Preserve those original bytes and receipts.

The transformation moves startIndex="1" from element to list without changing
other variables, constraints or objective text. Qualification uses the canonical
source's own hash, original-coordinate witness and official XCSP checker receipt.
