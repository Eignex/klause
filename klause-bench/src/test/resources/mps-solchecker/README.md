# MIPLIB adapter fixtures

These small MPS inputs are self-authored Eignex Klause regression fixtures under
Apache-2.0. They exercise RANGES, exact coordinates beyond binary64 integer
precision, integer-marker default bounds and enabled/disabled indicator rows.
The marker unit-bounds input exercises `UP 1` then `LO 0` normalization without
discarding the explicit upper side.
The mixed binary-bounds input is outside the adopted reader profile: its upper
bound must not disappear when a lower bound follows a BV declaration. The tests
supply their known supported points directly to the external MIPLIB checker.
