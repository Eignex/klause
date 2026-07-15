NAME          BLENDTINY
ROWS
 N  COST
 G  DEMAND
COLUMNS
    MK1       'MARKER'                 'INTORG'
    X1        COST           2.0   DEMAND         1.0
    X2        COST           3.0   DEMAND         1.0
    MK2       'MARKER'                 'INTEND'
RHS
    RHS       DEMAND         4.0
BOUNDS
 UP BND       X1             3.0
 UP BND       X2            10.0
ENDATA
