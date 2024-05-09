package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Only a few kinds of nodes are interesting when it comes to resolving variables:
 * - block statement
 * - function declaration
 * - variable declaration
 * - variable expression
 * - assignment expressions
 */
public class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {

    private enum FunctionType {
        NONE,
        FUNCTION
    }

    private final Interpreter interpreter;
    private FunctionType currentFunction = FunctionType.NONE;

    /*
    lexical scopes nest in both the interpreter & the resolver.
    They act like a stack.
    This field keeps track of the stack of scopes.
    The scope stack is only used for local block scopes. Global scope is not tracked since
    it's more dynamic in Lox. When resolving a var, if we can't find it, we assume it must be global.

    <String: is name of var, Boolean: finished resolving that var initializer?>
     */
    private final Stack<Map<String, Boolean>> scopes = new Stack<>();

    Resolver(Interpreter interpreter) {
        this.interpreter = interpreter;
    }

    /*
     * blocks create local scope
     *
     */
    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        beginScope();
        resolve(stmt.statements);
        endScope();
        return null;
    }

    void resolve(List<Stmt> statements) {
        for (Stmt statement : statements) {
            resolve(statement);
        }
    }

    private void resolve(Stmt stmt) {
        stmt.accept(this);
    }

    private void resolve(Expr expr) {
        expr.accept(this);
    }

    private void beginScope() {
        scopes.push(new HashMap<String, Boolean>());
    }

    private void endScope() {
        scopes.pop();
    }


    /* variable declaration
    - we first declare the variable
    - then "walk/play" the initializer (here we don't execute anything)
    - define the variable

    the reason we split declare & define is to handle case like this:
    ```
    var a = 1;
    {
        var a = a;
    }
    ```
    => we will consider this to be a compile time error (because shadowing is rare,
    so init a shadowing var based on the value of the shadowed one seems unlikely to be deliberate).

    -> we start by declaring the variable a in the current scope, and set ready to false.
    -> so if in the initializer we find the variable a, set to false,
    we know that we are trying to use the shadowed 'a' to init the shadowing 'a', and we report an error.
    -> if no error, we can define 'a'

     */
    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        // we first declare
        declare(stmt.name);
        if (stmt.initializer != null) {
            resolve(stmt.initializer);
        }
        define(stmt.name);
        return null;
    }

    private void declare(Token name) {
        if (scopes.isEmpty()) return; // if we are in global scope
        Map<String, Boolean> scope = scopes.peek();
        if (scope.containsKey(name.lexeme)) { // redeclaring the same variable is probably a mistake
            Lox.error(name, "Already variable with this name in this scope.");
        }
        scope.put(name.lexeme, false);
    }

    /*
    the var is initialized and is ready to be used!
     */
    private void define(Token name) {
        if (scopes.isEmpty()) return; // if we are in global scope
        scopes.peek().put(name.lexeme, true);
    }


    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        if (!scopes.isEmpty() && scopes.peek().get(expr.name.lexeme) == Boolean.FALSE)  {
            Lox.error(expr.name, "Can't read local variable in its own initializer.");
        }

        resolveLocal(expr, expr.name);
        return null;
    }

    private void resolveLocal(Expr expr, Token name) {
        for (int i = scopes.size() -1; i >= 0; i--) {
            if (scopes.get(i).containsKey(name.lexeme)) {
                interpreter.resolve(expr, scopes.size() - 1 - i);
                return;
            }
        }
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        resolve(expr.value);
        resolveLocal(expr, expr.name);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        // declare & define function name
        declare(stmt.name);
        define(stmt.name);
        // create scope for parameters & body
        resolveFunction(stmt, FunctionType.FUNCTION);
        return null;
    }

    private void resolveFunction(Stmt.Function function, FunctionType type) {

        FunctionType enclosingFunction = currentFunction;
        currentFunction = type;

        beginScope();
        for (Token param : function.params) {
            declare(param);
            define(param);
        }
        resolve(function.body);
        endScope();

        currentFunction = enclosingFunction;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        declare(stmt.name);
        define(stmt.name);
        return null;
    }

    // ------------- we handle every place where a var is declared, read or written
    // we also need to visit methods for all other syntax tree nodes in order to recurse into their subtrees


    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        resolve(expr.left);
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        resolve(expr.callee);
        for (Expr argument : expr.arguments) {
            resolve(argument);
        }
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        resolve(expr.expression);
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        // literals are... literal so there's no variable to resolve in there.
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        resolve(expr.left);
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        resolve(stmt.condition);
        resolve(stmt.thenBranch);
        if (stmt.elseBranch != null) resolve(stmt.elseBranch);
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {

        if (currentFunction == FunctionType.NONE) { // if not in a function
            Lox.error(stmt.keyword, "Can't return from top-level code.");
        }

        if (stmt.value != null) {
            resolve(stmt.value);
        }
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        resolve(stmt.condition);
        resolve(stmt.body);
        return null;
    }

}
