#!/usr/bin/env python3
"""Generate bounded, self-authored dense block LPs with the exact witness x = 1."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2] / 'smoke-corpus/lp-component-split'


def mzn(blocks, width, linked):
    link = 'constraint sum(b in 1..blocks)(x[b,1]) = blocks;\n' if linked else ''
    return f'''int: blocks = {blocks};
int: width = {width};
array[1..blocks, 1..width] of var 0.0..2.0: x;
var 0..1: k;
constraint int2float(k) + x[1,1] >= 1.0;
constraint forall(b in 1..blocks, r in 1..width)(
  sum(c in 1..width)((if c = r then width + 1 else 1 endif) * x[b,c]) = 2 * width
);
{link}solve minimize x[1,1];
output ["k = " ++ show(k) ++ ";\\nx = " ++ show(x) ++ ";\\n"];
'''


def mps(blocks, width):
    rows = [f' E  R{b}_{r}' for b in range(blocks) for r in range(width)]
    columns = []
    for b in range(blocks):
        for c in range(width):
            for r in range(width):
                columns.append(f'    X{b}_{c}  R{b}_{r}  {width + 1 if c == r else 1}')
    rhs = [f'    RHS  R{b}_{r}  {2 * width}' for b in range(blocks) for r in range(width)]
    bounds = [f' UP BND  X{b}_{c}  2' for b in range(blocks) for c in range(width)]
    return '\n'.join(['NAME COMPONENTS', 'ROWS', ' N  COST', *rows, 'COLUMNS', *columns,
                       'RHS', *rhs, 'BOUNDS', *bounds, 'ENDATA', ''])


if __name__ == '__main__':
    ROOT.mkdir(exist_ok=True)
    for name, blocks, width, linked in [('dense-2x12', 2, 12, False),
                                         ('dense-8x24', 8, 24, False),
                                         ('linked-8x24', 8, 24, True)]:
        (ROOT / f'{name}.mzn').write_text(mzn(blocks, width, linked))
    for name, blocks, width in [('dense-2x12', 2, 12), ('dense-8x24', 8, 24)]:
        (ROOT / f'{name}.mps').write_text(mps(blocks, width))
