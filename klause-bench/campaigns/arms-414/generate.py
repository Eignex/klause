#!/usr/bin/env python3
"""Write bounded AWS specifications from the frozen selection; does not submit jobs."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = '5471419aa8c832ee1065468a6861bb203a1ff8c7'
MEASUREMENT = '52193ef906cfedac893b1df8390559ca0495b59e'


def config(engine, processors, label, ref=MEASUREMENT, **params):
    return dict(ref=ref, engine=engine, processors=str(processors), label=label,
                **{'param.' + k.replace('_', '-'): str(v) for k, v in params.items()})


def write(name, description, inputs, configs, timeout=300000, seeds=(3, 7, 11), repeats=1):
    spec = dict(name='arms-414-' + name, description=description, host='aws',
                problems=[dict(suite=x['suite'], name=x['problem'], max='1', **{'per-family': '1', 'reference': 'any'})
                          for x in inputs], base=dict(timeout=str(timeout)), configs=configs,
                seeds=list(seeds), repeats=repeats)
    (ROOT / 'specs' / (name + '.json')).write_text(json.dumps(spec, indent=2) + '\n')
    print(name, len(inputs) * len(configs) * len(seeds) * repeats, 'cases')


if __name__ == '__main__':
    selection = json.loads((ROOT / 'selection.json').read_text())
    all_inputs = selection['discovery'] + selection['holdout'] + selection['historical_sentinel']
    write('baseline-300', 'Frozen main production defaults: free and full portfolios at p1/p4, balanced discovery/holdout plus five historical families; 300 s, three seeds.',
          all_inputs, [config(e, p, f'main-{e}-p{p}', MAIN) for p in (1, 4) for e in ('cp', 'mixed')])
    # One active plateau family per format plus the early-closing OPB proof control.
    active = [selection['discovery'][i] for i in (0, 3, 5, 4)]
    write('reseed-300', 'Bounded reseed sweep on balanced active discovery families plus an OPB proof control; retain first-solution/deferred variants and existing slices.',
          active, [config('mixed', p, f'mixed-p{p}-r{r}', reseed_stale_threshold=r)
                   for p in (1, 4) for r in (3, 0, 2, 4)])
    pools = [config(e, p, f'{e}-p{p}-a{a}', arms=a) for p in (1, 4)
             for e in ('cp', 'mixed') for a in ((6, 12) if p == 1 else (8, 4, 12))]
    write('oversubscription-300', 'Arm composition/oversubscription at p1 6/12 and p4 4/8/12, balanced active discovery families plus a proof control; reseeding and slices unchanged.', active, pools)
    write('timing-repeat', 'Alternating identical-seed timing controls at production settings on one plateau and one early-closing proof input; distinct from 300 s outcome evidence.',
          [active[0], active[3]], [config(e, p, f'repeat-{e}-p{p}') for p in (1, 4) for e in ('cp', 'mixed')],
          timeout=15000, seeds=(3,), repeats=3)

    for name in ('reseed-300', 'oversubscription-300'):
        spec = json.loads((ROOT / 'specs' / (name + '.json')).read_text())
        for processors in ('1', '4'):
            split = dict(spec, name=spec['name'].replace('-300', '-p' + processors + '-300'),
                         configs=[c for c in spec['configs'] if c['processors'] == processors])
            (ROOT / 'specs' / (name.replace('-300', '-p' + processors + '-300') + '.json')).write_text(
                json.dumps(split, indent=2) + '\n')
