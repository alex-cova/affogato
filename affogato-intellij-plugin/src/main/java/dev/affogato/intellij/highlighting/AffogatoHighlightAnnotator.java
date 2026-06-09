package dev.affogato.intellij.highlighting;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.psi.AffogatoPsiUtil;
import dev.affogato.intellij.psi.AffogatoTypes;
import dev.affogato.intellij.psi.Annotation;
import dev.affogato.intellij.psi.CatchClause;
import dev.affogato.intellij.psi.ClassDecl;
import dev.affogato.intellij.psi.EnumConstant;
import dev.affogato.intellij.psi.EnumDecl;
import dev.affogato.intellij.psi.ExtensionFuncDecl;
import dev.affogato.intellij.psi.ForContent;
import dev.affogato.intellij.psi.Identifier;
import dev.affogato.intellij.psi.ImportDecl;
import dev.affogato.intellij.psi.InterfaceDecl;
import dev.affogato.intellij.psi.LambdaExpression;
import dev.affogato.intellij.psi.LocalVarDecl;
import dev.affogato.intellij.psi.MethodSignature;
import dev.affogato.intellij.psi.PackageDecl;
import dev.affogato.intellij.psi.Parameter;
import dev.affogato.intellij.psi.QualifiedName;
import dev.affogato.intellij.psi.RecordDecl;
import dev.affogato.intellij.psi.TrailingClosure;
import dev.affogato.intellij.psi.TypeRef;
import org.jetbrains.annotations.NotNull;

/**
 * Adds semantic colors on top of the lexer-based {@link AffogatoSyntaxHighlighter}: declarations
 * (class / type / function / parameter / enum-constant / annotation names), local-variable binders,
 * usage sites (function & constructor calls, member-field access), and string escape sequences all
 * get distinct, user-configurable colors. Everything here is syntactic — no project-wide reference
 * resolution is performed, since the annotator runs on every edit.
 */
