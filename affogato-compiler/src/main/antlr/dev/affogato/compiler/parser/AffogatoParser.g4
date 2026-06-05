parser grammar AffogatoParser;

options {
    tokenVocab = AffogatoLexer;
}

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
    | Identifier ASSIGN annotationArray
    | annotationArray
    | expression
    ;

annotationArray
    : LBRACE (annotationArg (COMMA annotationArg)*)? RBRACE
    ;

classDecl
    : annotation* classModifier* CLASS Identifier typeParamList? compactConstructor? extendsClause? classBody
    ;

classModifier
    : PUBLIC
    | PRIVATE
    | PROTECTED
    | ABSTRACT
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
    : annotation* memberModifier* methodSignature (block | terminators)
    ;

methodSignature
    : FUNC Identifier typeParamList? LPAREN parameterList? RPAREN
    | typeRef Identifier typeParamList? LPAREN parameterList? RPAREN
    | Identifier typeParamList? LPAREN parameterList? RPAREN COLON typeRef
    ;

memberModifier
    : PUBLIC
    | PRIVATE
    | PROTECTED
    | STATIC
    | OVERRIDE
    | ABSTRACT
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : annotation* variableKind? Identifier COLON typeRef
    | annotation* typeRef Identifier
    ;

variableKind
    : VAR
    | LET
    ;

typeRef
    : qualifiedName typeArguments? nullability? arraySuffix* nullability?
    | LBRACK typeRef RBRACK nullability?
    ;

typeArguments
    : LT typeRef (COMMA typeRef)* GT
    ;

typeParamList
    : LT typeParam (COMMA typeParam)* GT
    ;

typeParam
    : Identifier (COLON typeRef)?
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
    | assertStatement terminators?
    | breakStatement terminators?
    | continueStatement terminators?
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
    : annotation* classModifier* RECORD Identifier typeParamList? recordHeader implementsClause? classBody
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
    : annotation* classModifier* INTERFACE Identifier typeParamList? interfaceBody
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

assertStatement
    : ASSERT expression (COLON expression)?
    ;

breakStatement
    : BREAK
    ;

continueStatement
    : CONTINUE
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
    : qualifiedName DOUBLE_COLON (Identifier | NEW)
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
    : ternaryExpression ((ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN) assignmentExpression)?
    ;

ternaryExpression
    : switchExpression
    | logicalOrExpression (QUESTION expression COLON expression)?
    ;

logicalOrExpression
    : logicalAndExpression (OR logicalAndExpression)*
    ;

logicalAndExpression
    : bitwiseOrExpression (AND bitwiseOrExpression)*
    ;

bitwiseOrExpression
    : bitwiseXorExpression (PIPE bitwiseXorExpression)*
    ;

bitwiseXorExpression
    : bitwiseAndExpression (CARET bitwiseAndExpression)*
    ;

bitwiseAndExpression
    : equalityExpression (AMPERSAND equalityExpression)*
    ;

equalityExpression
    : relationalExpression ((EQ | NE) relationalExpression)*
    ;

relationalExpression
    : shiftExpression ((LT | LE | GT | GE) shiftExpression | IS typeRef)*
    ;

shiftExpression
    : castExpression (shiftOp castExpression)*
    ;

shiftOp
    : LT LT
    | GT GT GT
    | GT GT
    ;

castExpression
    : additiveExpression (AS typeRef)*
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
    | TILDE unaryExpression
    | PLUS_PLUS unaryExpression
    | MINUS_MINUS unaryExpression
    | postfixExpression
    ;

postfixExpression
    : primaryExpression postfixPart*
    ;

postfixPart
    : DOT (Identifier | IN)
    | LPAREN argumentList? RPAREN
    | LBRACK expression RBRACK
    | PLUS_PLUS
    | MINUS_MINUS
    ;

primaryExpression
    : literal
    | typeRef DOT CLASS
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
