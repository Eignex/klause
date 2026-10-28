# Thin integer aggregate controls

Self-authored Eignex fixtures, Apache-2.0. The frozen `integer-aggregate-v1`
suite contains the finite and open controls from
[#3793](https://github.com/Eignex/klause/issues/3793), plus its gcd control.
All three are mathematically infeasible; this is independent of source admission
or a resource-limited solver's verdict.

In `finite-gap`, eight integers have bounds 0..1 and weighted activity 450..451
with coefficients 101..108. Writing `s = sum(x(i))`, `s <= 4` allows activity
at most 436, and `s >= 5` forces activity at least 500.
In `open-gap` only the nonnegative sides are declared; activity is between
`101*s` and `108*s`, excluding both adjacent alternatives.
`gcd-gap` asserts `2*s = 9`, which integer normalization can reject directly.

These controls establish a mathematical discriminator, not measured applicability,
checked global coverage or a throughput claim. Original hashes are retained by the
ordinary selection and solve receipts.
