package com.github.guoyyit.scriptdebuggerjava.runtime;

import com.github.guoyyit.scriptdebuggerjava.lang.Parser;
import com.github.guoyyit.scriptdebuggerjava.lang.TokenType;
import com.github.guoyyit.scriptdebuggerjava.lang.ast.Expr;
import com.github.guoyyit.scriptdebuggerjava.lang.ast.Stmt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Tree-walking interpreter for the script language subset. */
public final class Interpreter {

    private static final class Frame {
        final String name;
        int line;
        final Environment env;

        Frame(String name, int line, Environment env) {
            this.name = name;
            this.line = line;
            this.env = env;
        }
    }

    private final DebugHook hook;
    private final Consumer<String> output;
    private final Deque<Frame> frames = new ArrayDeque<>();
    private final Parser parser = new Parser();
    private volatile List<FrameView> errorFrames;

    public Interpreter(DebugHook hook, Consumer<String> output) {
        this.hook = hook == null ? DebugHook.NONE : hook;
        this.output = output == null ? s -> {
        } : output;
    }

    /** Runs a whole program. */
    public void interpret(List<Stmt> program) {
        Environment globals = new Environment(null);
        installBuiltins(globals);
        frames.push(new Frame("<script>", 0, globals));
        try {
            executeBody(program, globals);
        } finally {
            frames.pop();
        }
    }

    /** Evaluates a single expression source inside an existing scope. */
    public Value evalExpression(String source, Environment env) {
        Expr expr = parser.parseExpression(source);
        return eval(expr, env);
    }

    /** Current call stack, innermost frame last. Only valid while paused. */
    public List<FrameView> frames() {
        List<FrameView> result = new ArrayList<>(frames.size());
        // Deque.iterator is top-first; callers expect outermost-first.
        Frame[] asArray = frames.toArray(new Frame[0]);
        for (int i = asArray.length - 1; i >= 0; i--) {
            Frame f = asArray[i];
            result.add(new FrameView(f.name, f.line, f.env));
        }
        return result;
    }

    /** Call stack captured when a runtime error was first raised, or null. */
    public List<FrameView> errorFrames() {
        return errorFrames;
    }

