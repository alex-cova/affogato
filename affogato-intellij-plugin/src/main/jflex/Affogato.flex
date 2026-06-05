package dev.affogato.intellij.lexer;

%%

%class _AffogatoLexer
%public
%implements com.intellij.lexer.FlexLexer
%function advance
%type com.intellij.psi.tree.IElementType

WHITE_SPACE=[\ \n\r\t\f]+
ID=[A-Za-z$_][A-Za-z0-9$_]*
NUMBER=(0[xX][0-9a-fA-F_]+[lL]?)|([0-9]+(\.[0-9]+)?[fFdDlL]?)
STRING_ESCAPE=\\.
STRING_INNER=\"([^\"\\]|{STRING_ESCAPE})*\"
STRING_INNER_NESTED=\"([^\"\\]|{STRING_ESCAPE}|{STRING_INNER})*\"
INTERP_PART=([^{}\"\\]|{STRING_ESCAPE}|{STRING_INNER_NESTED})
INTERP=\$\{{INTERP_PART}*(\{{INTERP_PART}*\}{INTERP_PART}*)*\}
STRING=\"([^\"\\$]|{STRING_ESCAPE}|\$[^{]|{INTERP})*\"
LINE_COMMENT="//".*
BLOCK_COMMENT="/"\*([^*]|\*+[^*/])*\*+"/"

%%

{WHITE_SPACE}      { return com.intellij.psi.TokenType.WHITE_SPACE; }
{LINE_COMMENT}     { return dev.affogato.intellij.psi.AffogatoTypes.LINE_COMMENT; }
{BLOCK_COMMENT}    { return dev.affogato.intellij.psi.AffogatoTypes.BLOCK_COMMENT; }
{STRING}           { return dev.affogato.intellij.psi.AffogatoTypes.STRING; }
{NUMBER}           { return dev.affogato.intellij.psi.AffogatoTypes.NUMBER; }

"package"          { return dev.affogato.intellij.psi.AffogatoTypes.PACKAGE_KEYWORD; }
"import"           { return dev.affogato.intellij.psi.AffogatoTypes.IMPORT_KEYWORD; }
"static"           { return dev.affogato.intellij.psi.AffogatoTypes.STATIC_KEYWORD; }
"public"           { return dev.affogato.intellij.psi.AffogatoTypes.PUBLIC_KEYWORD; }
"private"          { return dev.affogato.intellij.psi.AffogatoTypes.PRIVATE_KEYWORD; }
"protected"        { return dev.affogato.intellij.psi.AffogatoTypes.PROTECTED_KEYWORD; }
"abstract"         { return dev.affogato.intellij.psi.AffogatoTypes.ABSTRACT_KEYWORD; }
"class"            { return dev.affogato.intellij.psi.AffogatoTypes.CLASS_KEYWORD; }
"record"           { return dev.affogato.intellij.psi.AffogatoTypes.RECORD_KEYWORD; }
"enum"             { return dev.affogato.intellij.psi.AffogatoTypes.ENUM_KEYWORD; }
"interface"        { return dev.affogato.intellij.psi.AffogatoTypes.INTERFACE_KEYWORD; }
"default"          { return dev.affogato.intellij.psi.AffogatoTypes.DEFAULT_KEYWORD; }
"init"             { return dev.affogato.intellij.psi.AffogatoTypes.INIT_KEYWORD; }
"func"             { return dev.affogato.intellij.psi.AffogatoTypes.FUNC_KEYWORD; }
"guard"            { return dev.affogato.intellij.psi.AffogatoTypes.GUARD_KEYWORD; }
"if"               { return dev.affogato.intellij.psi.AffogatoTypes.IF_KEYWORD; }
"else"             { return dev.affogato.intellij.psi.AffogatoTypes.ELSE_KEYWORD; }
"for"              { return dev.affogato.intellij.psi.AffogatoTypes.FOR_KEYWORD; }
"while"            { return dev.affogato.intellij.psi.AffogatoTypes.WHILE_KEYWORD; }
"try"              { return dev.affogato.intellij.psi.AffogatoTypes.TRY_KEYWORD; }
"catch"            { return dev.affogato.intellij.psi.AffogatoTypes.CATCH_KEYWORD; }
"finally"          { return dev.affogato.intellij.psi.AffogatoTypes.FINALLY_KEYWORD; }
"switch"           { return dev.affogato.intellij.psi.AffogatoTypes.SWITCH_KEYWORD; }
"break"            { return dev.affogato.intellij.psi.AffogatoTypes.BREAK_KEYWORD; }
"continue"         { return dev.affogato.intellij.psi.AffogatoTypes.CONTINUE_KEYWORD; }
"case"             { return dev.affogato.intellij.psi.AffogatoTypes.CASE_KEYWORD; }
"return"           { return dev.affogato.intellij.psi.AffogatoTypes.RETURN_KEYWORD; }
"throw"            { return dev.affogato.intellij.psi.AffogatoTypes.THROW_KEYWORD; }
"in"               { return dev.affogato.intellij.psi.AffogatoTypes.IN_KEYWORD; }
"assert"           { return dev.affogato.intellij.psi.AffogatoTypes.ASSERT_KEYWORD; }
"var"              { return dev.affogato.intellij.psi.AffogatoTypes.VAR_KEYWORD; }
"let"              { return dev.affogato.intellij.psi.AffogatoTypes.LET_KEYWORD; }
"override"         { return dev.affogato.intellij.psi.AffogatoTypes.OVERRIDE_KEYWORD; }
"not"              { return dev.affogato.intellij.psi.AffogatoTypes.NOT_KEYWORD; }
"is"               { return dev.affogato.intellij.psi.AffogatoTypes.IS_KEYWORD; }
"as"               { return dev.affogato.intellij.psi.AffogatoTypes.AS_KEYWORD; }
"new"              { return dev.affogato.intellij.psi.AffogatoTypes.NEW_KEYWORD; }
"this"             { return dev.affogato.intellij.psi.AffogatoTypes.THIS_KEYWORD; }
"super"            { return dev.affogato.intellij.psi.AffogatoTypes.SUPER_KEYWORD; }
"true"             { return dev.affogato.intellij.psi.AffogatoTypes.TRUE_KEYWORD; }
"false"            { return dev.affogato.intellij.psi.AffogatoTypes.FALSE_KEYWORD; }
"null"             { return dev.affogato.intellij.psi.AffogatoTypes.NULL_KEYWORD; }
"->"               { return dev.affogato.intellij.psi.AffogatoTypes.ARROW; }
"::"               { return dev.affogato.intellij.psi.AffogatoTypes.DOUBLE_COLON; }
"?."               { return dev.affogato.intellij.psi.AffogatoTypes.QUESTION_DOT; }
"?:"               { return dev.affogato.intellij.psi.AffogatoTypes.ELVIS; }
"||"               { return dev.affogato.intellij.psi.AffogatoTypes.OR; }
"&&"               { return dev.affogato.intellij.psi.AffogatoTypes.AND; }
"=="               { return dev.affogato.intellij.psi.AffogatoTypes.EQ; }
"!="               { return dev.affogato.intellij.psi.AffogatoTypes.NE; }
"<="               { return dev.affogato.intellij.psi.AffogatoTypes.LE; }
">="               { return dev.affogato.intellij.psi.AffogatoTypes.GE; }
"++"               { return dev.affogato.intellij.psi.AffogatoTypes.PLUS_PLUS; }
"--"               { return dev.affogato.intellij.psi.AffogatoTypes.MINUS_MINUS; }
"+="               { return dev.affogato.intellij.psi.AffogatoTypes.PLUS_ASSIGN; }
"-="               { return dev.affogato.intellij.psi.AffogatoTypes.MINUS_ASSIGN; }
"*="               { return dev.affogato.intellij.psi.AffogatoTypes.STAR_ASSIGN; }
"/="               { return dev.affogato.intellij.psi.AffogatoTypes.SLASH_ASSIGN; }
"%="               { return dev.affogato.intellij.psi.AffogatoTypes.PERCENT_ASSIGN; }
"("                { return dev.affogato.intellij.psi.AffogatoTypes.LPAREN; }
")"                { return dev.affogato.intellij.psi.AffogatoTypes.RPAREN; }
"{"                { return dev.affogato.intellij.psi.AffogatoTypes.LBRACE; }
"}"                { return dev.affogato.intellij.psi.AffogatoTypes.RBRACE; }
"["                { return dev.affogato.intellij.psi.AffogatoTypes.LBRACK; }
"]"                { return dev.affogato.intellij.psi.AffogatoTypes.RBRACK; }
":"                { return dev.affogato.intellij.psi.AffogatoTypes.COLON; }
";"                { return dev.affogato.intellij.psi.AffogatoTypes.SEMI; }
","                { return dev.affogato.intellij.psi.AffogatoTypes.COMMA; }
"."                { return dev.affogato.intellij.psi.AffogatoTypes.DOT; }
"="                { return dev.affogato.intellij.psi.AffogatoTypes.ASSIGN; }
"?"                { return dev.affogato.intellij.psi.AffogatoTypes.QUESTION; }
"!"                { return dev.affogato.intellij.psi.AffogatoTypes.BANG; }
"<"                { return dev.affogato.intellij.psi.AffogatoTypes.LT; }
">"                { return dev.affogato.intellij.psi.AffogatoTypes.GT; }
"+"                { return dev.affogato.intellij.psi.AffogatoTypes.PLUS; }
"-"                { return dev.affogato.intellij.psi.AffogatoTypes.MINUS; }
"*"                { return dev.affogato.intellij.psi.AffogatoTypes.STAR; }
"/"                { return dev.affogato.intellij.psi.AffogatoTypes.SLASH; }
"%"                { return dev.affogato.intellij.psi.AffogatoTypes.PERCENT; }
"|"                { return dev.affogato.intellij.psi.AffogatoTypes.PIPE; }
"^"                { return dev.affogato.intellij.psi.AffogatoTypes.CARET; }
"~"                { return dev.affogato.intellij.psi.AffogatoTypes.TILDE; }
"&"                { return dev.affogato.intellij.psi.AffogatoTypes.AMPERSAND; }
"@"                { return dev.affogato.intellij.psi.AffogatoTypes.AT; }

{ID}               { return dev.affogato.intellij.psi.AffogatoTypes.ID; }
.                  { return com.intellij.psi.TokenType.BAD_CHARACTER; }
