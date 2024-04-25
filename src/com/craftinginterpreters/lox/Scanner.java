package com.craftinginterpreters.lox;

// static import to avoid having to type TokenType. in front of every variant of the enum
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.craftinginterpreters.lox.TokenType.*;

public class Scanner {
    private final String source;
    private final List<Token> tokens = new ArrayList<>();
    private int start = 0; // points to first char in the lexeme being scanned
    private int current = 0; // points at char currently being considered
    private int line = 1; // source line number, "current" is on

    // KEYWORDS
    private static final Map<String, TokenType> keywords;
    static {
        keywords = new HashMap<>();
        keywords.put("and", AND);
        keywords.put("class", CLASS);
        keywords.put("else", ELSE);
        keywords.put("false", FALSE);
        keywords.put("for", FOR);
        keywords.put("fun", FUN);
        keywords.put("if", IF);
        keywords.put("nil", NIL);
        keywords.put("or", OR);
        keywords.put("print", PRINT);
        keywords.put("return", RETURN);
        keywords.put("super", SUPER);
        keywords.put("this", THIS);
        keywords.put("true", TRUE);
        keywords.put("var", VAR);
        keywords.put("while", WHILE);
    }

    Scanner(String source) {
        this.source = source;
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    List<Token> scanTokens() {
       while (!isAtEnd()) {
           start = current;
           scanToken();
       }
       tokens.add(new Token(EOF, "", null, line));
       return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            // single character
            case '(': addToken(LEFT_PAREN); break;
            case ')': addToken(RIGHT_PAREN); break;
            case '{': addToken(LEFT_BRACE); break;
            case '}': addToken(RIGHT_BRACE); break;
            case ',': addToken(COMMA); break;
            case '.': addToken(DOT); break;
            case '-': addToken(MINUS); break;
            case '+': addToken(PLUS); break;
            case ';': addToken(SEMICOLON); break;
            case '*': addToken(STAR); break;

            // ignoring whitespace
            case ' ':
            case '\r':
            case '\t':
                break;

            // new line
            case '\n':
                line++;
                break;

            // one or two character
            case '!':
                addToken(match('=') ? BANG_EQUAL : BANG);
                break;
            case '=':
                addToken(match('=') ? EQUAL_EQUAL : EQUAL);
                break;
            case '<':
                addToken(match('=') ? LESS_EQUAL : LESS);
                break;
            case '>':
                addToken(match('=') ? GREATER_EQUAL : GREATER);
                break;
            case '/':
                if (match('/')) { // if a comment, we ignore the rest of the line
                    while (peek() != '\n' && !isAtEnd()) {
                        advance(); // can't use advance() directly in while, because we don't necessary want to consume
                        // the character
                    }
                } else if (match('*')) { // /* comments */
                    while (peek() != '*' && peekNext() != '/' && !isAtEnd()) {
                        if (peek() == '\n') line++;
                        advance();
                    }
                    if (isAtEnd()) {
                        Lox.error(line, "Unclosed comment");
                        break;
                    }
                    // remove "*/"
                    advance(); advance();

                }
                else {
                    addToken(SLASH);
                }
                break;

            // Literals

            case '"':
                string();
                break;

            default:
                // number literals
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    // we begin by assuming any lexeme starting with letter or underscore is an:
                    identifier();
                } else {
                    Lox.error(line, "Unexpected character.");
                }
            break;
        }
    }

    /**
     * consumes and returns the next character in the source code.
     * @return the next character
     */
    private char advance() {
        current++;
        return source.charAt(current-1);
    }

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String text = source.substring(start, current);
        tokens.add(new Token(type, text, literal, line));
    }

    /**
     * Check if the current char matches with "expected".
     * Character is consumed if true! (current ++)
     *
     * @param expected expected character
     * @return true if expected == source[current]
     */
    private boolean match(char expected) {
        if (isAtEnd()) return false;
        if (source.charAt(current) != expected) return false;

        current++;
        return true;
    }


    private char peek() {
        if (isAtEnd())
            return '\0';
        return source.charAt(current);
    }


    private void string() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') line++;
            advance();
        }

        if (isAtEnd()) {
            Lox.error(line, "Unterminated string.");
            return;
        }

        // the closing '"'
        advance();

        // trim the surrounding quotes
        String value = source.substring(start + 1, current -1);
        addToken(STRING, value);
    }


    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
                c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void number() {
        while (isDigit(peek())) advance();

        if (peek() == '.' && isDigit(peekNext())) {
            do advance();
            while (isDigit(peek()));
        }

        addToken(NUMBER, Double.parseDouble(source.substring(start, current)));
    }


    private char peekNext() {
        if (current + 1 >= source.length()) return '\0';
        return source.charAt(current+1);
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();

        String text = source.substring(start, current);

        // check if identifier is reserved keyword
        TokenType type = keywords.get(text);
        if (type == null) type = IDENTIFIER;

        addToken(type);
    }











}
