#jal
#14
#daryllimyt
#
#

addi $2, $0, 1
jal func
addi $2, $2, 1
addi $2, $2, 1
addi $2, $2, 1
jr $0
sll $0, $0, 0


func: addi $2, $2, 10
jr $31
