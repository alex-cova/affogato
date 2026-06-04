package dev.affogato.intellij.parser;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import dev.affogato.intellij.AffogatoLanguage;
import dev.affogato.intellij.lexer.AffogatoLexerAdapter;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoTypes;
import org.jetbrains.annotations.NotNull;

public final class AffogatoParserDefinition implements ParserDefinition {
    public static final IFileElementType FILE = new IFileElementType(AffogatoLanguage.INSTANCE);
    private static final TokenSet COMMENTS = TokenSet.create(AffogatoTypes.LINE_COMMENT, AffogatoTypes.BLOCK_COMMENT);
    private static final TokenSet STRINGS = TokenSet.create(AffogatoTypes.STRING);

    @Override
    public @NotNull Lexer createLexer(Project project) {
        return new AffogatoLexerAdapter();
    }

    @Override
    public @NotNull PsiParser createParser(Project project) {
        return new AffogatoParser();
    }

    @Override
    public @NotNull IFileElementType getFileNodeType() {
        return FILE;
    }

    @Override
    public @NotNull TokenSet getCommentTokens() {
        return COMMENTS;
    }

    @Override
    public @NotNull TokenSet getStringLiteralElements() {
        return STRINGS;
    }

    @Override
    public @NotNull PsiElement createElement(ASTNode node) {
        return AffogatoTypes.Factory.createElement(node);
    }

    @Override
    public @NotNull PsiFile createFile(FileViewProvider viewProvider) {
        return new AffogatoFile(viewProvider);
    }

    @Override
    public @NotNull SpaceRequirements spaceExistenceTypeBetweenTokens(ASTNode left, ASTNode right) {
        return SpaceRequirements.MAY;
    }
}
