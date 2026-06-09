package dev.affogato.intellij.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoPsiUtil {
    public enum SymbolKind {
        CLASS,
        FIELD,
        METHOD,
        PARAMETER,
        UNKNOWN
    }

    private AffogatoPsiUtil() {
    }

    public static boolean canHaveReference(@NotNull PsiElement element) {
        if (element instanceof Identifier identifier) {
            return !isDeclarationIdentifier(identifier);
        }
        return isIdToken(element) && !(element.getParent() instanceof Identifier);
    }

    public static boolean isDeclarationIdentifier(@NotNull PsiElement element) {
        if (!(element instanceof Identifier identifier)) {
            return false;
        }
        PsiElement parent = identifier.getParent();
        return parent instanceof ClassDecl classDecl && classDecl.getIdentifier() == identifier
                || parent instanceof RecordDecl recordDecl && recordDecl.getIdentifier() == identifier
                || parent instanceof EnumDecl enumDecl && enumDecl.getIdentifier() == identifier
                || parent instanceof InterfaceDecl interfaceDecl && interfaceDecl.getIdentifier() == identifier
                || parent instanceof FieldDecl fieldDecl && fieldDecl.getIdentifier() == identifier
                || parent instanceof MethodSignature methodSignature && methodSignature.getIdentifier() == identifier
                || parent instanceof Parameter parameter && parameter.getIdentifier() == identifier;
    }

    public static @NotNull SymbolKind declarationKind(@NotNull PsiElement element) {
        if (!(element instanceof Identifier identifier)) {
            return SymbolKind.UNKNOWN;
        }
        PsiElement parent = identifier.getParent();
        if (parent instanceof ClassDecl classDecl && classDecl.getIdentifier() == identifier) {
            return SymbolKind.CLASS;
        }
        if (parent instanceof RecordDecl recordDecl && recordDecl.getIdentifier() == identifier) {
            return SymbolKind.CLASS;
        }
        if (parent instanceof EnumDecl enumDecl && enumDecl.getIdentifier() == identifier) {
            return SymbolKind.CLASS;
        }
        if (parent instanceof InterfaceDecl interfaceDecl && interfaceDecl.getIdentifier() == identifier) {
            return SymbolKind.CLASS;
        }
        if (parent instanceof FieldDecl fieldDecl && fieldDecl.getIdentifier() == identifier) {
            return SymbolKind.FIELD;
        }
        if (parent instanceof MethodSignature methodSignature && methodSignature.getIdentifier() == identifier) {
            return SymbolKind.METHOD;
        }
        if (parent instanceof Parameter parameter && parameter.getIdentifier() == identifier) {
            return SymbolKind.PARAMETER;
        }
        return SymbolKind.UNKNOWN;
    }

    public static @Nullable PsiElement resolveReference(@NotNull PsiElement element) {
        if (!canHaveReference(element)) {
            return null;
        }
        String name = element.getText();
        if (name.isBlank()) {
            return null;
        }

        if (AffogatoSymbols.isTypeReferenceIdentifier(element)) {
            return AffogatoSymbols.findClass(element, name);
        }

        char previous = AffogatoTextUtil.previousNonWhitespaceChar(element);
        char next = AffogatoTextUtil.nextNonWhitespaceChar(element);
        if (previous == '.') {
            String ownerName = AffogatoTextUtil.previousWordBeforeDot(element);
            String ownerType = ownerName.isBlank() ? "" : AffogatoSymbols.resolveOwnerType(element, ownerName);
            if (!ownerType.isBlank()) {
                PsiElement ownerClass = AffogatoSymbols.findClassLikeDecl(element, ownerType);
                if (ownerClass != null) {
                    return next == '('
                            ? AffogatoSymbols.findUniqueMethod(ownerClass, name)
                            : AffogatoSymbols.findUniqueField(ownerClass, name);
                }
            }
            return next == '('
                    ? AffogatoSymbols.findUniqueProjectMethod(element.getProject(), name)
                    : AffogatoSymbols.findUniqueProjectField(element.getProject(), name);
        }

        if (Character.isUpperCase(name.charAt(0)) && next == '(') {
            return AffogatoSymbols.findClass(element, name);
        }
        if (next == '(') {
            Identifier currentMethod = AffogatoSymbols.findCurrentClassMethod(element, name);
            return currentMethod != null ? currentMethod : AffogatoSymbols.findUniqueProjectMethod(element.getProject(), name);
        }

        Identifier parameter = AffogatoSymbols.findParameterInScope(element, name);
        if (parameter != null) {
            return parameter;
        }
        Identifier field = AffogatoSymbols.findCurrentClassField(element, name);
        if (field != null) {
            return field;
        }
        if (Character.isUpperCase(name.charAt(0))) {
            return AffogatoSymbols.findClass(element, name);
        }
        return null;
    }

    public static boolean isValidIdentifier(@NotNull String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static boolean isIdToken(PsiElement element) {
        return element.getNode() != null && element.getNode().getElementType() == AffogatoTypes.ID;
    }
}