public final class AffogatoHighlightAnnotator implements Annotator {
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof ClassDecl decl) {
            colorize(holder, decl.getIdentifier(), AffogatoSyntaxHighlighter.CLASS_NAME);
        } else if (element instanceof RecordDecl decl) {
            colorize(holder, decl.getIdentifier(), AffogatoSyntaxHighlighter.CLASS_NAME);
        } else if (element instanceof EnumDecl decl) {
            colorize(holder, decl.getIdentifier(), AffogatoSyntaxHighlighter.CLASS_NAME);
        } else if (element instanceof InterfaceDecl decl) {
            colorize(holder, decl.getIdentifier(), AffogatoSyntaxHighlighter.CLASS_NAME);
        } else if (element instanceof MethodSignature signature) {
            colorize(holder, signature.getIdentifier(), AffogatoSyntaxHighlighter.FUNCTION_DECL);
        } else if (element instanceof ExtensionFuncDecl decl) {
            colorize(holder, decl.getIdentifier(), AffogatoSyntaxHighlighter.FUNCTION_DECL);
        } else if (element instanceof Parameter parameter) {
            colorize(holder, parameter.getIdentifier(), AffogatoSyntaxHighlighter.PARAMETER);
        } else if (element instanceof EnumConstant constant) {
            colorize(holder, constant.getIdentifier(), AffogatoSyntaxHighlighter.ENUM_CONST);
        } else if (element instanceof Annotation annotation) {
            colorize(holder, annotation, AffogatoSyntaxHighlighter.ANNOTATION);
        } else if (element instanceof TypeRef typeRef) {
            QualifiedName qualifiedName = typeRef.getQualifiedName();
            if (qualifiedName != null) {
                colorize(holder, qualifiedName, AffogatoSyntaxHighlighter.TYPE_REF);
            }
        } else if (element instanceof Identifier identifier) {
            annotateIdentifier(identifier, holder);
        } else if (isStringToken(element)) {
            annotateStringEscapes(element, holder);
        }
    }

    /** Colors local-variable binders and reference usages. Declaration names are handled above. */
    private static void annotateIdentifier(Identifier id, AnnotationHolder holder) {
        if (AffogatoPsiUtil.isDeclarationIdentifier(id)) {
            return; // class / record / enum / interface / field / method / parameter name
        }
        PsiElement parent = id.getParent();
        if (parent instanceof LocalVarDecl decl && decl.getIdentifier() == id) {
            colorize(holder, id, AffogatoSyntaxHighlighter.LOCAL_VARIABLE);
            return;
        }
        if (parent instanceof ForContent forContent && forContent.getIdentifier() == id) {
            colorize(holder, id, AffogatoSyntaxHighlighter.LOCAL_VARIABLE);
            return;
        }
        if (parent instanceof CatchClause catchClause && catchClause.getIdentifier() == id) {
            colorize(holder, id, AffogatoSyntaxHighlighter.LOCAL_VARIABLE);
            return;
        }
        if (parent instanceof LambdaExpression lambda && lambda.getIdentifier() == id) {
            colorize(holder, id, AffogatoSyntaxHighlighter.PARAMETER);
            return;
        }
        if (parent instanceof TrailingClosure closure && closure.getIdentifier() == id) {
            colorize(holder, id, AffogatoSyntaxHighlighter.PARAMETER);
            return;
        }
        // Type names, annotation names and package/import qualified names are colored (or left
        // alone) elsewhere — don't double-process them here.
        if (PsiTreeUtil.getParentOfType(id, TypeRef.class, false) != null
                || PsiTreeUtil.getParentOfType(id, Annotation.class, false) != null
                || PsiTreeUtil.getParentOfType(id, PackageDecl.class, false) != null
                || PsiTreeUtil.getParentOfType(id, ImportDecl.class, false) != null) {
            return;
        }

        char next = nextNonWhitespaceChar(id);
        if (next == '(') {
            String name = id.getText();
            boolean constructor = !name.isEmpty() && Character.isUpperCase(name.charAt(0));
            colorize(holder, id, constructor
                    ? AffogatoSyntaxHighlighter.TYPE_REF
                    : AffogatoSyntaxHighlighter.FUNCTION_CALL);
        } else if (previousNonWhitespaceChar(id) == '.') {
            colorize(holder, id, AffogatoSyntaxHighlighter.INSTANCE_FIELD);
        }
    }

    private static void annotateStringEscapes(PsiElement stringToken, AnnotationHolder holder) {
        String text = stringToken.getText();
        int base = stringToken.getTextRange().getStartOffset();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '\\') {
                i++;
                continue;
            }
            int length = escapeLength(text, i);
            boolean valid = length > 0;
            int span = valid ? length : Math.min(2, text.length() - i);
            colorize(holder, TextRange.from(base + i, span), valid
                    ? AffogatoSyntaxHighlighter.VALID_STRING_ESCAPE
                    : AffogatoSyntaxHighlighter.INVALID_STRING_ESCAPE);
            i += span;
        }
    }

    /** Length of a valid escape starting at the backslash {@code index}, or 0 if invalid. */
    private static int escapeLength(String text, int index) {
        if (index + 1 >= text.length()) {
            return 0;
        }
        char escaped = text.charAt(index + 1);
        switch (escaped) {
            case '\\', '"', '\'', 'n', 't', 'r', 'b', 'f', 's', '0', '/' -> {
                return 2;
            }
            case 'u' -> {
                if (index + 5 < text.length() && isHex(text, index + 2, 4)) {
                    return 6;
                }
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    private static boolean isHex(String text, int start, int count) {
        for (int j = start; j < start + count; j++) {
            if (j >= text.length() || Character.digit(text.charAt(j), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStringToken(PsiElement element) {
        return element.getNode() != null && element.getNode().getElementType() == AffogatoTypes.STRING;
    }

    private static char nextNonWhitespaceChar(PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getEndOffset();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private static char previousNonWhitespaceChar(PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getStartOffset() - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        return index >= 0 ? text.charAt(index) : '\0';
    }

    private static void colorize(AnnotationHolder holder, PsiElement target, TextAttributesKey key) {
        if (target != null) {
            colorize(holder, target.getTextRange(), key);
        }
    }

    private static void colorize(AnnotationHolder holder, TextRange range, TextAttributesKey key) {
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(key)
                .create();
    }
}
