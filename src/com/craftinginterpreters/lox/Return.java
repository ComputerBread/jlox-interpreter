package com.craftinginterpreters.lox;

public class Return extends RuntimeException {
    final Object value;

    Return(Object value) {
        super(null, null, false, false); // disables some JVM machinery we don't need
        this.value = value;
    }
}
