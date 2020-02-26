package compiler;

import compiler.antlr.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.misc.Interval;

import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList; 
import java.util.Queue;

public class CCompiler extends CBaseVisitor<String> {

  int mem; 
  int label_id; // for unique identification of each label (branch)
  Map<String, Integer> table = new HashMap<String, Integer>();
  boolean debug = false;
  boolean enter_parent = false;
  int param_count = 0;  // count parameters for function definition. No nested cases

  // Break: switch / while / for    (break_context)
  // Continue: while / for          (continue_context)
  // Return: functions              (return_context)

  Queue<String> current_break_context = new LinkedList<>();   // Break: switch / while / for    (break_context)
  Queue<String> current_continue_context = new LinkedList<>();   // Continue: while / for          (continue_context)
  Queue<String> current_return_context = new LinkedList<>();   // Return: functions              (return_context)
  Queue<Integer> current_switch_context = new LinkedList<>(); // Informs us of memory location of switch. Used for nested switches


  CCompiler(boolean d) {
    mem = 0;
    label_id = 0;
    debug = d;
    enter_parent = true;
    
    // scratch[0] = "$t0";
    // scratch[1] = "$t1";
    // scratch[2] = "$t2"; // r8-r15	($t0-$t7)	Temporaries, not saved
    // update: just manually use these scratch registers, easier to play around with order?
    
    /*

    MIPS allocation
    |----------|---------------|----------------------------|
    | Register | Assembly name | Comment                    |
    |----------|---------------|----------------------------|
    | r0       | $zero         | Always 0                   |
    | r1       | $at           | Reserved for assembler     |
    | r2-r3    | $v0-$v1       | Stores result              |
    | r4-r7    | $a0-$a3       | Stores arguments           |
    | r8-r15   | $t0-$t7       | Temporaries, not saved     |
    | r16-r23  | $s0-$s7       | Contents saved for later   |
    | r24-r25  | $t8-$t9       | More temporaries not saved |
    | r26-r27  | $k0-$k1       | Reserved by OS             |
    | r28      | $gp           | Global pointer             |
    | r29      | $sp           | Stack pointer              |
    | r30      | $fp           | Frame pointer              |
    | r31      | $ra           | Return address             |
    |----------|---------------|----------------------------|


    Our current usage:
    ---------------

    $v0         | return register of any context evaluation
    $t0-$t1     | scratch for LEFT OP RIGHT expressions
    $t2         | store value during self assignements OR assist comparaison



    Conventions:
    -------------

    - Any scratch register is undefined when leaving a context function and therefore can be used freely in another context
    - Return registers should always contain the correct / expected return value of the context    
    */
  }



  ////////////////////////////////////////////////////////////////////////////////////
  // HELPERS

