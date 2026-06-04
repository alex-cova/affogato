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

    private static final TextAttributesKey[] KEYWORD_KEYS = new TextAttributesKey[]{KEYWORD};
    private static final TextAttributesKey[] STRING_KEYS = new TextAttributesKey[]{STRING};
    private static final TextAttributesKey[] NUMBER_KEYS = new TextAttributesKey[]{NUMBER};
    private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[]{COMMENT};
    private static final TextAttributesKey[] BAD_CHARACTER_KEYS = new TextAttributesKey[]{BAD_CHARACTER};
    private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];
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
        return EMPTY_KEYS;
    }
}