    private void installBuiltins(Environment globals) {
        globals.define("print", Value.builtin("print", (args, line) -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(args.get(i).toDisplay());
            }
            output.accept(sb.toString());
            return Value.nil();
        }));
    }

    // ---------- statements ----------

    private void executeBody(List<Stmt> body, Environment env) {
        for (Stmt stmt : body) {
            execute(stmt, env);
        }
    }

    private void execute(Stmt stmt, Environment env) {
        frames.peek().line = stmt.line();
        hook.beforeStatement(stmt, env);
        try {
            dispatch(stmt, env);
        } catch (ScriptRuntimeException e) {
            // Capture the stack while it is still intact (frames unwind during rethrow).
            if (errorFrames == null) {
                errorFrames = frames();
            }
            throw e;
        }
    }

    private void dispatch(Stmt stmt, Environment env) {
        if (stmt instanceof Stmt.LetStmt let) {
            Value init = let.init() == null ? Value.nil() : eval(let.init(), env);
            env.define(let.name(), init);
        } else if (stmt instanceof Stmt.AssignStmt assign) {
            env.assign(assign.name(), eval(assign.value(), env), stmt.line());
        } else if (stmt instanceof Stmt.ExprStmt exprStmt) {
            eval(exprStmt.expr(), env);
        } else if (stmt instanceof Stmt.IfStmt ifStmt) {
            boolean branch = eval(ifStmt.condition(), env).asBoolean(stmt.line());
            executeBody(branch ? ifStmt.thenBody() : ifStmt.elseBody(), env);
        } else if (stmt instanceof Stmt.WhileStmt whileStmt) {
            while (eval(whileStmt.condition(), env).asBoolean(stmt.line())) {
                executeBody(whileStmt.body(), env);
                // Re-run the hook before each further condition evaluation so
                // that timeouts, step limits and breakpoints also apply to
                // tight or infinite loops.
                frames.peek().line = stmt.line();
                hook.beforeStatement(stmt, env);
            }
        } else if (stmt instanceof Stmt.FunctionDef fn) {
            env.define(fn.name(), Value.function(fn, env));
        } else if (stmt instanceof Stmt.ReturnStmt ret) {
            Value value = ret.value() == null ? Value.nil() : eval(ret.value(), env);
            throw new ReturnSignal(value);
        } else {
            throw new ScriptRuntimeException("unsupported statement: " + stmt.getClass().getSimpleName(), stmt.line());
        }
    }

    // ---------- expressions ----------

    private Value eval(Expr expr, Environment env) {
        if (expr instanceof Expr.NumberLiteral n) {
            return Value.of(n.value());
        }
        if (expr instanceof Expr.BoolLiteral b) {
            return Value.of(b.value());
        }
        if (expr instanceof Expr.Variable v) {
            return env.get(v.name(), v.line());
        }
        if (expr instanceof Expr.Unary u) {
            return evalUnary(u, env);
        }
        if (expr instanceof Expr.Binary bin) {
            return evalBinary(bin, env);
        }
        if (expr instanceof Expr.Call call) {
            return evalCall(call, env);
        }
        throw new ScriptRuntimeException("unsupported expression: " + expr.getClass().getSimpleName(), expr.line());
    }

    private Value evalUnary(Expr.Unary u, Environment env) {
        Value operand = eval(u.operand(), env);
        return switch (u.op()) {
            case MINUS -> Value.of(-operand.asNumber(u.line()));
            case NOT -> Value.of(!operand.asBoolean(u.line()));
            default -> throw new ScriptRuntimeException("unsupported unary operator", u.line());
        };
    }

    private Value evalBinary(Expr.Binary bin, Environment env) {
        int line = bin.line();
        if (bin.op() == TokenType.AND) {
            boolean left = eval(bin.left(), env).asBoolean(line);
            if (!left) {
                return Value.of(false);
            }
            return Value.of(eval(bin.right(), env).asBoolean(line));
        }
        if (bin.op() == TokenType.OR) {
            boolean left = eval(bin.left(), env).asBoolean(line);
            if (left) {
                return Value.of(true);
            }
            return Value.of(eval(bin.right(), env).asBoolean(line));
        }

        Value left = eval(bin.left(), env);
        Value right = eval(bin.right(), env);

        switch (bin.op()) {
            case PLUS:
                return Value.of(left.asNumber(line) + right.asNumber(line));
            case MINUS:
                return Value.of(left.asNumber(line) - right.asNumber(line));
            case STAR:
                return Value.of(left.asNumber(line) * right.asNumber(line));
            case SLASH:
                return Value.of(divide(left.asNumber(line), right.asNumber(line), line));
            case PERCENT:
                return Value.of(modulo(left.asNumber(line), right.asNumber(line), line));
            case LT:
                return Value.of(left.asNumber(line) < right.asNumber(line));
            case LTE:
                return Value.of(left.asNumber(line) <= right.asNumber(line));
            case GT:
                return Value.of(left.asNumber(line) > right.asNumber(line));
            case GTE:
                return Value.of(left.asNumber(line) >= right.asNumber(line));
            case EQ:
                return Value.of(valueEquals(left, right));
            case NEQ:
                return Value.of(!valueEquals(left, right));
            default:
                throw new ScriptRuntimeException("unsupported binary operator", line);
        }
    }

    private Value evalCall(Expr.Call call, Environment env) {
        Value callee = env.get(call.name(), call.line());
        if (!callee.isFunction()) {
            throw new ScriptRuntimeException("'" + call.name() + "' is not a function", call.line());
        }
        List<Value> args = new ArrayList<>(call.args().size());
        for (Expr arg : call.args()) {
            args.add(eval(arg, env));
        }

        if (callee.isBuiltin()) {
            return callee.builtin().apply(args, call.line());
        }

        Stmt.FunctionDef def = callee.functionDef();
        if (args.size() != def.params().size()) {
            throw new ScriptRuntimeException(
                    "function '" + def.name() + "' expects " + def.params().size()
                            + " arguments, got " + args.size(),
                    call.line());
        }
        Environment callEnv = new Environment(callee.closure());
        for (int i = 0; i < args.size(); i++) {
            callEnv.define(def.params().get(i), args.get(i));
        }

        frames.push(new Frame(def.name(), call.line(), callEnv));
        try {
            executeBody(def.body(), callEnv);
            return Value.nil();
        } catch (ReturnSignal ret) {
            return ret.value();
        } finally {
            frames.pop();
        }
    }

    private static double divide(double left, double right, int line) {
        if (right == 0.0) {
            throw new ScriptRuntimeException("division by zero", line);
        }
        return left / right;
    }

    private static double modulo(double left, double right, int line) {
        if (right == 0.0) {
            throw new ScriptRuntimeException("modulo by zero", line);
        }
        return left % right;
    }

    private static boolean valueEquals(Value left, Value right) {
        if (left.kind() != right.kind()) {
            return false;
        }
        return switch (left.kind()) {
            case NUMBER -> left.asNumber(0) == right.asNumber(0);
            case BOOLEAN -> left.asBoolean(0) == right.asBoolean(0);
            case NIL -> true;
            case FUNCTION -> left == right;
        };
    }
}
