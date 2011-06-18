package pxb.xjimple.ts;

import java.util.List;
import java.util.Stack;

import pxb.xjimple.JimpleMethod;
import pxb.xjimple.Local;
import pxb.xjimple.Trap;
import pxb.xjimple.Value.VT;
import pxb.xjimple.ValueBox;
import pxb.xjimple.expr.ArrayExpr;
import pxb.xjimple.expr.BinopExpr;
import pxb.xjimple.expr.CastExpr;
import pxb.xjimple.expr.Exprs;
import pxb.xjimple.expr.FieldExpr;
import pxb.xjimple.expr.InstanceOfExpr;
import pxb.xjimple.expr.InvokeExpr;
import pxb.xjimple.expr.NewArrayExpr;
import pxb.xjimple.expr.NewMutiArrayExpr;
import pxb.xjimple.expr.UnopExpr;
import pxb.xjimple.stmt.AssignStmt;
import pxb.xjimple.stmt.JumpStmt;
import pxb.xjimple.stmt.LookupSwitchStmt;
import pxb.xjimple.stmt.Stmt;
import pxb.xjimple.stmt.StmtList;
import pxb.xjimple.stmt.TableSwitchStmt;
import pxb.xjimple.stmt.UnopStmt;

public class LocalSpliter {

    public void split(JimpleMethod jm) {
        int orgLocalSize = jm.locals.size();
        for (int i = 0; i < orgLocalSize; i++) {
            Local local = jm.locals.get(i);
            local._ls_index = i;
        }

        StmtList list = jm.stmts;
        jm.locals.clear();
        List<Local> locals = jm.locals;
        int localId = 0;

        for (Trap t : jm.traps) {
            for (Stmt s = t.start.getNext(); s != t.end; s = s.getNext()) {
                s._ls_traps.add(t.handler);
            }
        }

        ValueBox[] tmp = new ValueBox[orgLocalSize];

        Stack<Stmt> toVisitStack = new Stack<Stmt>();

        toVisitStack.push(list.getFirst());

        // execute
        // merge to all branches
        while (!toVisitStack.isEmpty()) {
            Stmt currentStmt = toVisitStack.pop();
            if (currentStmt == null || currentStmt._ls_visited) {
                continue;
            } else {
                currentStmt._ls_visited = true;
            }
            if (currentStmt._ls_frame == null) {
                currentStmt._ls_frame = new ValueBox[orgLocalSize];
            }

            // System.out.println(currentStmt);

            ValueBox[] currentFrame = currentStmt._ls_frame;
            switch (currentStmt.st) {
            case GOTO:
                mergeFrame2Stmt(currentFrame, ((JumpStmt) currentStmt).target, locals);
                toVisitStack.push(((JumpStmt) currentStmt).target);
                break;
            case IF:
                mergeFrame2Stmt(currentFrame, ((JumpStmt) currentStmt).target, locals);
                mergeFrame2Stmt(currentFrame, currentStmt.getNext(), locals);

                toVisitStack.push(((JumpStmt) currentStmt).target);
                toVisitStack.push(currentStmt.getNext());
                break;
            case TABLE_SWITCH:
                TableSwitchStmt tss = (TableSwitchStmt) currentStmt;
                for (Stmt target : tss.targets) {
                    mergeFrame2Stmt(currentFrame, target, locals);
                    toVisitStack.push(target);
                }
                mergeFrame2Stmt(currentFrame, tss.defaultTarget, locals);
                toVisitStack.push(tss.defaultTarget);
                break;
            case LOOKUP_SWITCH:
                LookupSwitchStmt lss = (LookupSwitchStmt) currentStmt;
                for (Stmt target : lss.targets) {
                    mergeFrame2Stmt(currentFrame, target, locals);
                    toVisitStack.push(target);
                }
                mergeFrame2Stmt(currentFrame, lss.defaultTarget, locals);
                toVisitStack.push(lss.defaultTarget);
                break;
            case ASSIGN:
                AssignStmt assignStmt = (AssignStmt) currentStmt;
                switch (assignStmt.left.value.vt) {
                case LOCAL:
                    System.arraycopy(currentFrame, 0, tmp, 0, tmp.length);
                    Local local = (Local) assignStmt.left.value;
                    int reg = local._ls_index;
                    Local nLocal = Exprs.nLocal("a_" + localId++, null);
                    nLocal._ls_index = reg;
                    jm.locals.add(nLocal);
                    ValueBox nvb = new ValueBox(nLocal);
                    assignStmt.left = nvb;
                    tmp[reg] = nvb;
                    currentFrame = tmp;
                    mergeFrame2Stmt(currentFrame, currentStmt.getNext(), locals);
                }
                toVisitStack.push(currentStmt.getNext());
                break;
            case IDENTITY:
            case NOP:
            case LABEL:
            case LOCK:
            case UNLOCK:
                // merge to next next
                mergeFrame2Stmt(currentFrame, currentStmt.getNext(), locals);
                toVisitStack.push(currentStmt.getNext());
                break;
            case THROW:
            case RETURN:
            case RETURN_VOID:
                break;
            }

            for (Stmt t : currentStmt._ls_traps) {
                if (!t._ls_visited) {
                    mergeFrame2Stmt(currentFrame, t, locals);
                    toVisitStack.push(t);
                }
            }
        }

        for (Stmt t : list) {
            exec(t);
        }

    }

