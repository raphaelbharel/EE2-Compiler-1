#lb
#70
#daryllimyt
#getc with offset
#HF
#

lui $3, 0x3000
lb $4, 0($3)
lb $5, 3($3)
nop
add $2, $4, $5
jr $0