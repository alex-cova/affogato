grammar Affogato;

compilationUnit
    : separators? packageDecl? importDecl* typeDecl* EOF
    ;

packageDecl
    : PACKAGE qualifiedName terminators
    ;

importDecl
    : IMPORT STATIC? qualifiedName (DOT STAR)? terminators
    ;

typeDecl
    : classDecl separators?
    | enumDecl separators?
    | interfaceDecl separators?
    | recordDecl separators?
    | extensionFuncDecl separators?
    ;

extensionFuncDecl
    : annotation* memberModifier* FUNC extensionReceiverType DOT Identifier LPAREN parameterList? RPAREN (COLON typeRef)? block
    ;

extensionReceiverType
    : Identifier typeArguments? arraySuffix* nullability?
    ;

annotation
    : AT qualifiedName (LPAREN annotationArgs? RPAREN)? separators?
    ;

annotationArgs
    : annotationArg (COMMA annotationArg)*
    ;

annotationArg
    : Identifier ASSIGN expression
    | expression
    ;

classDecl
    : annotation* classModifier* CLASS Identifier compactConstructor? extendsClause? classBody
    ;

classModifier
    : PUBLIC
    | PRIVATE
    | PROTECTED
    ;

compactConstructor
    : LPAREN parameterList? RPAREN
    ;

extendsClause
    : COLON typeRef (COMMA typeRef)*
    ;

classBody
    : LBRACE separators? classMember* RBRACE
    ;

classMember
    : separators
    | fieldDecl terminators
    | constructorDecl separators?
    | methodDecl separators?
    ;

fieldDecl
    : annotation* memberModifier* variableKind Identifier (COLON typeRef)? (ASSIGN expression)?
    ;

constructorDecl
    : annotation* memberModifier* INIT LPAREN parameterList? RPAREN block
    ;

methodDecl
    : annotation* memberModifier* methodSignature block
    ;

methodSignature
    : FUNC Identifier LPAREN parameterList? RPAREN
    | typeRef Identifier LPAREN parameterList? RPAREN
    | Identifier LPAREN parameterList? RPAREN COLON typeRef
    ;

memberModifier
    : PUBLIC
    | PRIVATE
    | PROTECTED
    | STATIC
    | OVERRIDE
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : variableKind? Identifier COLON typeRef
    | typeRef Identifier
    ;

variableKind
    : VAR
    | LET
    ;

typeRef
    : qualifiedName typeArguments? arraySuffix* nullability?
    | LBRACK typeRef RBRACK nullability?
    ;

typeArguments
    : LT typeRef (COMMA typeRef)* GT
    ;

arraySuffix
    : LBRACK RBRACK
    ;

nullability
    : QUESTION
    | BANG
    ;

qualifiedName
    : Identifier (DOT Identifier)*
    ;

block
    : LBRACE separators? statement* RBRACE
    ;

statement
    : separators
    | block separators?
    | guardStatement
    | ifStatement
    | forStatement
    | whileStatement
    | tryStatement separators?
    | switchStatement separators?
    | returnStatement terminators?
    | throwStatement terminators?
    | localVarDecl terminators
    | expressionStatement terminators
    ;

guardStatement
    : GUARD condition ELSE block separators?
    ;

ifStatement
    : IF condition block (ELSE (ifStatement | block))? separators?
    ;

forStatement
    : FOR forCondition block separators?
    ;

whileStatement
    : WHILE condition block separators?
    ;

tryStatement
    : TRY block catchClause* finallyClause?
    ;

catchClause
    : CATCH LPAREN catchType Identifier RPAREN block separators?
    ;

catchType
    : typeRef (PIPE typeRef)*
    ;

finallyClause
    : FINALLY block separators?
    ;

recordDecl
    : annotation* classModifier* RECORD Identifier recordHeader implementsClause? classBody
    ;

recordHeader
    : LPAREN parameterList? RPAREN
    ;

implementsClause
    : COLON typeRef (COMMA typeRef)*
    ;

enumDecl
    : annotation* classModifier* ENUM Identifier enumBody
    ;

enumBody
    : LBRACE separators? (enumConstant (COMMA separators? enumConstant)* separators?)? RBRACE
    ;

enumConstant
    : Identifier
    ;

interfaceDecl
    : annotation* classModifier* INTERFACE Identifier interfaceBody
    ;

interfaceBody
    : LBRACE separators? interfaceMember* RBRACE
    ;

interfaceMember
    : separators
    | DEFAULT methodSignature block separators?
    | methodSignature terminators
    ;

switchStatement
    : SWITCH condition switchBody
    ;

switchExpression
    : SWITCH condition switchBody
    ;

switchBody
    : LBRACE separators? switchArm* RBRACE
    ;

switchArm
    : CASE switchLabel (COMMA switchLabel)* ARROW switchArmBody
    | DEFAULT ARROW switchArmBody
    ;

switchLabel
    : expression
    ;

switchArmBody
    : expression terminators
    | block separators?
    ;