    private void exec(Stmt st) {
        ValueBox[] frame = st._ls_frame;
        switch (st.st) {
        case ASSIGN:
            AssignStmt assignStmt = (AssignStmt) st;
            if (assignStmt.left.value.vt != VT.LOCAL) {
                execValue(assignStmt.left, frame);
            }
            execValue(assignStmt.right, frame);
            break;
        case GOTO:
        case NOP:
        case LABEL:

        case RETURN_VOID:
            break;
        case IDENTITY:
            break;
        case IF:
            execValue(((JumpStmt) st).condition, frame);
            break;
        case LOOKUP_SWITCH:
            execValue(((LookupSwitchStmt) st).key, frame);
            break;
        case TABLE_SWITCH:
            execValue(((TableSwitchStmt) st).key, frame);
            break;
        case LOCK:
        case THROW:
        case UNLOCK:
        case RETURN:
            execValue(((UnopStmt) st).op, frame);
            break;
        }
    }

    private void execValue(ValueBox vb, ValueBox[] frame) {
        switch (vb.value.vt) {
        case LOCAL:
            vb.value = frame[((Local) vb.value)._ls_index].value;
            break;

        case CAST:
            CastExpr ce = (CastExpr) vb.value;
            execValue(ce.op, frame);
            break;
        case FIELD:
            FieldExpr fe = (FieldExpr) vb.value;
            if (null != fe.object) {
                execValue(fe.object, frame);
            }
            break;
        case INSTANCEOF:
            InstanceOfExpr ioe = (InstanceOfExpr) vb.value;
            execValue(ioe.op, frame);
            break;
        case LENGTH:
        case NEG:
            UnopExpr ue = (UnopExpr) vb.value;
            execValue(ue.op, frame);
            break;

        case NEW_ARRAY:
            NewArrayExpr nae = (NewArrayExpr) vb.value;
            execValue(nae.size, frame);

            break;
        case NEW_MUTI_ARRAY:
            NewMutiArrayExpr nmae = (NewMutiArrayExpr) vb.value;
            for (ValueBox size : nmae.sizes) {
                execValue(size, frame);
            }
            break;
        case ARRAY:
            ArrayExpr ae = (ArrayExpr) vb.value;
            execValue(ae.base, frame);
            execValue(ae.index, frame);
            break;
        case CMP:
        case CMPG:
        case CMPL:
        case ADD:
        case MUL:
        case AND:
        case DIV:
        case OR:
        case REM:
        case USHR:
        case XOR:
        case SUB:
        case SHL:
        case SHR:
        case EQ:
        case GE:
        case GT:
        case LE:
        case LT:
        case NE:
            BinopExpr be = (BinopExpr) vb.value;
            execValue(be.op1, frame);
            execValue(be.op2, frame);
            break;
        case INVOKE_STATIC:
        case INVOKE_SPECIAL:
        case INVOKE_VIRTUAL:
        case INVOKE_INTERFACE:
        case INVOKE_NEW:
            InvokeExpr methodExpr = (InvokeExpr) vb.value;
            if (null != methodExpr.object) {
                execValue(methodExpr.object, frame);
            }
            for (ValueBox arg : methodExpr.args) {
                execValue(arg, frame);
            }
            break;
        case EXCEPTION_REF:
        case THIS_REF:
        case PARAMETER_REF:
        case CONSTANT:
            break;
        }
    }

    private void mergeFrame2Stmt(ValueBox[] currentFrame, Stmt distStmt, List<Local> locals) {
        if (distStmt == null) {
            return;
        }
        if (distStmt._ls_frame == null) {
            distStmt._ls_frame = new ValueBox[currentFrame.length];
            System.arraycopy(currentFrame, 0, distStmt._ls_frame, 0, currentFrame.length);
        } else {
            ValueBox[] b = distStmt._ls_frame;
            for (int i = 0; i < currentFrame.length; i++) {
                ValueBox ai = currentFrame[i];
                if (ai != null) {
                    ValueBox bi = b[i];
                    if (ai != bi) {
                        if (ai.value != bi.value) {
                            locals.remove(bi.value);
                        }
                        bi.value = ai.value;
                    }
                }
            }
        }
    }
}