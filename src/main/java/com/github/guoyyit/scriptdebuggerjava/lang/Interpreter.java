package com.github.guoyyit.scriptdebuggerjava.lang;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tree-walking interpreter. Executes statements in a controlled scope and
 * notifies an {@link ExecutionListener} before every statement so debuggers
 * can observe and control execution.
 *
 * <p>Maintains an explicit call-frame stack: every function call pushes a new
 * frame with its own local scope, so each call has independent locals.</p>
 */
public class Interpreter {

    /** Internal control-flow signal carrying a function return value. */
    private static class ReturnSignal extends RuntimeException {
        final Object value;

        ReturnSignal(Object value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    /** One active function invocation on the call stack. */
    private static class Frame {
        final String name;
        Environment env;
        int currentLine;

        Frame(String name, Environment env, int currentLine) {
            this.name = name;
            this.env = env;
            this.currentLine = currentLine;
        }
    }

    private final ExecutionListener listener;
    private final Consumer<String> stdout;
    private final Environment globals = new Environment();
    /** Call stack, top frame first. */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** Marker for built-in functions; hidden from variable snapshots. */
    private abstract static class NativeFunction implements ScriptCallable {
    }

    public Interpreter(ExecutionListener listener, Consumer<String> stdout) {
        this.listener = listener != null ? listener : (line, stack) -> { };
        this.stdout = stdout != null ? stdout : line -> { };
        this.globals.define("print", new NativeFunction() {
            @Override
            public int arity() {
                return 1;
            }

            @Override
            public Object call(Interpreter interpreter, List<Object> arguments, int callLine) {
                Interpreter.this.stdout.accept(stringify(arguments.get(0)));
                return null;
            }

            @Override
            public String toString() {
                return "<native fn print>";
            }
        });
    }

    public Environment globals() {
        return globals;
    }

    /**
     * Evaluates an expression against the currently active scopes. Must be
     * called from the executing thread (e.g. inside an
     * {@link ExecutionListener} callback) so the observed state is consistent.
     */
    public Object evaluateInCurrentContext(Expr expression) {
        return evaluate(expression);
    }

    public void interpret(List<Stmt> program) {
        frames.push(new Frame("<script>", globals, 1));
        try {
            for (Stmt stmt : program) {
                execute(stmt);
            }
        } finally {
            frames.pop();
        }
    }

    /**
     * Invokes a user function with a fresh frame and local scope.
     * Called by {@link ScriptFunction#call}.
     */
    public Object invokeFunction(ScriptFunction function, List<Object> arguments, int callLine) {
        Stmt.FnStmt declaration = function.declaration();
        Environment scope = new Environment(function.closure());
        for (int i = 0; i < declaration.params().size(); i++) {
            scope.define(declaration.params().get(i), arguments.get(i));
        }
        frames.push(new Frame(declaration.name(), scope, declaration.line()));
        try {
            executeBlock(declaration.body(), new Environment(scope));
            return null;
        } catch (ReturnSignal signal) {
            return signal.value;
        } finally {
            frames.pop();
        }
    }

    // ------------------------------------------------------------------
    // statements
    // ------------------------------------------------------------------

    private void execute(Stmt stmt) {
        Frame frame = frames.peek();
        if (frame != null) {
            frame.currentLine = stmt.line();
        }
        // Blocks have no runtime meaning of their own; while-loops notify once
        // per iteration instead (see below) so endless loops stay interruptible.
        if (!(stmt instanceof Stmt.BlockStmt) && !(stmt instanceof Stmt.WhileStmt)) {
            listener.beforeStatement(stmt.line(), snapshot());
        }
        try {
            if (stmt instanceof Stmt.ExprStmt s) {
                evaluate(s.expression());
            } else if (stmt instanceof Stmt.LetStmt s) {
                Object value = evaluate(s.initializer());
                frame.env.define(s.name(), value);
            } else if (stmt instanceof Stmt.BlockStmt s) {
                executeBlock(s.statements(), new Environment(frame.env));
            } else if (stmt instanceof Stmt.IfStmt s) {
                if (isTruthy(evaluate(s.condition()))) {
                    execute(s.thenBranch());
                } else if (s.elseBranch() != null) {
                    execute(s.elseBranch());
                }
            } else if (stmt instanceof Stmt.WhileStmt s) {
                while (isTruthy(evaluate(s.condition()))) {
                    listener.beforeStatement(s.line(), snapshot());
                    execute(s.body());
                }
            } else if (stmt instanceof Stmt.FnStmt s) {
                frame.env.define(s.name(), new ScriptFunction(s, frame.env));
            } else if (stmt instanceof Stmt.ReturnStmt s) {
                Object value = s.value() != null ? evaluate(s.value()) : null;
                throw new ReturnSignal(value);
            } else {
                throw new IllegalStateException("unknown statement " + stmt);
            }
        } catch (ScriptRuntimeException e) {
            throw enrich(e);
        }
    }

    private void executeBlock(List<Stmt> statements, Environment env) {
        Frame frame = frames.peek();
        Environment previous = frame.env;
        frame.env = env;
        try {
            for (Stmt stmt : statements) {
                execute(stmt);
            }
        } finally {
            frame.env = previous;
        }
    }

    // ------------------------------------------------------------------
    // expressions
    // ------------------------------------------------------------------

    private Object evaluate(Expr expr) {
        try {
            if (expr instanceof Expr.Literal e) {
                return e.value();
            } else if (expr instanceof Expr.Variable e) {
                return frames.peek().env.get(e.name(), e.line());
            } else if (expr instanceof Expr.Assign e) {
                Object value = evaluate(e.value());
                frames.peek().env.assign(e.name(), value, e.line());
                return value;
            } else if (expr instanceof Expr.Unary e) {
                Object operand = evaluate(e.operand());
                return switch (e.operator()) {
                    case MINUS -> {
                        checkNumber(operand, e.line());
                        yield -(double) operand;
                    }
                    case NOT -> !isTruthy(operand);
                    default -> throw new IllegalStateException("bad unary operator " + e.operator());
                };
            } else if (expr instanceof Expr.Logical e) {
                Object left = evaluate(e.left());
                if (e.operator() == TokenType.OR) {
                    return isTruthy(left) ? left : evaluate(e.right());
                }
                return isTruthy(left) ? evaluate(e.right()) : left;
            } else if (expr instanceof Expr.Binary e) {
                return evaluateBinary(e);
            } else if (expr instanceof Expr.Call e) {
                return evaluateCall(e);
            }
            throw new IllegalStateException("unknown expression " + expr);
        } catch (ScriptRuntimeException e) {
            throw enrich(e);
        }
    }

    private Object evaluateBinary(Expr.Binary expr) {
        Object left = evaluate(expr.left());
        Object right = evaluate(expr.right());
        int line = expr.line();
        return switch (expr.operator()) {
            case PLUS -> {
                if (left instanceof Double l && right instanceof Double r) {
                    yield l + r;
                }
                if (left instanceof String || right instanceof String) {
                    yield stringify(left) + stringify(right);
                }
                throw error("operands of '+' must be two numbers or include a string", line);
            }
            case MINUS -> {
                checkNumbers(left, right, line);
                yield (double) left - (double) right;
            }
            case STAR -> {
                checkNumbers(left, right, line);
                yield (double) left * (double) right;
            }
            case SLASH -> {
                checkNumbers(left, right, line);
                if ((double) right == 0) {
                    throw error("division by zero", line);
                }
                yield (double) left / (double) right;
            }
            case PERCENT -> {
                checkNumbers(left, right, line);
                if ((double) right == 0) {
                    throw error("division by zero", line);
                }
                yield (double) left % (double) right;
            }
            case LT -> {
                checkNumbers(left, right, line);
                yield (double) left < (double) right;
            }
            case LE -> {
                checkNumbers(left, right, line);
                yield (double) left <= (double) right;
            }
            case GT -> {
                checkNumbers(left, right, line);
                yield (double) left > (double) right;
            }
            case GE -> {
                checkNumbers(left, right, line);
                yield (double) left >= (double) right;
            }
            case EQ -> Objects.equals(left, right);
            case NE -> !Objects.equals(left, right);
            default -> throw new IllegalStateException("bad binary operator " + expr.operator());
        };
    }

    private Object evaluateCall(Expr.Call expr) {
        Object callee = evaluate(expr.callee());
        List<Object> arguments = new ArrayList<>();
        for (Expr argument : expr.arguments()) {
            arguments.add(evaluate(argument));
        }
        if (!(callee instanceof ScriptCallable callable)) {
            throw error("can only call functions", expr.line());
        }
        if (callable.arity() != arguments.size()) {
            throw error("expected " + callable.arity() + " arguments but got " + arguments.size(),
                    expr.line());
        }
        return callable.call(this, arguments, expr.line());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<FrameSnapshot> snapshot() {
        List<FrameSnapshot> result = new ArrayList<>();
        for (Frame frame : frames) { // ArrayDeque iterates head (top) first
            result.add(new FrameSnapshot(frame.name, frame.currentLine, visibleLocals(frame.env)));
        }
        return result;
    }

    private Map<String, Object> visibleLocals(Environment env) {
        Map<String, Object> result = new LinkedHashMap<>();
        Environment current = env;
        while (current != null) {
            for (Map.Entry<String, Object> entry : current.locals().entrySet()) {
                if (!(entry.getValue() instanceof NativeFunction)) {
                    result.putIfAbsent(entry.getKey(), entry.getValue());
                }
            }
            current = current.parent();
        }
        return result;
    }

    private ScriptRuntimeException enrich(ScriptRuntimeException e) {
        if (e.callStack() != null) {
            return e;
        }
        return new ScriptRuntimeException(e.getMessage(), e.line(), snapshot());
    }

    private ScriptRuntimeException error(String message, int line) {
        return new ScriptRuntimeException(message, line, null);
    }

    private void checkNumber(Object value, int line) {
        if (!(value instanceof Double)) {
            throw error("operand must be a number, got " + typeName(value), line);
        }
    }

    private void checkNumbers(Object left, Object right, int line) {
        if (!(left instanceof Double) || !(right instanceof Double)) {
            throw error("operands must be numbers, got " + typeName(left) + " and "
                    + typeName(right), line);
        }
    }

    private String typeName(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Double) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof String) {
            return "string";
        }
        return "function";
    }

    private boolean isTruthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return true;
    }

    /** Formats a script value the way users expect in stdout and snapshots. */
    public static String stringify(Object value) {
        if (value == null) {
            return "nil";
        }
        if (value instanceof Double d) {
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return Long.toString((long) (double) d);
            }
            return Double.toString(d);
        }
        return value.toString();
    }
}
