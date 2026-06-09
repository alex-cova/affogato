package dev.affogato.intellij.highlighting;

import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import dev.affogato.intellij.lexer.AffogatoLexerAdapter;
import dev.affogato.intellij.psi.AffogatoTypes;
import org.jetbrains.annotations.NotNull;

public final class AffogatoSyntaxHighlighter extends SyntaxHighlighterBase {
    public static final TextAttributesKey KEYWORD = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_KEYWORD",
            DefaultLanguageHighlighterColors.KEYWORD
    );
    public static final TextAttributesKey STRING = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_STRING",
            DefaultLanguageHighlighterColors.STRING
    );
    public static final TextAttributesKey NUMBER = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_NUMBER",
            DefaultLanguageHighlighterColors.NUMBER
    );
    public static final TextAttributesKey COMMENT = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_COMMENT",
            DefaultLanguageHighlighterColors.LINE_COMMENT
    );
    public static final TextAttributesKey BAD_CHARACTER = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_BAD_CHARACTER",
            HighlighterColors.BAD_CHARACTER
    );
    public static final TextAttributesKey BRACES = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_BRACES",
            DefaultLanguageHighlighterColors.BRACES
    );
    public static final TextAttributesKey PARENTHESES = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_PARENTHESES",
            DefaultLanguageHighlighterColors.PARENTHESES
    );
    public static final TextAttributesKey BRACKETS = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_BRACKETS",
            DefaultLanguageHighlighterColors.BRACKETS
    );
    public static final TextAttributesKey COMMA = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_COMMA",
            DefaultLanguageHighlighterColors.COMMA
    );
    public static final TextAttributesKey DOT = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_DOT",
            DefaultLanguageHighlighterColors.DOT
    );
    public static final TextAttributesKey SEMICOLON = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_SEMICOLON",
            DefaultLanguageHighlighterColors.SEMICOLON
    );
    public static final TextAttributesKey OPERATION_SIGN = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_OPERATION_SIGN",
            DefaultLanguageHighlighterColors.OPERATION_SIGN
    );

    // Semantic keys applied by AffogatoHighlightAnnotator.
    public static final TextAttributesKey CLASS_NAME = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_CLASS_NAME",
            DefaultLanguageHighlighterColors.CLASS_NAME
    );
    public static final TextAttributesKey TYPE_REF = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_TYPE_REF",
            DefaultLanguageHighlighterColors.CLASS_REFERENCE
    );
    public static final TextAttributesKey FUNCTION_DECL = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_FUNCTION_DECLARATION",
            DefaultLanguageHighlighterColors.FUNCTION_DECLARATION
    );
    public static final TextAttributesKey FUNCTION_CALL = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_FUNCTION_CALL",
            DefaultLanguageHighlighterColors.FUNCTION_CALL
    );
    public static final TextAttributesKey PARAMETER = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_PARAMETER",
            DefaultLanguageHighlighterColors.PARAMETER
    );
    public static final TextAttributesKey ANNOTATION = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_ANNOTATION",
            DefaultLanguageHighlighterColors.METADATA
    );
    public static final TextAttributesKey ENUM_CONST = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_ENUM_CONST",
            DefaultLanguageHighlighterColors.STATIC_FIELD
    );
    public static final TextAttributesKey INSTANCE_FIELD = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_INSTANCE_FIELD",
            DefaultLanguageHighlighterColors.INSTANCE_FIELD
    );
    public static final TextAttributesKey LOCAL_VARIABLE = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_LOCAL_VARIABLE",
            DefaultLanguageHighlighterColors.LOCAL_VARIABLE
    );
    public static final TextAttributesKey VALID_STRING_ESCAPE = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_VALID_STRING_ESCAPE",
            DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE
    );
    public static final TextAttributesKey INVALID_STRING_ESCAPE = TextAttributesKey.createTextAttributesKey(
            "AFFOGATO_INVALID_STRING_ESCAPE",
            DefaultLanguageHighlighterColors.INVALID_STRING_ESCAPE
    );

    private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{KEYWORD};
    private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{STRING};
    private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{NUMBER};
    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[]{COMMENT};
    private static final TextAttributesKey[] BAD_CHARACTER_KEYS = new TextAttributesKey[]{BAD_CHARACTER};
    private static final TextAttributesKey[] BRACES_KEYS = new TextAttributesKey[]{BRACES};
    private static final TextAttributesKey[] PARENTHESES_KEYS = new TextAttributesKey[]{PARENTHESES};
    private static final TextAttributesKey[] BRACKETS_KEYS = new TextAttributesKey[]{BRACKETS};
    private static final TextAttributesKey[] COMMA_KEYS = new TextAttributesKey[]{COMMA};
    private static final TextAttributesKey[] DOT_KEYS = new TextAttributesKey[]{DOT};
    private static final TextAttributesKey[] SEMICOLON_KEYS = new TextAttributesKey[]{SEMICOLON};
    private static final TextAttributesKey[] OPERATION_KEYS = new TextAttributesKey[]{OPERATION_SIGN};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];
    private static final TokenSet OPERATORS = TokenSet.create(
            AffogatoTypes.AMPERSAND, AffogatoTypes.AND, AffogatoTypes.ARROW, AffogatoTypes.AT,
            AffogatoTypes.BANG, AffogatoTypes.CARET, AffogatoTypes.COLON, AffogatoTypes.DOUBLE_COLON,
            AffogatoTypes.ELVIS, AffogatoTypes.EQ, AffogatoTypes.ASSIGN, AffogatoTypes.GE, AffogatoTypes.GT,
            AffogatoTypes.LE, AffogatoTypes.LT, AffogatoTypes.MINUS, AffogatoTypes.MINUS_ASSIGN,
            AffogatoTypes.MINUS_MINUS, AffogatoTypes.NE, AffogatoTypes.OR, AffogatoTypes.PERCENT,
            AffogatoTypes.PERCENT_ASSIGN, AffogatoTypes.PIPE, AffogatoTypes.PLUS, AffogatoTypes.PLUS_ASSIGN,
            AffogatoTypes.PLUS_PLUS, AffogatoTypes.QUESTION, AffogatoTypes.QUESTION_DOT, AffogatoTypes.SLASH,
            AffogatoTypes.SLASH_ASSIGN, AffogatoTypes.STAR, AffogatoTypes.STAR_ASSIGN, AffogatoTypes.TILDE
    );
    private static final TokenSet KEYWORDS = TokenSet.create(
            AffogatoTypes.AS_KEYWORD,
            AffogatoTypes.CASE_KEYWORD,
            AffogatoTypes.CATCH_KEYWORD,
            AffogatoTypes.CLASS_KEYWORD,
            AffogatoTypes.DEFAULT_KEYWORD,
            AffogatoTypes.ELSE_KEYWORD,
            AffogatoTypes.ENUM_KEYWORD,
            AffogatoTypes.FALSE_KEYWORD,
            AffogatoTypes.FINALLY_KEYWORD,
            AffogatoTypes.FOR_KEYWORD,
            AffogatoTypes.FUNC_KEYWORD,
            AffogatoTypes.GUARD_KEYWORD,
            AffogatoTypes.IF_KEYWORD,
            AffogatoTypes.IMPORT_KEYWORD,
            AffogatoTypes.IN_KEYWORD,
            AffogatoTypes.INIT_KEYWORD,
            AffogatoTypes.INTERFACE_KEYWORD,
            AffogatoTypes.IS_KEYWORD,
            AffogatoTypes.LET_KEYWORD,
            AffogatoTypes.NEW_KEYWORD,
            AffogatoTypes.NOT_KEYWORD,
            AffogatoTypes.NULL_KEYWORD,
            AffogatoTypes.OVERRIDE_KEYWORD,
            AffogatoTypes.PACKAGE_KEYWORD,
            AffogatoTypes.PRIVATE_KEYWORD,
            AffogatoTypes.PROTECTED_KEYWORD,
            AffogatoTypes.PUBLIC_KEYWORD,
            AffogatoTypes.RECORD_KEYWORD,
            AffogatoTypes.RETURN_KEYWORD,
            AffogatoTypes.STATIC_KEYWORD,
            AffogatoTypes.SUPER_KEYWORD,
            AffogatoTypes.SWITCH_KEYWORD,
            AffogatoTypes.THIS_KEYWORD,
            AffogatoTypes.THROW_KEYWORD,
            AffogatoTypes.TRUE_KEYWORD,
            AffogatoTypes.TRY_KEYWORD,
            AffogatoTypes.VAR_KEYWORD,
            AffogatoTypes.WHILE_KEYWORD
    );

    @Override
    public @NotNull Lexer getHighlightingLexer() {
        return new AffogatoLexerAdapter();
    }

    @Override
    public TextAttributesKey @NotNull [] getTokenHighlights(IElementType tokenType) {
        if (tokenType == AffogatoTypes.STRING) {
            return STRING_KEYS;
        }
        if (tokenType == AffogatoTypes.NUMBER) {
            return NUMBER_KEYS;
        }
        if (tokenType == AffogatoTypes.LINE_COMMENT || tokenType == AffogatoTypes.BLOCK_COMMENT) {
            return COMMENT_KEYS;
        }
        if (tokenType == TokenType.BAD_CHARACTER || tokenType == AffogatoTypes.BAD_CHARACTER) {
            return BAD_CHARACTER_KEYS;
        }
        if (KEYWORDS.contains(tokenType)) {
            return KEYWORD_KEYS;
        }
        if (tokenType == AffogatoTypes.LBRACE || tokenType == AffogatoTypes.RBRACE) {
            return BRACES_KEYS;
        }
        if (tokenType == AffogatoTypes.LPAREN || tokenType == AffogatoTypes.RPAREN) {
            return PARENTHESES_KEYS;
        }
        if (tokenType == AffogatoTypes.LBRACK || tokenType == AffogatoTypes.RBRACK) {
            return BRACKETS_KEYS;
        }
        if (tokenType == AffogatoTypes.COMMA) {
            return COMMA_KEYS;
        }
        if (tokenType == AffogatoTypes.DOT) {
            return DOT_KEYS;
        }
        if (tokenType == AffogatoTypes.SEMI) {
            return SEMICOLON_KEYS;
        }
        if (OPERATORS.contains(tokenType)) {
            return OPERATION_KEYS;
        }
        return EMPTY_KEYS;
    }
}
