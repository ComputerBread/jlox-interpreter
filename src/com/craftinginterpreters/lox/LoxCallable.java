package com.craftinginterpreters.lox;

import java.util.List;

/*
    Lox callable objects: function, class,
 */
public interface LoxCallable {

    /**
     *
     * Arity = number of arguments a function or operator expects.
     * @return the number of parameters
     */
    int arity();
    /**
     *
     * @param interpreter the interpreter in case it needs it
     * @param arguments The list of evaluated arguments
     * @return the call result
     */
    Object call(Interpreter interpreter, List<Object> arguments);
}
