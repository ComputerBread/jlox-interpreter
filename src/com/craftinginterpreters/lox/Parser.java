package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.List;
import static com.craftinginterpreters.lox.TokenType.*;

public class Parser {

    private static class ParseError extends RuntimeException {}

    private final List<Token> tokens;
    private int current = 0; // points to the next token eagerly waiting to be parsed

    Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    List<Stmt> parse() {
        List<Stmt> statements = new ArrayList<>();
        while (!isAtEnd()) {
            statements.add(declaration());
        }
        return statements;
    }

    private Stmt declaration() {
        try {
            if (match(VAR)) return varDeclaration();
            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

    private Stmt varDeclaration() {
        Token name = consume(IDENTIFIER, "Expect variable name.");

        Expr initializer = null;
        if (match(EQUAL)) {
            initializer = expression();
        }

        consume(SEMICOLON, "Expect ';' after variable declaration.");
        return new Stmt.Var(name, initializer);
    }

    private Stmt statement() {
        if (match(PRINT)) return printStatement();
        return expressionStatement();
    }

    private Stmt printStatement() {
        Expr value = expression();
        consume(SEMICOLON, "Expect ';' after value.");
        return new Stmt.Print(value);
    }

    private Stmt expressionStatement() {
        Expr expr = expression();
        consume(SEMICOLON, "Expect ';' after expression.");
        return new Stmt.Expression(expr);
    }

    private Expr expression() {
        return assignment();
    }

    /*
    express -> assignment;
    assignment  -> IDENTIFIER "=" assignment | equality ;
    The tricky part here is to know that we are parsing an assignment.
    The left-hand side of an assignment isn't an expression that evaluates to a value.
    We need to figure out what variable on the left (l-value) refers to, so we know where to store
    the right-hand side expression's value (r-value).
    l-value = r-value;

    An l-value "evaluates" to a storage location.
    We want the syntax tree to reflect that an l-value isn't evaluated like a normal expression.
    That's why Expr.Assign has a Token for the l-value.
    The problem is that the parser doesn't know it's parsing an l-value until it hits the "=".

    assignment is right-associative (a = b = c -> a = (b = c))

    The trick is that right before we create the assignment expression node,
    we look at the left-hand side expression and figure out what kind of assignment target it is.
    We convert the r-value expression node into an l-value representation.

    The left-hand side of an assignment could also work as a valid expression.
    This means that we can parse the left-hand side as if it were an expression (r-value)
    and then produce a syntax tree that turns it into an assignment target (l-value).

    If the left-hand side expression isn't a valid assignment target, we fail with a syntax
    error. (ex: of failure: a + b = c).

     */
    private Expr assignment() {

        Expr expr = equality();

        if (match(EQUAL)) {
            Token equals = previous();
            Expr value = assignment();

            if (expr instanceof Expr.Variable) {
                Token name = ((Expr.Variable)expr).name;
                return new Expr.Assign(name, value);
            }

            error(equals, "Invalid assignment target.");
        }
        return expr;

    }


    // check if token type(s) match the current token(s) and consume it/them
    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    // compare type with the type of the "current" token
    private boolean check(TokenType type) {
        if (isAtEnd()) return false;
        return peek().type == type;
    }

    // consume the current token
    private Token advance() {
        if (!isAtEnd()) current++;
        return previous();
    }

    private boolean isAtEnd() {
        return peek().type == EOF;
    }

    // return token at position "current" without consuming it
    private Token peek() {
        return tokens.get(current);
    }

    private Token previous() {
        return tokens.get(current - 1);
    }



    private Expr equality() {
        Expr expr = comparison();
        while (match(BANG_EQUAL, EQUAL_EQUAL)) {
            Token operator = previous();
            Expr right = comparison();
            expr = new Expr.Binary(expr, operator, right);
        }
        return expr;
    }

    private Expr comparison() {
        Expr expr = term();
        while (match(LESS, LESS_EQUAL, GREATER, GREATER_EQUAL)) {
            Token operator = previous();
            Expr right = term();
            expr = new Expr.Binary(expr, operator, right);
        }
        return expr;
    }

    private Expr term() {
        Expr expr = factor();
        while (match(MINUS, PLUS)) {
            Token operator = previous();
            Expr right = factor();
            expr = new Expr.Binary(expr, operator, right);
        }
        return expr;
    }

    private Expr factor() {
        Expr expr = unary();
        while (match(SLASH, STAR)) {
            Token operator = previous();
            Expr right = unary();
            expr = new Expr.Binary(expr, operator, right);
        }
        return expr;
    }

    private Expr unary() {
        if (match(BANG, MINUS)) {
            Token operator = previous();
            return new Expr.Unary(operator, unary());
        }
        return primary();
    }

    private Expr primary() {
       if (match(FALSE))  return new Expr.Literal(false);
       if (match(TRUE))   return new Expr.Literal(true);
       if (match(NIL))    return new Expr.Literal(null);

       if (match(NUMBER, STRING)) {
           return new Expr.Literal(previous().literal);
       }

       if (match(IDENTIFIER)) return new Expr.Variable(previous());

       if (match(LEFT_PAREN)) {
           Expr expr = expression();
           consume(RIGHT_PAREN, "Expect ')' after expression.");
           return new Expr.Grouping(expr);
       }

       // if any other token
       throw error(peek(), "Expected expression");
    }

    private Token consume(TokenType type, String message) {
        if (check(type)) return advance();
        throw error(peek(), message);
    }

    private ParseError error(Token token, String message) {
        Lox.error(token, message);
        // returns a ParseError, the caller decide to throw it or not
        return new ParseError();
    }

    /**
     * When an error is detected by the parser, it enters panic mode.
     * The source code doesn't match the grammar rule being parsed.
     * Before getting back to parsing, the parser needs to fix its state by jumping
     * out of any nested productions it's in, and find the next production.
     * This process is called synchronization.
     *
     * The traditional place to synchronize is between statement.
     * (an error occurred while parsing a statement, we discard everything until we find
     * the end of it (;), or find a new one).
     *
     * We can report the error, and continue parsing. Of course, any additional errors in
     * nested productions will be ignored. But it allows us to keep going quite easily.
     */
    private void synchronize() {
        advance();

        while (!isAtEnd()) {
            // if semicolon -> clearly end of statement
            if (previous().type == SEMICOLON) return;

            switch (peek().type) {
                case CLASS:
                case FUN:
                case VAR:
                case FOR:
                case IF:
                case WHILE:
                case PRINT:
                case RETURN:
                    return;
            }

            advance();
        }
    }

}
