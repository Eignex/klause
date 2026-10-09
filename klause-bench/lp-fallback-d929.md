# LP fallback measurements, session d929

## Refused float optima (#2342)

[Lab job 789](http://192.168.50.104:8420/jobs/789) compares control
`58ae735e7b527a9d5e1050a0111b78f38b8590f0` with instrumented candidate
`a0395fd70c2dd43ee8d2a8788288de06b986ff57`. The lab built each CLI with
`:klause-cli:installJvmDist`. It ran `miplib3/blend2`, `miplib3/egout` and
`miplib3/flugpl`, alternating arms, seed 1, three repeats, 20,000 ms per case,
`engine=cp processors=1 lp=default exact=false`. Selection used
`name=blend2,egout,flugpl max=3 seed=1 reference=any`; the cache was disabled.
The shared queue scheduled one case at a time on the lab host. No profiling was enabled.

The [case CSV](http://192.168.50.104:8420/experiments/789/cases.csv) and job Files page
retain the commands and results. Raw files, the experiment spec and independent witness checks
are also preserved in `/home/rasmus/Workspaces/lp-evidence/session-lp-d929/`.
Control and candidate build fingerprints are respectively
`808a303e4e5268ab6b810478b4831950b0b154223b3c23c25ebf11ebf3011bcc` and
`a0a14131b1095649d6b5188a0f3dedf7bcea33d70636b2378703e275b6b586c0`.
The bench's record policy is `reported-result-v1`; separate checks below validate witnesses.

| Instance | Outcomes, both arms | Median solve seconds, control / candidate | Candidate ladder calls, min–max | Median inclusive ladder seconds |
| --- | --- | --- | --- | --- |
| blend2 | unknown, all repeats | 19.854 / 19.853 | 11,395–11,691 | 12.029 |
| egout | feasible 606.0797, unproven, all repeats | 19.908 / 19.909 | 14,838–15,119 | 0.349 |
| flugpl | optimum 1201500, all repeats | 0.631 / 0.636 | 1,094 | 0.036 |

Flugpl supplied 46 optimal float candidates per repeat: all 138 were accepted directly.
The observed refusal rate is 0/138; exact-dual recovery and cleanup each ran zero times.
Acceptance took 3.016–3.066 ms per repeat in total. Blend2 and egout supplied no optimal
float candidates to the tolerance check. Their ladder traffic follows other float terminations,
so it does not measure refusal cost. Flugpl's ladder calls all certified infeasibility.
This small selection does not estimate a corpus-wide refusal rate or prove an instrumentation
speed effect. The budget-limited cases cannot establish runtime equivalence.

Every candidate import count is zero: these retained leaf consumers already supply exact state.
Consequently, this baseline shows no standalone authoritative-import cost that #2343 could remove
from these MIP runs. Moving construction at the standalone entry point needs separate source-path
coverage; it should not be credited with reducing these retained-leaf costs.

The independent source checker from
`lp-evidence/session-stage-c-exact-duals/check_witness.py` parsed the original MPS files and
checked all 12 feasible lab witnesses against their original rows, bounds and integer markers
at its documented relative 1e-7 tolerance. All passed. This establishes feasible witnesses,
not an independent optimality proof.

## Additional 80bau3b coverage

80bau3b is available locally but absent from the current lab catalog. One additional diagnostic
ran the rebuilt candidate CLI on
`lp-evidence/session-7.4/inputs/80bau3b.mps`, SHA-256
`abfd9c578df785018be663840ff798199c9a0991e0ea7a809794bf12d581fd56`, with
`-e cp -p 1 --lp default -r 1 -t 20000 -s`. It returned `OPTIMUM FOUND` at
987224.1924090875. The float dual check needed one exact-dual recovery, accepted after
13 refinement steps; cleanup and the exact ladder were not entered. Recorded exact-dual time
was 135.412 ms, within 162.081 ms of inclusive acceptance time. These are a single workstation
observation, with concurrent development activity, and are excluded from lab runtime comparisons.
The same independent source checker passed its witness with maximum relative violation
`1.084821096970424e-15`. This checks source feasibility, not exact optimality.

## Reading the counters

`lpPhase_<phase>_<route>_Calls`, `_Nanos` and outcome keys retain completed attempts,
including declines. Acceptance time contains exact-dual and cleanup time; those times must
not be added together. The exact ladder is timed separately and includes refinement setup.
`_Work` and `_Pivots` measure cleanup simplex work only; `_Steps` measures exact-dual
refinement only. Their zero placeholders on other phases are not estimates of those phases'
arithmetic work. Existing basis, continuation and per-certifier statistics remain available.

Targeted regressions exercise exact-dual recovery, zero-pivot cleanup followed by the ladder,
source refusal, immutable statistics snapshots, worker merging and CLI output. The full local
`check lintDocs` gate was skipped; GitHub CI supplies the full gate.
