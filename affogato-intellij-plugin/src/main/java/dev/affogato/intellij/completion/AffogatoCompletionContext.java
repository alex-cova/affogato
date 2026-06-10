package dev.affogato.intellij.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.psi.PsiErrorElement;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.CatchType;
import dev.affogato.intellij.psi.ExtendsClause;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.ImplementsClause;
import dev.affogato.intellij.psi.AffogatoTypes;
import dev.affogato.intellij.psi.ImportDecl;
import dev.affogato.intellij.psi.TypeRef;
import org.jetbrains.annotations.NotNull;

public final class AffogatoCompletionContext {
    public enum Kind {
        IMPORT,
        TYPE,
        MEMBER,
        EXPRESSION,
        DECLARATION_NAME
    }

    private final @NotNull Kind kind;
    private final @NotNull PsiElement position;
    private final char previousChar;
    private final char nextChar;
    private final boolean afterNew;

    private AffogatoCompletionContext(
            @NotNull Kind kind,
            @NotNull PsiElement position,
            char previousChar,
            char nextChar,
            boolean afterNew
    ) {
        this.kind = kind;
        this.position = position;
        this.previousChar = previousChar;
        this.nextChar = nextChar;
        this.afterNew = afterNew;
    }

    public static @NotNull AffogatoCompletionContext at(@NotNull CompletionParameters parameters) {
        PsiElement position = parameters.getPosition();
        char previous = AffogatoTextUtil.previousNonWhitespaceChar(position);
        char next = AffogatoTextUtil.nextNonWhitespaceChar(position);
        boolean afterNew = AffogatoTextUtil.isAfterNew(position);
        Kind kind = classify(position, previous);
        return new AffogatoCompletionContext(kind, position, previous, next, afterNew);
    }

    public @NotNull Kind kind() {
        return kind;
    }

    public @NotNull PsiElement position() {
        return position;
    }

    public char previousChar() {
        return previousChar;
    }

    public char nextChar() {
        return nextChar;
    }

    public boolean afterNew() {
        return afterNew;
    }

    public boolean expectsCall() {
        return nextChar == '(' || previousChar == '(';
    }

    public boolean expectsType() {
        return kind == Kind.TYPE || afterNew || AffogatoSymbols.isTypeReferenceIdentifier(position);
    }

    public static boolean isErrorTreeContext(@NotNull PsiElement position, int offset) {
        if (position instanceof PsiErrorElement) {
            return true;
        }
        for (PsiElement element = position; element != null; element = element.getParent()) {
            if (element instanceof PsiErrorElement error) {
                TextRange range = error.getTextRange();
                if (range.getStartOffset() <= offset && offset <= range.getEndOffset() + 24) {
                    return true;
                }
            }
            if (element instanceof AffogatoFile) {
                break;
            }
        }
        return false;
    }

    private static @NotNull Kind classify(@NotNull PsiElement position, char previous) {
        if (PsiTreeUtil.getParentOfType(position, ImportDecl.class) != null
                || isImportDot(position)) {
            return Kind.IMPORT;
        }
        if (isMemberAccess(position, previous)) {
            return Kind.MEMBER;
        }
        if (isTypePosition(position)) {
            return Kind.TYPE;
        }
        if (isDeclarationNamePosition(position)) {
            return Kind.DECLARATION_NAME;
        }
        return Kind.EXPRESSION;
    }

    private static boolean isTypePosition(@NotNull PsiElement position) {
        return PsiTreeUtil.getParentOfType(position, TypeRef.class) != null
                || PsiTreeUtil.getParentOfType(position, ExtendsClause.class) != null
                || PsiTreeUtil.getParentOfType(position, ImplementsClause.class) != null
                || PsiTreeUtil.getParentOfType(position, CatchType.class) != null;
    }

    private static boolean isMemberAccess(@NotNull PsiElement position, char previous) {
        if (PsiTreeUtil.getParentOfType(position, ImportDecl.class) != null) {
            return false;
        }
        return AffogatoTextUtil.isMemberAccessPosition(position)
                || previous == '.' || previous == '?';
    }

    private static boolean isImportDot(@NotNull PsiElement position) {
        return position.getNode() != null
                && position.getNode().getElementType() == AffogatoTypes.DOT
                && PsiTreeUtil.getParentOfType(position, ImportDecl.class) != null;
    }

    private static boolean isDeclarationNamePosition(@NotNull PsiElement position) {
        if (position instanceof Identifier identifier && AffogatoPsiUtil.isDeclarationIdentifier(identifier)) {
            return true;
        }
        Identifier identifier = PsiTreeUtil.getParentOfType(position, Identifier.class, false);
        return identifier != null && AffogatoPsiUtil.isDeclarationIdentifier(identifier);
    }
}
