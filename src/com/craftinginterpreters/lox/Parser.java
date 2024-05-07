package com.craftinginterpreters.lox;

import com.sun.tools.jconsole.JConsoleContext;

import java.util.ArrayList;
import java.util.Arrays;
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
            if (match(FUN)) return function("function");
            if (match(VAR)) return varDeclaration();
            return statement();
        } catch (ParseError error) {
            synchronize();
            return null;
        }
    }

   private Stmt function(String kind) {

        // name
        Token name = consume(IDENTIFIER, "Expect " + kind + " name.");

        // parameters
        consume(LEFT_PAREN, "Expect '(' after " + kind + " name.");
        List<Token> parameters = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                if (parameters.size() >= 255) {
                    error(peek(), "Can't have more than 255 parameters.");
                }
                parameters.add(consume(IDENTIFIER, "Expect parameter name."));
            } while (match(COMMA));
        }
        consume(RIGHT_PAREN, "Expect ')' after parameters.");

        consume(LEFT_BRACE, "Expect '{' before " + kind + " body.");
        List<Stmt> body = block();
        return new Stmt.Function(name, parameters, body);
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
        if (match(FOR)) return forStatement();
        if (match(IF)) return ifStatement();
        if (match(PRINT)) return printStatement();
        if (match(RETURN)) return returnStatement();
        if (match(WHILE)) return whileStatement();
        if (match(LEFT_BRACE)) return new Stmt.Block(block());
        return expressionStatement();
    }

    private Stmt returnStatement() {
        Token keyword = previous();
        Expr value = null;
        if (!check(SEMICOLON)) {
            value = expression();
        }

        consume(SEMICOLON, "Expect ';' after return value.");
        return new Stmt.Return(keyword, value);
    }

    /*
        here, we desugar "for" into a while.
        In our simple interpreter, desugaring really doesn't save us much work,
        but it's to introduce the technique!
     */
    private Stmt forStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'for'.");
        Stmt initializer;
        if (match(SEMICOLON)) {
            initializer = null;
        } else if (match(VAR)) {
            initializer = varDeclaration();
        } else {
            initializer = expressionStatement();
        }

        Expr condition = null;
        if (!check(SEMICOLON)) {
            condition = expression();
        }
        consume(SEMICOLON, "Expect ';' after loop condition.");

        Expr increment = null;
        if (!check(RIGHT_PAREN)) {
            increment = expression();
        }
        consume(RIGHT_PAREN, "Expect ')' after loop condition.");

        Stmt body = statement();

        // the increment if it exists, is executed after the body
        if (increment != null) {
            body = new Stmt.Block(
                    Arrays.asList(
                            body,
                            new Stmt.Expression(increment)
                    )
            );
        }

        // the condition doesn't exist, the for loop, will loop for ever;
        if (condition == null) {
            condition = new Expr.Literal(true);
        }

        body = new Stmt.While(condition, body);

        // if there's an initializer, it runs once, before the loop
        if (initializer != null) {
            body = new Stmt.Block(Arrays.asList(initializer, body));
        }


        return body;

    }

    private Stmt whileStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'while'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after 'while' condition.");
        Stmt body = statement();

        return new Stmt.While(condition, body);
    }

    /*
    here, our grammar is ambiguous.
    if (first) if (second) true(); else false();
    will be translated to
    if (first)
        if (second) true();
        else false();
    and not:
    if (first) { if (second) true(); } else false();
    which makes sense? (I think)
     */
    private Stmt ifStatement() {
        consume(LEFT_PAREN, "Expect '(' after 'if'.");
        Expr condition = expression();
        consume(RIGHT_PAREN, "Expect ')' after 'if' condition.");

        Stmt thenBranch = statement();
        Stmt elseBranch = null;
        if (match(ELSE)) {
            elseBranch = statement();
        }

        return new Stmt.If(condition, thenBranch, elseBranch);

    }

    private List<Stmt> block() {
        List<Stmt> statements = new ArrayList<>();

        while (!check(RIGHT_BRACE) && !isAtEnd()) {
            statements.add(declaration());
        }

        consume(RIGHT_BRACE, "Expect '}' after block.");

        return statements;

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

    -- UPDATE
    assignment  -> IDENTIFIER "=" assignment | logic_or ;
    logic_or    -> logic_and ( "or" logic_and )* ;
    logic_and   -> equality ( "and" equality )* ;

     */
    private Expr assignment() {

        Expr expr = or();

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

    private Expr or() {
        Expr expr = and();
        while (match(OR)) {
            Token operator = previous();
            Expr right = and();
            expr = new Expr.Logical(expr, operator, right);
        }

        return expr;
    }

    private Expr and() {
        Expr expr = equality();
        while (match(AND)) {
            Token operator = previous();
            Expr right = equality();
            expr = new Expr.Logical(expr, operator, right);
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
        return call();
    }

    /*
    call        -> primary ( "(" arguments? ")" )* ;
    arguments   -> expression ( "," expression )*;

    We need to be careful of function call returning a function that we call:
    getCallBack()();
     */
    private Expr call() {
        Expr expr = primary();

        while (true) {
            // do this to be able to do this: funcWithCallBacks()()();
            if (match(LEFT_PAREN)) {
                expr = finishCall(expr);
            } else {
                break;
            }
        }
        return expr;
    }

    private Expr finishCall(Expr callee) {
        List<Expr> arguments = new ArrayList<>();
        if (!check(RIGHT_PAREN)) {
            do {
                // this is "a soft error", it's mainly to be consistent with clox
                if (arguments.size() >= 255) {
                    // not throwing the error, because throwing = parser in confused state => panic mode
                    // here the parser isn't confused! so we just report the error
                    error(peek(), "Can't have more than 255 arguments.");
                }
                arguments.add(expression());
            } while (match(COMMA));
        }

        Token paren = consume(RIGHT_PAREN, "Expect ')' after arguments.");

        return new Expr.Call(callee, paren, arguments);
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
