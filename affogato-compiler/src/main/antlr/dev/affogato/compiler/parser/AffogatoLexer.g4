lexer grammar AffogatoLexer;

@members {
    // Tracks open brackets so newlines inside ( ) and [ ] are treated as line
    // continuations (skipped), while newlines inside { } blocks remain real
    // statement separators. The innermost open bracket decides: ( or [ suppress
    // newlines; { does not.
    private final java.util.Deque<Character> openBrackets = new java.util.ArrayDeque<>();

    private void pushBracket(char bracket) {
        openBrackets.push(bracket);
    }

    private void popBracket() {
        if (!openBrackets.isEmpty()) {
            openBrackets.pop();
        }
    }

    private boolean newlineSuppressed() {
        Character top = openBrackets.peek();
        return top != null && (top == '(' || top == '[');
    }

}

PACKAGE: 'package';
IMPORT: 'import';
STATIC: 'static';
ABSTRACT: 'abstract';
CLASS: 'class';
ENUM: 'enum';
RECORD: 'record';
INTERFACE: 'interface';
DEFAULT: 'default';
SWITCH: 'switch';
CASE: 'case';
PUBLIC: 'public';
PRIVATE: 'private';
PROTECTED: 'protected';
VAR: 'var';
LET: 'let';
INIT: 'init';
FUNC: 'func';
OVERRIDE: 'override';
GUARD: 'guard';
IF: 'if';
ELSE: 'else';
FOR: 'for';
WHILE: 'while';
TRY: 'try';
CATCH: 'catch';
FINALLY: 'finally';
IN: 'in';
RETURN: 'return';
THROW: 'throw';
ASSERT: 'assert';
BREAK: 'break';
CONTINUE: 'continue';
NOT: 'not';
IS: 'is';
AS: 'as';
NEW: 'new';
THIS: 'this';
SUPER: 'super';
TRUE: 'true';
FALSE: 'false';
NULL: 'null';

QUESTION_DOT: '?.';
ELVIS: '?:';
ARROW: '->';
DOUBLE_COLON: '::';
PIPE: '|';
OR: '||';
AND: '&&';
EQ: '==';
NE: '!=';
LE: '<=';
GE: '>=';
PLUS_ASSIGN: '+=';
MINUS_ASSIGN: '-=';
STAR_ASSIGN: '*=';
SLASH_ASSIGN: '/=';
PERCENT_ASSIGN: '%=';
ASSIGN: '=';
LT: '<';
GT: '>';
PLUS_PLUS: '++';
PLUS: '+';
MINUS_MINUS: '--';
MINUS: '-';
STAR: '*';
SLASH: '/';
PERCENT: '%';
BANG: '!';
QUESTION: '?';
AMPERSAND: '&';
CARET: '^';
TILDE: '~';
DOT: '.';
COMMA: ',';
COLON: ':';
SEMI: ';';
LPAREN: '(' { pushBracket('('); };
RPAREN: ')' { popBracket(); };
LBRACE: '{' { pushBracket('{'); };
RBRACE: '}' { popBracket(); };
LBRACK: '[' { pushBracket('['); };
RBRACK: ']' { popBracket(); };
AT: '@';

Identifier
    : JavaLetter JavaLetterOrDigit*
    ;

IntegerLiteral
    : DecimalIntegerLiteral
    | HexIntegerLiteral
    ;

FloatingPointLiteral
    : Digits DOT Digits ExponentPart? FloatTypeSuffix?
    | Digits ExponentPart FloatTypeSuffix?
    | Digits FloatTypeSuffix
    ;

StringLiteral
    : '"' (Interpolation | StringCharacter)* '"'
    ;

NL
    : '\r'? '\n' { if (newlineSuppressed()) skip(); }
    ;

WS
    : [ \t\f]+ -> skip
    ;

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

fragment DecimalIntegerLiteral
    : Digits [lL]?
    ;

fragment HexIntegerLiteral
    : '0' [xX] HexDigits [lL]?
    ;

fragment HexDigits
    : HexDigit ([0-9a-fA-F_]* HexDigit)?
    ;

fragment HexDigit
    : [0-9a-fA-F]
    ;

fragment Digits
    : [0-9] ([0-9_]* [0-9])?
    ;

fragment ExponentPart
    : [eE] [+-]? Digits
    ;

fragment FloatTypeSuffix
    : [fFdD]
    ;

fragment JavaLetter
    : [a-zA-Z$_] // ASCII
    | ~[\u0000-\u007F] // Non-ASCII (covers Unicode identifiers)
    ;

fragment JavaLetterOrDigit
    : [a-zA-Z0-9$_] // ASCII
    | ~[\u0000-\u007F] // Non-ASCII
    ;

fragment StringCharacter
    : ~["\\\r\n]
    | EscapeSequence
    ;

fragment Interpolation
    : '${' InterpolationCharacter* '}'
    ;

fragment BalancedBraces
    : '{' InterpolationCharacter* '}'
    ;

fragment InterpolationCharacter
    : EscapeSequence
    | NestedStringLiteral
    | Interpolation
    | BalancedBraces
    | ~["{}\\]
    ;

fragment NestedStringLiteral
    : '"' (Interpolation | StringCharacter)* '"'
    ;

fragment EscapeSequence
    : '\\' ([btnfr"'\\$] | 'u'+ HexDigit HexDigit HexDigit HexDigit)
    ;
