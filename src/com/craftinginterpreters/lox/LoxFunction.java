package com.craftinginterpreters.lox;

import java.util.List;

public class LoxFunction implements LoxCallable {

    private final Stmt.Function declaration;
    private final Environment closure;
    private final boolean isInitializer;

    LoxFunction(Stmt.Function declaration, Environment closure, boolean isInitializer) {
        this.declaration = declaration;
        this.closure = closure;
        this.isInitializer = isInitializer;
    }

    @Override
    public int arity() {
        return declaration.params.size();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {

        // create a new environment
        Environment environment = new Environment(closure);

        // bind args to params (param.name (key) => arg[i] (value)).
        for (int i = 0; i < declaration.params.size(); i++) {
            environment.define(declaration.params.get(i).lexeme, arguments.get(i));
        }

        // try-catch to catch the return value
        try {
            interpreter.executeBlock(declaration.body, environment);
        } catch (Return returnValue) {
            // if in a constructor, we return "this"
            if (isInitializer) return closure.getAt(0, "this");

            return returnValue.value;
        }

        // init(), always returns "this".
        if (isInitializer) return closure.getAt(0, "this");

        return null;
    }

    @Override
    public String toString() {
        return "<fn " + declaration.name.lexeme + ">";
    }

    /*
    Create a new environment nestled inside the method's original closure.
    When we declare a class, its methods have the class scope as their environment (closure).
    When a method is used, we create a new environment
    that binds "this" to the instance that was before it.
     */
    LoxFunction bind(LoxInstance instance) {
        Environment environment = new Environment(closure);
        environment.define("this", instance);
        return new LoxFunction(declaration, environment, isInitializer);
    }
}
