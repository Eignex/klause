* Self-authored Eignex Klause regression fixture. Apache-2.0.
ROWS
 N COST
 G A
 L B
COLUMNS
 M0 'MARKER' 'INTORG'
 I COST 0
 M1 'MARKER' 'INTEND'
 X A 1 B 1
RHS
 RHS A 0.2 B 0.1
BOUNDS
 FX BN I 0
 LO BN X 0
 UP BN X 1
INDICATORS
 IF A I 1
 IF B I 1
ENDATA