  ////////////////////////////////////////////////////////////////////////////////////
  // expression helper
  // form LEFT OP RIGHT
  // $t0 has left
  // $t1 has right
  public String threeOp(ParserRuleContext ctx){

    // NEEDS STACK IMPLEMENT OF THE $SP
    // WILL BE ADDED LATER
    // FOR NOW REFER TO MEM AS AN OFFSET ON THE STACK

    this.visit(ctx.getChild(0));
    System.out.println("sw $v0, " + -4*(mem++) + "($sp)");

    this.visit(ctx.getChild(2));
    System.out.println("sw $v0, " + -4*(mem++) + "($sp)");

    System.out.println("lw $t1, " + -4*(--mem) + "($sp)");  // get right from stack
    System.out.println("lw $t0, " + -4*(--mem) + "($sp)");  // get left from stack
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // add comment of every line to debug easily
  @Override
  public String visitStatBlockItem(CParser.StatBlockItemContext ctx) {
    if(debug){
      int a = ctx.item.start.getStartIndex();
      int b = ctx.item.stop.getStopIndex();
      Interval interval = new Interval(a, b);
      String line = ctx.item.start.getInputStream().getText(interval);
      line = line.replaceAll("\n", "\n# ");
      System.out.println("\n# " + line);
    }
    this.visit(ctx.item);
    return "";
  }

  @Override
  public String visitDecBlockItem(CParser.DecBlockItemContext ctx) {
    if(debug){
      int a = ctx.item.start.getStartIndex();
      int b = ctx.item.stop.getStopIndex();
      Interval interval = new Interval(a, b);
      String line = ctx.item.start.getInputStream().getText(interval);
      line = line.replaceAll("\n", "\n# ");
      System.out.println("\n# " + line);
    }
    this.visit(ctx.item);
    return "";
  }

  // illegal argument exception helper
  public String throwIllegalArgument(String operand, String rule){
    throw new IllegalArgumentException("Found illegal operand " + operand + " when visiting context " + rule);
  }

  // makeName for label
  public String makeName(String base){
    return "_" + base + "_" + label_id++;
  }
  
  // insert label (to not forget the : )
  public void insertLabel(String label){
    System.out.println(label + ":");
  }

  // END OF HELPERS AND COMMON FUNCTIONS
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////




  ////////////////////////////////////////////////////////////////////////////////////
  // functions contexts

  ////////////////////////////////////////////////////////////////////////////////////
  // function definition

  /*

  Figure 1
  --------

  80...(HIGH)
  .----------.
  |    ra    |
  .----------.
  | fp (old) |
  .----------.
  |     3    |  <- $fp
  .----------.
  |     6    |  
  .----------.
  |          |  <- $sp
  .----------.
  |          |
  .----------.
  20...(LOW)

  */

  @Override
  public String visitFunctionDefinition(CParser.FunctionDefinitionContext ctx){
    String functionName = this.visit(ctx.func_dec);
    if(ctx.dec_list != null) this.visit(ctx.dec_list); // this is to intialize the function in the symbol table as well... for other functions and for its own good
    current_return_context.add("_return_" + functionName);
    System.out.println("# " + functionName + ": function full");
    insertLabel(functionName);
    System.out.println("\t.set noreorder\n\t.text\n\t.align 2\n\t.globl " + functionName);
    if(!functionName.equals("main")){
      System.out.println("# store function arguments\nsw $a0, 0($sp)\nsw $a1, 4($sp)\nsw $a2, 8($sp)\nsw $a3, 12($sp)\n"); // store arguments on main stack
      System.out.println("move $t0, $sp"); // remember where the stack pointer was
    }
    System.out.println("# " + functionName + ": function entry");
    // figure 1: get function header ready
    System.out.println("addiu $sp, $sp, -12\nsw $fp, 4($sp)\nsw $ra, 8($sp)\nmove $fp, $sp\n");
    // now load all input parameters on the function stack
    mem = 0; // REMOVE LATER. USE SYMBOLE TABLE
    for(int i=0; i<param_count; i++){
      System.out.println("lw $t1, " + 4*i + "($t0)");
      System.out.println("sw $t1, " + -4*(mem++) + "($sp)");
    }
    System.out.println("# " + functionName + ": function body");
    this.visit(ctx.comp_stat);
    insertLabel("# " + functionName + ": function return\n_return_" + functionName);
    // exit function: setback $fp and $sp as before. Get correct return address for subroutine
    System.out.println("move $sp, $fp\nlw $ra, 8($fp)\nlw $fp, 4($fp)\naddiu $sp, $sp, 12\njr $ra\nnop");
    current_return_context.poll();
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // function name (id) retrieved
  @Override
  public String visitIdDirDec(CParser.IdDirDecContext ctx){
    return ctx.id.getText();
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // function params context ( TYPE ID (PARAM_LIST) )
  @Override
  public String visitParamlDirDec(CParser.ParamlDirDecContext ctx){
    String functionName = this.visit(ctx.dec);
    param_count = 0;
    this.visit(ctx.paramL);
    table.put(functionName, param_count);
    return functionName;
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // function identifiers list ( TYPE ID (IDL?) or TYPE ID())
  @Override
  public String visitIdlDirDec(CParser.IdlDirDecContext ctx){
    String functionName = this.visit(ctx.dec);
    param_count = 0;
    if(ctx.idL != null) this.visit(ctx.idL);
    table.put(functionName, param_count);
    return functionName;
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // function invocation with n parameters (integers for now)
  // function returns in $v0. If type is double or float, use the $f registers discard $v0
  // we CANNOT get from symbol table because what if we have same function name, different param count?
  // count the parameters IN the call and then call the appropriate function
  @Override
  public String visitFuncInvocPostExpr(CParser.FuncInvocPostExprContext ctx){
    String functionName = this.visit(ctx.expr); // get function ID. From symbol table with type return later
    int argsCount = table.get(functionName); // move the stack pointer accordingly
    mem += argsCount;
    System.out.println("addiu $sp, $sp, " + -4*mem); // secure memory locations for arguments
    current_switch_context.add(argsCount-1); // save the count state for parameters (for nested cases like f(g(1), h(2, 3)) where another function gets ready for parameters). -1 because index starts at 0
    if(ctx.args != null) this.visit(ctx.args); // get parameters
    for(int i=0; i<4 && i<argsCount; i++){  // store parameters in $a0-$a3
      System.out.println("lw $a"+ i + " " + 4*i + "($sp)");
    }
    System.out.println("jal " + functionName + "\nnop"); // jump and link 
    current_switch_context.poll();
    return "u";
  }

  // end of function contexts
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////


  ////////////////////////////////////////////////////////////////////////////////////
  // parameters, initalizatiors, arguments

  ////////////////////////////////////////////////////////////////////////////////////
  // single parameter declaration in function declaration
  // should later add to param_count based on type for stack resizing
  @Override
  public String visitDecParamDec(CParser.DecParamDecContext ctx){
    param_count += 1;
    this.visit(ctx.spec);
    table.put(this.visit(ctx.dec), mem++);
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // function invocation arguments
  @Override
  public String visitSingleArgExprList(CParser.SingleArgExprListContext ctx) { 
    Integer currentArgumentCount = current_switch_context.poll();
    this.visit(ctx.expr); // value is in $v0. Only bottom part of stack is being used
    System.out.println("sw $v0 " + 4*currentArgumentCount + "($sp)");
    current_switch_context.add(currentArgumentCount-1);
    return "";
  }

  @Override
  public String visitMultArgExprList(CParser.MultArgExprListContext ctx) { 
    Integer currentArgumentCount = current_switch_context.poll();
    this.visit(ctx.expr); // value is in $v0. Only bottom part of stack is being used
    System.out.println("sw $v0 " + 4*currentArgumentCount + "($sp)");
    current_switch_context.add(currentArgumentCount-1);
    this.visit(ctx.args);
    return "";
  }









  ////////////////////////////////////////////////////////////////////////////////////
  // VARIABLES MANIPULATIONS

  ////////////////////////////////////////////////////////////////////////////////////
  // integer constant node
  @Override
  public String visitIntConstPrimaryExpr(CParser.IntConstPrimaryExprContext ctx) {
    System.out.println("ori $v0, $zero, " + ctx.val.getText());
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // variable identifier
  @Override
  public String visitIdPrimaryExpr(CParser.IdPrimaryExprContext ctx) {
    String id = ctx.id.getText();
    if(table.containsKey(id))
      System.out.println("lw $v0, " + -4*table.get(id)+ "($sp)"); // for now functions are not stored in table, only variables (since a function and a variable can have the same name)
    return id;  // return function name to caller (invoke in case of function at parent level)
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // declaring a variable with initialization
  // TYPE ID = VAL
  @Override
  public String visitOpInitDec(CParser.OpInitDecContext ctx) {
    this.visit(ctx.right);
    String varName = ctx.left.getText();
    System.out.println("sw $v0, " + -4*(mem) + "($sp)\t\t# \"" + varName + "\" was stored in " + String.format("0x%08X", -4*(mem)) + " on stack");
    table.put(varName, mem++);
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // declaring a variable without initialization
  // TYPE ID;
  // default value to zero
  @Override
  public String visitTermInitDec(CParser.TermInitDecContext ctx){
    String id = this.visit(ctx.dec);
    if(!table.containsKey(id)){
      table.put(id, mem++); // only store in table if not already there. Avoids storing functions IDs
      System.out.println("sw $zero, " + -4*(mem) + "($sp)");
    }
    return "";
  }

  // end variable manipulation
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////


  ////////////////////////////////////////////////////////////////////////////////////
  // jump statements

  ////////////////////////////////////////////////////////////////////////////////////
  // break statement
  @Override
  public String visitBreakJumpStat(CParser.BreakJumpStatContext ctx) {
    System.out.println("j " + current_break_context.peek() + "\nnop");
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // continue statement
  @Override
  public String visitContinueJumpStat(CParser.ContinueJumpStatContext ctx) {
    System.out.println("j " + current_continue_context.peek() + "\nnop");
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // return statement
  @Override
  public String visitReturnJumpStat(CParser.ReturnJumpStatContext ctx){
    if(ctx.expr != null) this.visit(ctx.expr);
    System.out.println("j " + current_return_context.peek() + "\nnop");
    return "";
  }















  ////////////////////////////////////////////////////////////////////////////////////
  // ARITHMETIC AND BINARY EXPRESSIONS

  ////////////////////////////////////////////////////////////////////////////////////
  // logical OR
  @Override
  public String visitOpLogOrExpr(CParser.OpLogOrExprContext ctx){
    String successEnd = makeName("logical_or_success");
    String failEnd = makeName("logical_or_fail");

    this.visit(ctx.left);
    System.out.println("bne $v0, $zero, " + successEnd + "\nnop"); // if left is zero, return false

    this.visit(ctx.right);
    System.out.println("bne $v0, $zero, " + successEnd + "\nnop"); // if right is zero, return false

    System.out.println("addu $v0, $zero, $zero"); // both weren't zero, return true
    System.out.println("j " + failEnd + "\nnop");
    insertLabel(successEnd);
    System.out.println("addiu $v0, $zero, 1");
    insertLabel(failEnd);
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // logical AND
  @Override
  public String visitOpLogAndExpr(CParser.OpLogAndExprContext ctx){
    String successEnd = makeName("logical_and_success");
    String failEnd = makeName("logical_and_fail");

    this.visit(ctx.left);
    System.out.println("beq $v0, $zero, " + failEnd + "\nnop"); // if left is zero, return false

    this.visit(ctx.right);
    System.out.println("beq $v0, $zero, " + failEnd + "\nnop"); // if right is zero, return false

    System.out.println("addiu $v0, $zero, 1"); // both weren't zero, return true
    System.out.println("j " + successEnd + "\nnop");
    insertLabel(failEnd);
    System.out.println("addu $v0, $zero, $zero");
    insertLabel(successEnd);
    return "";
  }
  
  ////////////////////////////////////////////////////////////////////////////////////
  // inclusive OR
  @Override
  public String visitOpIncOrExpr(CParser.OpIncOrExprContext ctx){
    threeOp(ctx);
    System.out.println("or $v0, $t0, $t1");
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // exclusive OR
  @Override
  public String visitOpExcOrExpr(CParser.OpExcOrExprContext ctx){
    threeOp(ctx);
    System.out.println("xor $v0, $t0, $t1");
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // AND
  @Override
  public String visitOpAndExpr(CParser.OpAndExprContext ctx){
    threeOp(ctx);
    System.out.println("and $v0, $t0, $t1");
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // shift operations (<< and >>)
  @Override
  public String visitOpShiftExpr(CParser.OpShiftExprContext ctx){
    threeOp(ctx);
    switch(ctx.op.getText()){
      case("<<"):
        System.out.println("sllv $v0, $t0, $t1");
        break;
      case(">>"):
        System.out.println("srav $v0, $t0, $t1");
        break;
      default:
        throwIllegalArgument(ctx.op.getText(), "OpShiftExpr");
    }
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // MUL, DIV, MOD
  @Override
  public String visitOpMultExpr(CParser.OpMultExprContext ctx){
    threeOp(ctx);
    switch(ctx.op.getText()){
      case("*"):
        System.out.println("mul $v0, $t0, $t1");
        break;
      case("/"):
        System.out.println("div $t0, $t1"); // left / right
        System.out.println("mflo $v0");
        break;
      case("%"):
        System.out.println("div $t0, $t1");
        System.out.println("mfhi $v0");
        break;
      default:
        throwIllegalArgument(ctx.op.getText(), "OpMultExpr");
    }
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // ADD, SUB
  @Override
  public String visitOpAddExpr(CParser.OpAddExprContext ctx) {
    threeOp(ctx);
    if (ctx.op.getText().equals("+")) {
      System.out.println("add $v0, $t0, $t1");
    } else {
      System.out.println("sub $v0, $t0, $t1");
    }
    return "";
  }

  // end arithmetic and binary expressions
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////















  ////////////////////////////////////////////////////////////////////////////////////
  // assignment operator


  @Override
  public String visitOpAssgnExpr(CParser.OpAssgnExprContext ctx) {
    // currently storing into int variable
    // will be modified later for arrays
    Integer destination = table.get(ctx.left.getText());
    this.visit(ctx.right);
    // $v0 contains the value of whatever was on the right
    System.out.println("sw $v0, " + -4*(mem++) + "($sp)"); // push right on stack
    this.visit(ctx.left); // evaluate current value (left)
    System.out.println("addu $t2, $v0, $zero"); // store current value in $t2
    System.out.println("lw $v0, " + -4*(--mem) + "($sp)"); // pop right from stack. Ready to evaluate
    switch(ctx.op.getText()){
      case("="):
        break; // do nothing. Store into destination at the end
      case("+="):
        System.out.println("add $v0, $v0, $t2");
        break;
      case("-="):
        System.out.println("sub $v0, $v0, $t2");
        break;
      case("*="):
        System.out.println("mul $v0, $v0, $t2");
        break;
      case("<<="):
        System.out.println("sllv $v0, $v0, $t2");
        break;
      case(">>="):
       System.out.println("srav $v0, $v0, $t2");
       break;
      case("&="):
        System.out.println("and $v0, $v0, $t2");
        break;
      case("|="):
        System.out.println("or $v0, $v0, $t2");
        break;
      case("^="):
        System.out.println("xor $v0, $v0, $t2");
        break;
      case("/="):
        System.out.println("div $v0");
        System.out.println("mflo $v0, $t2");
        break;
      case("%="):
        System.out.println("div $v0");
        System.out.println("mfhi $v0, $t2");
        break;
      default:
        throwIllegalArgument(ctx.op.getText(), "OpAssgnExpr");
    }
    System.out.println("sw $v0, " + -4*destination + "($sp)");
    
    return "";
  }

  // end assignment operator
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////

  
  ////////////////////////////////////////////////////////////////////////////////////
  // Comparaison based expressions


////////////////////////////////////////////////////////////////////////////////////
  // Relational expression
  @Override
  public String visitOpRelExpr(CParser.OpRelExprContext ctx){
    threeOp(ctx);
    System.out.println("xor $t2, $t0, $t1");
    System.out.println("sltiu $t2, $t2, 1");  // $t2 = (right == left)
    switch(ctx.op.getText()) {
      case ">":
        System.out.println("slt $v0, $t1, $t0"); // right < left
        break;
      case "<":
        System.out.println("slt $v0, $t0, $t1"); // left < right
        break;
      case "<=":
        System.out.println("slt $v0, $t1, $t0"); // right < left
        System.out.println("or $v0, $v0, $t2"); // right <= left
        break;
      case ">=": 
        System.out.println("slt $v0, $t0, $t1"); // left < right
        System.out.println("or $v0, $v0, $t2"); // left <= right
        break;
      default:
        throwIllegalArgument(ctx.op.getText(), "OpEqualExpr");
    }
    return "";
  }


  ////////////////////////////////////////////////////////////////////////////////////
  //Equality expression
  @Override
  public String visitOpEqualExpr(CParser.OpEqualExprContext ctx){
    threeOp(ctx);
    System.out.println("xor $v0, $t0, $t1"); // A^B = 0 only if A=B, non-zero otherwise
    switch(ctx.op.getText()){
      case "==":
        System.out.println("sltiu $v0, $v0, 1");
        break;
      case "!=":
        System.out.println("sltu $v0, $v0, $zero");
        break;
      default:
        throwIllegalArgument(ctx.op.getText(), "OpEqualExpr");
    }

    return "";
  }

  // end of comparison based expressions
  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////////////////////////////////////////////////////////////




  ////////////////////////////////////////////////////////////////////////////////////
  // Scoped contexts

  ////////////////////////////////////////////////////////////////////////////////////
  // Selection statement
  /* for the if statement:
    - compile the condition into register _cond_
    - insert a BEQ _cond_, zero, false_label
    - compile first statement into destReg
    - insert a J end_label (skip the false exec)
    - insert false_label
    - compile second statemnt into destReg
    - insert end_label
   */

  @Override
  public String visitIfSelecStat(CParser.IfSelecStatContext ctx) {
    String falseExecLabel = makeName("if_stat_false");
    String endLabel = makeName("if_stat_end");
    this.visit(ctx.cond); // $v0 holds 0 or 1 from the condition
    System.out.println("beq $v0, $zero, " + falseExecLabel + "\nnop");
    this.visit(ctx.trueExec);
    System.out.println("j " + endLabel + "\nnop");
    insertLabel(falseExecLabel);
    if(ctx.falseExec != null){
      this.visit(ctx.falseExec);
    }else{
      System.out.println("nop");
    }
    insertLabel(endLabel);
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // While loop expression

  /*for the while statement:
    - insert begin_label
    - compile the condition into register _cond_
    - insert a BEQ _cond_ _zero_ end_label
    - compile statement into destReg
    - insert a J begin_label 
    - insert end_label
  */

  @Override
  public String visitWhileIterStat(CParser.WhileIterStatContext ctx){
    String beginLabel = makeName("while_stat_begin");
    String endLabel = makeName("while_stat_end");
    current_break_context.add(endLabel);
    insertLabel(beginLabel);
    this.visit(ctx.cond); // condition is now in $v0
    System.out.println("beq $v0, $zero, " + endLabel + "\nnop");
    this.visit(ctx.exec); // while loop execution body
    System.out.println("j " + beginLabel + "\nnop");
    insertLabel(endLabel);
    current_break_context.poll();
    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // For loop expression

  /*for the for statement:
    - variable declaration
    - insert begin_label
    - compile the condition into register _cond_
    - insert a BEQ _cond_ _zero_ end_label
    - compile statement into destReg
    - insert a J begin_label 
    - insert end_label
  */

  @Override
  public String visitForIterStat(CParser.ForIterStatContext ctx){

    if(enter_parent){
      enter_parent = false;
      this.visit(ctx.cond);
    } else {
      this.visit(ctx.exec);
      enter_parent = true;
    }
    return "";
  }

  //The two possible for conditions. NOTE: can edit grammar to avoid repetition
  @Override
  public String visitDecForCond(CParser.DecForCondContext ctx){ //For loop 1

    //variable init
    this.visit(ctx.init);

    //for loop
    String beginLabel = makeName("for_stat_begin");
    String endLabel = makeName("for_stat_end");
    current_break_context.add(endLabel);
    insertLabel(beginLabel);
    this.visit(ctx.cond);

    //execution body
    System.out.println("beq $v0, $zero, " + endLabel + "\nnop");
    this.visit(ctx.getParent());

    //increment variable
    this.visit(ctx.update);

    //return to top of loop
    System.out.println("j " + beginLabel + "\nnop");
    insertLabel(endLabel);
    current_break_context.poll();
    return "";
  }

  @Override
  public String visitExpForCond(CParser.ExpForCondContext ctx){ //For loop 2
    //variable init
    this.visit(ctx.init);

    //for loop
    String beginLabel = makeName("for_stat_begin");
    String endLabel = makeName("for_stat_end");
    current_break_context.add(endLabel);
    insertLabel(beginLabel);
    this.visit(ctx.cond);

    //print statement
    System.out.println("beq $v0, $zero, " + endLabel + "\nnop");
    this.visit(ctx.getParent());


    //increment variable
    this.visit(ctx.update);

    //return to top of loop
    System.out.println("j " + beginLabel + "\nnop");
    insertLabel(endLabel);
    current_break_context.poll();
    return "";
  }

   ////////////////////////////////////////////////////////////////////////////////////
  // Switch case expression

  @Override
  public String visitSwitchSelecStat(CParser.SwitchSelecStatContext ctx){ 
    String endLabel = makeName("switch_stat_end");
    current_break_context.add(endLabel);
    this.visit(ctx.cond); //switch value loaded into register 2 ($v0) 
    //Save the variable
    current_switch_context.add(mem);
    System.out.println("sw $v0, " + -4*(mem++) + "($sp)"); //save variable in stack
    this.visit(ctx.trueExec);
    insertLabel(endLabel);
    current_break_context.poll();
    current_switch_context.poll();
    return "";
  }


  @Override
  public String visitCaseLabelStat(CParser.CaseLabelStatContext ctx){ 
    String endLabel = makeName("case_stat_end");
    int case_mem = current_switch_context.peek();
    this.visit(ctx.cond);
    System.out.println("lw $t0, " + -4*(case_mem) + "($sp)"); //load variable saved from SwitchSelec
    //compare switch value to case value
    System.out.println("bne $v0, $t0, " + endLabel + "\nnop"); //if not equal, jump to the end
    this.visit(ctx.exec);
    if(ctx.skip != null) this.visit(ctx.skip); // add the break BEFORE the end label
    //if break appears during exec, jump to end
    insertLabel(endLabel); 

    return "";
  }

  //Default case
  @Override
  public String visitDefLabelStat(CParser.DefLabelStatContext ctx){ 
    String endLabel = makeName("case_stat_end");

    //execute default
    this.visit(ctx.exec);
    //if break appears during switch selec stat, jump to endLabel. recommend global var.
    insertLabel(endLabel); 

    return "";
  }

  ////////////////////////////////////////////////////////////////////////////////////
  // end of scoped contexts
  ////////////////////////////////////////////////////////////////////////////////////



  ////////////////////////////////////////////////////////////////////////////////////
  // Array declarations
  
  @Override
  public String visitQArrDirDec(CParser.QArrDirDecContext ctx){ 
    System.out.println("Inside Qarr");
    return "u";
  }

  //TODO: static qualifier array implementation
  @Override
  public String visitSqArrDirDec(CParser.SqArrDirDecContext ctx){ 
    System.out.println("Inside SqArr");
    return "u";
  }

  //TODO: qualifier static array implementation
  @Override
  public String visitQsArrDirDec(CParser.QsArrDirDecContext ctx){ 
    System.out.println("Inside QsArr");
    return "u";
  }

  //TODO: dynamic array implementation
  @Override
  public String visitEArrDirDec(CParser.EArrDirDecContext ctx){ 
    System.out.println("Inside EArr");
    return "u";
  }

  ////////////////////////////////////////////////////////////////////////////////////



  ////////////////////////////////////////////////////////////////////////////////////
  // main class. create a tree and call a listener on the tree
  public static void main(String[] args) throws Exception {
    // create a CharStream that reads from standard input
    ANTLRInputStream input = new ANTLRInputStream(System.in); // create a lexer that feeds off of input CharStream
    CLexer lexer = new CLexer(input); // create a buffer of tokens pulled from the lexer
    CommonTokenStream tokens = new CommonTokenStream(lexer); // create a parser that feeds off the tokens buffer
    CParser parser = new CParser(tokens);
    ParseTree tree = parser.compilationUnit(); // begin parsing at init rule
    boolean debug = false;
    if(args.length > 0){
      if(args[0].equals("-debug"))debug = true;
    }
    CCompiler compiler = new CCompiler(debug);
    compiler.visit(tree);
    System.err.println(compiler.table);
  }

}