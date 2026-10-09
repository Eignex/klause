# Targeted LP component split experiment

The explicit `lp-component-split` bench suite contains generated MPS feasibility and
MiniZinc optimization models, plus a linked MiniZinc control. Use the committed lab
specs and [measurement report](../../reports/lp-component-split-1455/README.md).

`generate.py` regenerates the five committed sources. The MiniZinc measurement requires
`exact=true` and its real objective to reach CP residual LP certification; default grid
floats and exact satisfaction models select different paths. The extra finite decision
does not join the continuous blocks. The optimum is 1, attained by x=1 and k=0 or 1.

`analyze.py <cases.json> <raw-directory>` summarizes saved lab records and checks dense
source witnesses against the original equations. Raw files are named `<case-index>.out`.
CLI statistics omit zero-valued optional counters; missing split/component counters are
read as zero. The script rejects failed/incomplete records and missing elapsed timing.
It preserves proof counts and incumbent times separately from subprocess duration.
