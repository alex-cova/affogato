package dev.affogato.intellij.completion;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import dev.affogato.intellij.psi.AffogatoSymbols;
import dev.affogato.intellij.psi.AffogatoTextUtil;
import dev.affogato.intellij.psi.AffogatoTypeResolver;
import dev.affogato.intellij.psi.CallGroup;
import dev.affogato.intellij.psi.ClassDecl;
import dev.affogato.intellij.psi.MethodSignature;
import dev.affogato.intellij.psi.Parameter;
import dev.affogato.intellij.psi.RecordDecl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AffogatoCallSite {
    public record CallableParameter(@NotNull String name, @NotNull String typeText) {
    }

    public enum Kind {
        CONSTRUCTOR,
        METHOD
    }

    public record Site(
            @NotNull Kind kind,
            @NotNull String calleeExpression,
            @NotNull String methodName,
            @NotNull String receiverExpression,
            @NotNull CallGroup callGroup
    ) {
    }

    private AffogatoCallSite() {
    }

    public static @Nullable Site at(@NotNull PsiElement position) {
        return at(position, position.getTextRange().getStartOffset());
    }

    public static @Nullable Site at(@NotNull PsiElement position, int offset) {
        CallGroup callGroup = PsiTreeUtil.getParentOfType(position, CallGroup.class);
        if (callGroup == null || !AffogatoTextUtil.isNamedArgumentCompletionPosition(position, offset)) {
            return null;
        }
        String calleeExpression = AffogatoTextUtil.calleeExpressionBeforeCall(position);
        if (calleeExpression.isBlank()) {
            return null;
        }
        Kind kind = isConstructorCall(calleeExpression) ? Kind.CONSTRUCTOR : Kind.METHOD;
        String methodName = methodName(calleeExpression, kind);
        String receiver = receiverExpression(calleeExpression, kind);
        return new Site(kind, calleeExpression, methodName, receiver, callGroup);
    }

    public static @NotNull List<CallableParameter> callableParameters(
            @NotNull Site site,
            @NotNull PsiElement position,
            @NotNull PsiFile file,
            @NotNull GlobalSearchScope scope
    ) {
        LinkedHashSet<CallableParameter> parameters = new LinkedHashSet<>();
        if (site.kind() == Kind.CONSTRUCTOR) {
            addAffogatoConstructorParameters(position, site.methodName(), parameters);
            addJavaConstructorParameters(position, file, scope, site.methodName(), parameters);
            return List.copyOf(parameters);
        }
        addAffogatoMethodParameters(position, site.receiverExpression(), site.methodName(), parameters);
        addJavaMethodParameters(position, file, scope, site.receiverExpression(), site.methodName(), parameters);
        return List.copyOf(parameters);
    }

    public static @NotNull Set<String> usedNamedArguments(@NotNull CallGroup callGroup) {
        return AffogatoTextUtil.usedNamedArguments(callGroup);
    }

    private static boolean isConstructorCall(@NotNull String calleeExpression) {
        return calleeExpression.startsWith("new ") || AffogatoTextUtil.isConstructorCallee(calleeExpression);
    }

    private static @NotNull String methodName(@NotNull String calleeExpression, @NotNull Kind kind) {
        if (kind == Kind.CONSTRUCTOR) {
            Matcher matcher = Pattern.compile("new\\s+([A-Z][A-Za-z0-9_.$]*)").matcher(calleeExpression);
            if (matcher.find()) {
                return AffogatoSymbols.simpleTypeName(matcher.group(1));
            }
            return AffogatoSymbols.simpleTypeName(calleeExpression);
        }
        int dot = calleeExpression.lastIndexOf('.');
        return dot >= 0 ? calleeExpression.substring(dot + 1) : calleeExpression;
    }

    private static @NotNull String receiverExpression(@NotNull String calleeExpression, @NotNull Kind kind) {
        if (kind == Kind.CONSTRUCTOR) {
            return "";
        }
        int dot = calleeExpression.lastIndexOf('.');
        return dot >= 0 ? calleeExpression.substring(0, dot) : "";
    }

    private static void addAffogatoConstructorParameters(
            @NotNull PsiElement position,
            @NotNull String typeName,
            @NotNull Set<CallableParameter> parameters
    ) {
        PsiElement typeDecl = AffogatoTypeResolver.findTypeDecl(position, typeName);
        if (typeDecl == null) {
            return;
        }
        for (Parameter parameter : AffogatoSymbols.constructorParameters(typeDecl)) {
            parameters.add(toCallableParameter(parameter));
        }
    }

    private static void addJavaConstructorParameters(
            @NotNull PsiElement position,
            @NotNull PsiFile file,
            @NotNull GlobalSearchScope scope,
            @NotNull String typeName,
            @NotNull Set<CallableParameter> parameters
    ) {
        PsiClass psiClass = AffogatoJavaIndex.resolveClass(position, position.getProject(), scope, typeName);
        if (psiClass == null) {
            return;
        }
        for (PsiMethod constructor : psiClass.getConstructors()) {
            addJavaMethodParameters(constructor, parameters);
        }
    }

    private static void addAffogatoMethodParameters(
            @NotNull PsiElement position,
            @NotNull String receiverExpression,
            @NotNull String methodName,
            @NotNull Set<CallableParameter> parameters
    ) {
        if (receiverExpression.isBlank()) {
            PsiElement enclosing = PsiTreeUtil.getParentOfType(position, ClassDecl.class, RecordDecl.class);
            if (enclosing != null) {
                for (MethodSignature signature : AffogatoSymbols.methodSignatures(enclosing, methodName)) {
                    addAffogatoMethodParameters(signature, parameters);
                }
            }
            Project project = position.getProject();
            for (MethodSignature signature : AffogatoSymbols.projectMethodSignatures(project, methodName)) {
                addAffogatoMethodParameters(signature, parameters);
            }
            return;
        }
        String ownerType = AffogatoTypeResolver.resolveExpressionType(position, receiverExpression);
        if (ownerType.isBlank()) {
            return;
        }
        PsiElement ownerDecl = AffogatoTypeResolver.findTypeDecl(position, ownerType);
        if (ownerDecl != null) {
            for (MethodSignature signature : AffogatoSymbols.methodSignatures(ownerDecl, methodName)) {
                addAffogatoMethodParameters(signature, parameters);
            }
        }
    }

    private static void addJavaMethodParameters(
            @NotNull PsiElement position,
            @NotNull PsiFile file,
            @NotNull GlobalSearchScope scope,
            @NotNull String receiverExpression,
            @NotNull String methodName,
            @NotNull Set<CallableParameter> parameters
    ) {
        String ownerType = receiverExpression.isBlank()
                ? ""
                : AffogatoTypeResolver.resolveExpressionType(position, receiverExpression);
        if (ownerType.isBlank() && !receiverExpression.isBlank()) {
            return;
        }
        PsiClass psiClass = ownerType.isBlank()
                ? null
                : AffogatoJavaIndex.resolveClass(position, position.getProject(), scope, ownerType);
        if (psiClass == null) {
            return;
        }
        for (PsiMethod method : psiClass.findMethodsByName(methodName, true)) {
            addJavaMethodParameters(method, parameters);
        }
    }

    private static void addAffogatoMethodParameters(
            @NotNull MethodSignature signature,
            @NotNull Set<CallableParameter> parameters
    ) {
        for (Parameter parameter : AffogatoSymbols.parameters(signature)) {
            parameters.add(toCallableParameter(parameter));
        }
    }

    private static void addJavaMethodParameters(
            @NotNull PsiMethod method,
            @NotNull Set<CallableParameter> parameters
    ) {
        for (com.intellij.psi.PsiParameter parameter : method.getParameterList().getParameters()) {
            String name = parameter.getName();
            if (name != null) {
                parameters.add(new CallableParameter(name, parameter.getType().getPresentableText()));
            }
        }
    }

    private static @NotNull CallableParameter toCallableParameter(@NotNull Parameter parameter) {
        String typeText = parameter.getTypeRef() == null ? "" : parameter.getTypeRef().getText();
        return new CallableParameter(parameter.getIdentifier().getText(), typeText);
    }
}
