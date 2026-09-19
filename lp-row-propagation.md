# Exact row propagation

Wave 7.2 adoption is deferred. The experiment at
`f41152f250e91f459bd67594fcafbb6d96ee6cc8`, based on
`233e81eecb028f314cff9898bc70b29692989956`, was independently reviewed and removed.
The review supports deferral, not production readiness. No optional helper, runtime option,
publication interface or caller dependency remains in this change.

The experiment derived exact bounds from active original equations, including rational coefficients,
strict endpoints, integer rounding and recursive source reasons. Registered source atoms returned
through the shared propagation queue for native application. It supported bounded cold LP states;
prepared states, retained reductions, unsupported mappings and unavailable source premises declined.

Six natural SMT sources ran through the real QfLira consumer with 1,000 shared checks and 100 search
decisions. Both arms returned four SAT and two UNSAT results. Propagation installed three source
implications and reduced engine checks from seven to six, but nine row passes increased combined
checks from seven to fifteen. Two rebuilt SMT/CP CLI controls completed the eight-case source cohort.

The same six sources with one shared check failed the frozen no-quality-regression gate: four SAT
answers became UNKNOWN. The other UNSAT and UNKNOWN results were unchanged. This implementation's
shared-check scheduling loses certified solves under a scarce budget; it is not evidence of unsound
deductions or a universal lack of benefit.

The composition review also found incomplete resource accounting. Each publication induces a full
QfLira source-factor replay before the next allowance check. Deduplicated LP row dimensions do not
bound the original factor count, so the retained receipt does not bound that additional work.
Inspection confirmed queued predicate and reservation enforcement, but adverse real-caller tests
changing branch-count or reduction eligibility between publication and application are missing.

Eight directed cases ran 100 times per batch, with one warmup per arm and exactly three alternating
pairs on a busy host. Median complete fixture time was 170.73 ms off and 110.00 ms on; measured ranges
were 94.23–209.86 ms and 51.87–119.73 ms. Median thread allocation was 104,891,616 and 78,650,528 bytes.
Each batch used 800/600 engine checks and 800/1,700 combined checks. All declines and no-gain cases
remain in the denominator. These timings describe directed fixtures with substantial warmup/order
variation, not broad source-solver performance or a replacement for the failed quality gate.

Independent Z3 checks passed all 33 original-source assignment, implication and conflict queries;
18 explicit lifecycle cases and 28 unit/caller/source checks passed. Natural implication queries use
the full original source; directed queries use original antecedents. Two cold integration tests
exceeded 300 ms and were moved to the external evidence runner. Failed attempts, coverage limits,
the frozen contract, measurements, reviews and a verified restorable patch/bundle including caller
dependencies are preserved in `/home/rasmus/Workspaces/lp-evidence/session-7.2/`. The required full
gate applies to the final disposition tree, not the archived implementation.

Wave 7.3 can proceed without row propagation or a Wave 5.7 interface dependency. Any revival must
bound induced source replay before work, test the queued caller mutation paths, and revisit shared
check scheduling under new predeclared consumer evidence while preserving exact source authority.
