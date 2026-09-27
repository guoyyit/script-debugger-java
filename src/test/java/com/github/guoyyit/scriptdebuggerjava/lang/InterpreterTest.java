package com.github.guoyyit.scriptdebuggerjava.lang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterpreterTest {

    private List<String> run(String source) {
        List<Stmt> program = new Parser().parse(new Lexer().scan(source));
        List<String> out = new ArrayList<>();
        new Interpreter(null, out::add).interpret(program);
        return out;
    }

    @Test
    void arithmeticAndVariables() {
        List<String> out = run("""
                let x = 1 + 2 * 3;
                let y = (x - 1) / 2;
                print(x);
                print(y);
                print(x % 2);
                """);
        assertEquals(List.of("7", "3", "1"), out);
    }

    @Test
    void booleanExpressions() {
        List<String> out = run("""
                print(1 < 2 && 3 >= 3);
                print(true || false && false);
                print(!false);
                print(1 == 1);
                print(1 != 2);
                """);
        assertEquals(List.of("true", "true", "true", "true", "true"), out);
    }

    @Test
    void strings() {
        List<String> out = run("""
                let name = "world";
                print("hello " + name);
                """);
        assertEquals(List.of("hello world"), out);
    }

    @Test
    void ifElse() {
        List<String> out = run("""
                let x = 10;
                if (x > 5) { print("big"); } else { print("small"); }
                if (x < 5) { print("big"); } else { print("small"); }
                """);
        assertEquals(List.of("big", "small"), out);
    }

    @Test
    void whileLoop() {
        List<String> out = run("""
                let i = 0;
                let sum = 0;
                while (i < 5) {
                    sum = sum + i;
                    i = i + 1;
                }
                print(sum);
                """);
        assertEquals(List.of("10"), out);
    }

    @Test
    void functionsHaveIndependentScopes() {
        List<String> out = run("""
                fn add(a, b) {
                    let c = a + b;
                    return c;
                }
                fn counter() {
                    let x = 100;
                    return x;
                }
                print(add(1, 2));
                print(counter());
                print(add(3, 4));
                """);
        assertEquals(List.of("3", "100", "7"), out);
    }

    @Test
    void recursion() {
        List<String> out = run("""
                fn fib(n) {
                    if (n < 2) { return n; }
                    return fib(n - 1) + fib(n - 2);
                }
                print(fib(10));
                """);
        assertEquals(List.of("55"), out);
    }

    @Test
    void undefinedVariableFails() {
        ScriptRuntimeException e = assertThrows(ScriptRuntimeException.class,
                () -> run("print(nope);"));
        assertTrue(e.getMessage().contains("nope"));
        assertEquals(1, e.line());
    }

    @Test
    void divisionByZeroFails() {
        ScriptRuntimeException e = assertThrows(ScriptRuntimeException.class,
                () -> run("let x = 1 / 0;"));
        assertTrue(e.getMessage().contains("division by zero"));
    }

    @Test
    void runtimeErrorCarriesCallStack() {
        ScriptRuntimeException e = assertThrows(ScriptRuntimeException.class, () -> run("""
                fn boom() {
                    let x = 1;
                    return x + nil;
                }
                boom();
                """));
        assertEquals(3, e.line());
        assertEquals(2, e.callStack().size());
        assertEquals("boom", e.callStack().get(0).functionName());
        assertEquals("<script>", e.callStack().get(1).functionName());
    }

    @Test
    void parseErrorReportsLine() {
        Parser.ParseException e = assertThrows(Parser.ParseException.class,
                () -> run("let x = ;"));
        assertEquals(1, e.line());
    }
}