returnStatement
    : RETURN switchExpression
    | RETURN expression trailingClosure
    | RETURN expression?
    ;

throwStatement
    : THROW expression
    ;

localVarDecl
    : variableKind Identifier (COLON typeRef)? ASSIGN switchExpression
    | variableKind Identifier (COLON typeRef)? ASSIGN expression trailingClosure
    | variableKind Identifier (COLON typeRef)? (ASSIGN expression)?
    ;

expressionStatement
    : expression trailingClosure?
    ;

trailingClosure
    : LBRACE (lambdaParameters ARROW)? closureBody RBRACE
    ;

closureBody
    : lambdaBody
    | separators? statement*
    ;

condition
    : LPAREN expression RPAREN
    | expression
    ;

forCondition
    : LPAREN forContent RPAREN
    | forContent
    ;

forContent
    : variableKind? Identifier IN expression
    | expression
    ;

expression
    : lambdaExpression
    ;

lambdaExpression
    : lambdaParameters ARROW lambdaBody
    | methodReferenceExpression
    | assignmentExpression
    ;

methodReferenceExpression
    : qualifiedName DOUBLE_COLON Identifier
    ;

lambdaParameters
    : Identifier
    | LPAREN lambdaParameterList? RPAREN
    ;

lambdaParameterList
    : lambdaParameter (COMMA lambdaParameter)*
    ;

lambdaParameter
    : Identifier (COLON typeRef)?
    ;

lambdaBody
    : expression
    | block
    ;

assignmentExpression
    : ternaryExpression ((ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN) assignmentExpression)?
    ;

ternaryExpression
    : logicalOrExpression (QUESTION expression COLON expression)?
    ;

logicalOrExpression
    : logicalAndExpression (OR logicalAndExpression)*
    ;

logicalAndExpression
    : equalityExpression (AND equalityExpression)*
    ;

equalityExpression
    : relationalExpression ((EQ | NE) relationalExpression)*
    ;

relationalExpression
    : castExpression ((LT | LE | GT | GE) castExpression | IS typeRef)*
    ;

castExpression
    : additiveExpression (AS typeRef)?
    ;

additiveExpression
    : multiplicativeExpression ((PLUS | MINUS) multiplicativeExpression)*
    ;

multiplicativeExpression
    : unaryExpression ((STAR | SLASH | PERCENT) unaryExpression)*
    ;

unaryExpression
    : NOT LPAREN expression RPAREN
    | BANG unaryExpression
    | MINUS unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression postfixPart*
    ;

postfixPart
    : DOT Identifier
    | LPAREN argumentList? RPAREN
    ;

primaryExpression
    : literal
    | genericConstructorExpression
    | NEW typeRef LPAREN argumentList? RPAREN
    | THIS
    | SUPER
    | arrayLiteral
    | Identifier
    | LPAREN expression RPAREN
    ;

arrayLiteral
    : LBRACK (expression (COMMA expression)*)? RBRACK
    ;

genericConstructorExpression
    : qualifiedName typeArguments LPAREN argumentList? RPAREN
    ;

argumentList
    : argument (COMMA argument)*
    ;

argument
    : Identifier ASSIGN expression
    | expression
    ;

literal
    : StringLiteral
    | IntegerLiteral
    | FloatingPointLiteral
    | TRUE
    | FALSE
    | NULL
    ;

terminators
    : (SEMI | NL)+
    ;

separators
    : (SEMI | NL)+
    ;

PACKAGE: 'package';
IMPORT: 'import';
STATIC: 'static';
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
NOT: 'not';
IS: 'is';
AS: 'as';
NEW: 'new';
THIS: 'this';
SUPER: 'super';
TRUE: 'true';
FALSE: 'false';
NULL: 'null';

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
ASSIGN: '=';
LT: '<';
GT: '>';
PLUS: '+';
MINUS: '-';
STAR: '*';
SLASH: '/';
PERCENT: '%';
BANG: '!';
QUESTION: '?';
DOT: '.';
COMMA: ',';
COLON: ':';
SEMI: ';';
LPAREN: '(';
RPAREN: ')';
LBRACE: '{';
RBRACE: '}';
LBRACK: '[';
RBRACK: ']';
AT: '@';

Identifier
    : JavaLetter JavaLetterOrDigit*
    ;

IntegerLiteral
    : DecimalIntegerLiteral
    ;

FloatingPointLiteral
    : Digits DOT Digits ExponentPart? FloatTypeSuffix?
    | Digits ExponentPart FloatTypeSuffix?
    | Digits FloatTypeSuffix
    ;

StringLiteral
    : '"' StringCharacter* '"'
    ;

NL
    : '\r'? '\n'
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

fragment StringCharacter
    : ~["\\\r\n]
    | EscapeSequence
    ;

fragment EscapeSequence
    : '\\' [btnfr"'\\]
    ;

fragment DecimalIntegerLiteral
    : Digits [lL]?
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
    : [a-zA-Z$_]
    ;

fragment JavaLetterOrDigit
    : [a-zA-Z0-9$_]
    ;
