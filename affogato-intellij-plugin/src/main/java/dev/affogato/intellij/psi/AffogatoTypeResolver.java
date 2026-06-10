package dev.affogato.intellij.psi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class AffogatoTypeResolver {
    private AffogatoTypeResolver() {
    }

    public static @NotNull String resolveExpressionType(@NotNull PsiElement place, @NotNull String expression) {
        if (expression.isBlank()) {
            return "";
        }
        String[] parts = expression.split("\\.");
        String type = resolveSimpleExpressionType(place, parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (type.isBlank()) {
                return "";
            }
            type = resolveMemberType(place, type, parts[i]);
        }
        return type;
    }

    public static @Nullable PsiElement findTypeDecl(@NotNull PsiElement context, @NotNull String simpleName) {
        if (context.getContainingFile() instanceof AffogatoFile file) {
            PsiElement imported = AffogatoImports.findImportedOrSamePackageType(file, context.getProject(), simpleName);
            if (imported != null) {
                return imported;
            }
        }
        return findTypeDecl(context.getProject(), simpleName);
    }

    public static @Nullable PsiElement findTypeDecl(@NotNull Project project, @NotNull String simpleName) {
        String normalized = AffogatoSymbols.simpleTypeName(simpleName);
        for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
            if (type.identifier().getText().equals(normalized)) {
                return type.declaration();
            }
        }
        return null;
    }

    public static @NotNull String resolveMemberType(@NotNull PsiElement context, @NotNull String ownerType, @NotNull String memberName) {
        PsiElement ownerDecl = findTypeDecl(context, ownerType);
        if (ownerDecl == null) {
            GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(context.getContainingFile());
            PsiClass javaClass = AffogatoJavaIndex.resolveClass(context, context.getProject(), scope, ownerType);
            if (javaClass != null) {
                return AffogatoJavaIndex.memberType(javaClass, memberName);
            }
            return "";
        }
        if (ownerDecl instanceof EnumDecl enumDecl) {
            for (Identifier constant : AffogatoSymbols.allEnumConstants(enumDecl)) {
                if (constant.getText().equals(memberName)) {
                    return ownerType;
                }
            }
            return "";
        }
        Identifier field = AffogatoSymbols.findUniqueField(ownerDecl, memberName);
        if (field != null) {
            return fieldTypeName(ownerDecl, memberName);
        }
        return "";
    }

    private static @NotNull String resolveSimpleExpressionType(@NotNull PsiElement place, @NotNull String name) {
        if ("this".equals(name)) {
            PsiElement enclosing = enclosingClassLikeDecl(place);
            if (enclosing instanceof ClassDecl classDecl) {
                return classDecl.getIdentifier().getText();
            }
            if (enclosing instanceof RecordDecl recordDecl) {
                return recordDecl.getIdentifier().getText();
            }
            return "";
        }
        if ("super".equals(name)) {
            return superType(place);
        }

        String ownerType = AffogatoSymbols.resolveOwnerType(place, name);
        if (!ownerType.isBlank()) {
            return ownerType;
        }
        if (findTypeDecl(place, name) != null) {
            return AffogatoSymbols.simpleTypeName(name);
        }
        PsiFile file = place.getContainingFile();
        if (file instanceof AffogatoFile affogatoFile) {
            GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(file);
            PsiClass javaClass = AffogatoJavaIndex.findImportedClass(affogatoFile, place.getProject(), scope, name);
            if (javaClass == null) {
                javaClass = AffogatoJavaIndex.resolveClass(place, place.getProject(), scope, name);
            }
            if (javaClass != null && javaClass.getName() != null) {
                return javaClass.getName();
            }
        }
        return "";
    }

    private static @NotNull String superType(@NotNull PsiElement place) {
        ClassDecl classDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classDecl == null) {
            return "";
        }
        ExtendsClause extendsClause = classDecl.getExtendsClause();
        if (extendsClause == null) {
            return "";
        }
        java.util.List<TypeRef> typeRefs = extendsClause.getTypeRefList();
        if (typeRefs.isEmpty()) {
            return "";
        }
        return AffogatoSymbols.simpleTypeName(typeRefs.get(0).getText());
    }

    private static @NotNull String fieldTypeName(@NotNull PsiElement ownerDecl, @NotNull String fieldName) {
        ClassBody classBody = classBody(ownerDecl);
        if (classBody != null) {
            for (ClassMember member : classBody.getClassMemberList()) {
                FieldDecl fieldDecl = member.getFieldDecl();
                if (fieldDecl != null && fieldDecl.getIdentifier().getText().equals(fieldName)) {
                    return declaredFieldType(fieldDecl);
                }
            }
        }
        if (ownerDecl instanceof RecordDecl recordDecl) {
            ParameterList parameterList = recordDecl.getRecordHeader().getParameterList();
            if (parameterList != null) {
                for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                    if (parameter.getIdentifier().getText().equals(fieldName)) {
                        TypeRef typeRef = parameter.getTypeRef();
                        return typeRef == null ? "" : AffogatoSymbols.simpleTypeName(typeRef.getText());
                    }
                }
            }
        }
        ParameterList parameterList = constructorParameterList(ownerDecl);
        if (parameterList != null) {
            for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                if (parameter.getIdentifier().getText().equals(fieldName) && isPropertyParameter(parameter)) {
                    TypeRef typeRef = parameter.getTypeRef();
                    return typeRef == null ? "" : AffogatoSymbols.simpleTypeName(typeRef.getText());
                }
            }
        }
        return "";
    }

    private static @Nullable PsiElement enclosingClassLikeDecl(@NotNull PsiElement place) {
        PsiElement classDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classDecl != null) {
            return classDecl;
        }
        return PsiTreeUtil.getParentOfType(place, RecordDecl.class);
    }

    private static @Nullable ClassBody classBody(@NotNull PsiElement classLikeDecl) {
        if (classLikeDecl instanceof ClassDecl classDecl) {
            return classDecl.getClassBody();
        }
        if (classLikeDecl instanceof RecordDecl recordDecl) {
            return recordDecl.getClassBody();
        }
        return null;
    }

    private static @Nullable ParameterList constructorParameterList(@NotNull PsiElement classLikeDecl) {
        if (classLikeDecl instanceof ClassDecl classDecl && classDecl.getCompactConstructor() != null) {
            return classDecl.getCompactConstructor().getParameterList();
        }
        return null;
    }

    private static boolean isPropertyParameter(@NotNull Parameter parameter) {
        VariableKind variableKind = parameter.getVariableKind();
        return variableKind != null && (variableKind.getText().equals("var") || variableKind.getText().equals("let"));
    }

    private static @NotNull String declaredFieldType(@NotNull FieldDecl fieldDecl) {
        TypeRef typeRef = fieldDecl.getTypeRef();
        if (typeRef != null) {
            return AffogatoSymbols.simpleTypeName(typeRef.getText());
        }
        Expression expression = fieldDecl.getExpression();
        if (expression == null) {
            return "";
        }
        return inferTypeFromInitializer(expression.getText());
    }

    private static @NotNull String inferTypeFromInitializer(@NotNull String expression) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(?:new\\s+)?([A-Z][A-Za-z0-9_.$]*)\\s*(?:<[^>]+>)?\\s*\\(")
                .matcher(expression.trim());
        return matcher.find() ? AffogatoSymbols.simpleTypeName(matcher.group(1)) : "";
    }
}
