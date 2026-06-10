package dev.affogato.intellij.psi;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.AffogatoFileType;
import dev.affogato.intellij.project.AffogatoClasspath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AffogatoSymbols {
    public enum TopLevelKind {
        CLASS,
        RECORD,
        ENUM,
        INTERFACE
    }

    public record TopLevelType(
            @NotNull PsiElement declaration,
            @NotNull Identifier identifier,
            @NotNull String packageName,
            @NotNull TopLevelKind kind
    ) {
    }

    private AffogatoSymbols() {
    }

    public static @NotNull List<AffogatoFile> affogatoFiles(@NotNull Project project) {
        Collection<VirtualFile> virtualFiles = FileTypeIndex.getFiles(AffogatoFileType.INSTANCE, GlobalSearchScope.projectScope(project));
        List<AffogatoFile> files = new ArrayList<>();
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile virtualFile : virtualFiles) {
            PsiFile file = psiManager.findFile(virtualFile);
            if (file instanceof AffogatoFile affogatoFile) {
                files.add(affogatoFile);
            }
        }
        return files;
    }

    public static @NotNull String packageName(@NotNull AffogatoFile file) {
        PackageDecl packageDecl = PsiTreeUtil.findChildOfType(file, PackageDecl.class);
        if (packageDecl == null) {
            return "";
        }
        QualifiedName qualifiedName = packageDecl.getQualifiedName();
        return qualifiedName == null ? "" : qualifiedName.getText();
    }

    public static @NotNull List<TopLevelType> allTopLevelTypes(@NotNull Project project) {
        List<TopLevelType> types = new ArrayList<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            String pkg = packageName(file);
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                types.add(new TopLevelType(classDecl, classDecl.getIdentifier(), pkg, TopLevelKind.CLASS));
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                types.add(new TopLevelType(recordDecl, recordDecl.getIdentifier(), pkg, TopLevelKind.RECORD));
            }
            for (EnumDecl enumDecl : PsiTreeUtil.findChildrenOfType(file, EnumDecl.class)) {
                types.add(new TopLevelType(enumDecl, enumDecl.getIdentifier(), pkg, TopLevelKind.ENUM));
            }
            for (InterfaceDecl interfaceDecl : PsiTreeUtil.findChildrenOfType(file, InterfaceDecl.class)) {
                types.add(new TopLevelType(interfaceDecl, interfaceDecl.getIdentifier(), pkg, TopLevelKind.INTERFACE));
            }
        }
        return types;
    }

    public static @NotNull List<Identifier> allParametersInScope(@NotNull PsiElement place) {
        LinkedHashSet<Identifier> parameters = new LinkedHashSet<>();
        MethodDecl methodDecl = PsiTreeUtil.getParentOfType(place, MethodDecl.class);
        if (methodDecl != null) {
            collectParameters(methodDecl.getMethodSignature().getParameterList(), parameters);
        }
        ConstructorDecl constructorDecl = PsiTreeUtil.getParentOfType(place, ConstructorDecl.class);
        if (constructorDecl != null) {
            collectParameters(constructorDecl.getParameterList(), parameters);
        }
        return List.copyOf(parameters);
    }

    public static @NotNull List<Identifier> allLocalsInScope(@NotNull PsiElement place) {
        LinkedHashSet<Identifier> locals = new LinkedHashSet<>();
        int caret = place.getTextRange().getStartOffset();
        Block block = PsiTreeUtil.getParentOfType(place, Block.class);
        while (block != null) {
            for (LocalVarDecl localVarDecl : PsiTreeUtil.findChildrenOfType(block, LocalVarDecl.class)) {
                if (localVarDecl.getTextRange().getEndOffset() <= caret) {
                    locals.add(localVarDecl.getIdentifier());
                }
            }
            for (ForStatement forStatement : PsiTreeUtil.findChildrenOfType(block, ForStatement.class)) {
                if (forStatement.getTextRange().getEndOffset() <= caret) {
                    collectForLoopBindings(forStatement, locals);
                }
            }
            block = PsiTreeUtil.getParentOfType(block, Block.class);
        }
        return List.copyOf(locals);
    }

    public static @NotNull List<Identifier> allFieldsInEnclosingClass(@NotNull PsiElement place) {
        LinkedHashSet<Identifier> fields = new LinkedHashSet<>();
        PsiElement classLikeDecl = enclosingClassLikeDecl(place);
        if (classLikeDecl != null) {
            collectFields(classLikeDecl, fields);
        }
        return List.copyOf(fields);
    }

    public static @NotNull List<Identifier> allMethodsInEnclosingClass(@NotNull PsiElement place) {
        LinkedHashSet<Identifier> methods = new LinkedHashSet<>();
        PsiElement classLikeDecl = enclosingClassLikeDecl(place);
        if (classLikeDecl != null) {
            collectMethods(classLikeDecl, methods);
        }
        return List.copyOf(methods);
    }

    public static @NotNull List<Identifier> allProjectMethods(@NotNull Project project) {
        LinkedHashSet<Identifier> methods = new LinkedHashSet<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                collectMethods(classDecl, methods);
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                collectMethods(recordDecl, methods);
            }
        }
        return List.copyOf(methods);
    }

    public static @Nullable Identifier findClass(@NotNull PsiElement context, @NotNull String name) {
        PsiElement classDecl = findClassLikeDecl(context, name);
        return classLikeIdentifier(classDecl);
    }

    public static @Nullable PsiElement findClassLikeDecl(@NotNull PsiElement context, @NotNull String name) {
        if (context.getContainingFile() instanceof AffogatoFile file) {
            PsiElement imported = AffogatoImports.findImportedOrSamePackageType(file, context.getProject(), name);
            if (imported != null) {
                return imported;
            }
        }
        return findClassLikeDecl(context.getProject(), name);
    }

    public static @Nullable PsiElement findClassLikeDecl(@NotNull Project project, @NotNull String name) {
        List<PsiElement> matches = new ArrayList<>();
        for (TopLevelType type : allTopLevelTypes(project)) {
            if (type.identifier().getText().equals(simpleTypeName(name))) {
                matches.add(type.declaration());
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    public static @Nullable Identifier findParameterInScope(@NotNull PsiElement place, @NotNull String name) {
        MethodDecl methodDecl = PsiTreeUtil.getParentOfType(place, MethodDecl.class);
        if (methodDecl != null) {
            Identifier parameter = findParameter(methodDecl.getMethodSignature().getParameterList(), name);
            if (parameter != null) {
                return parameter;
            }
        }
        ConstructorDecl constructorDecl = PsiTreeUtil.getParentOfType(place, ConstructorDecl.class);
        if (constructorDecl != null) {
            return findParameter(constructorDecl.getParameterList(), name);
        }
        return null;
    }

    public static @Nullable Identifier findCurrentClassField(@NotNull PsiElement place, @NotNull String name) {
        PsiElement classLikeDecl = enclosingClassLikeDecl(place);
        return classLikeDecl == null ? null : findUniqueField(classLikeDecl, name);
    }

    public static @Nullable Identifier findCurrentClassMethod(@NotNull PsiElement place, @NotNull String name) {
        PsiElement classLikeDecl = enclosingClassLikeDecl(place);
        return classLikeDecl == null ? null : findUniqueMethod(classLikeDecl, name);
    }

    public static @Nullable Identifier findUniqueField(@NotNull PsiElement classLikeDecl, @NotNull String name) {
        List<Identifier> matches = new ArrayList<>();
        collectFields(classLikeDecl, matches);
        matches.removeIf(identifier -> !identifier.getText().equals(name));
        return unique(matches);
    }

    public static @Nullable Identifier findUniqueMethod(@NotNull PsiElement classLikeDecl, @NotNull String name) {
        List<Identifier> matches = new ArrayList<>();
        collectMethods(classLikeDecl, matches);
        matches.removeIf(identifier -> !identifier.getText().equals(name));
        return unique(matches);
    }

    public static @NotNull List<Identifier> allFields(@NotNull PsiElement classLikeDecl) {
        LinkedHashSet<Identifier> fields = new LinkedHashSet<>();
        collectFields(classLikeDecl, fields);
        return List.copyOf(fields);
    }

    public static @NotNull List<Identifier> allMethods(@NotNull PsiElement typeDecl) {
        LinkedHashSet<Identifier> methods = new LinkedHashSet<>();
        collectMethods(typeDecl, methods);
        return List.copyOf(methods);
    }

    public static @NotNull List<MethodSignature> methodSignatures(
            @NotNull PsiElement typeDecl,
            @NotNull String name
    ) {
        List<MethodSignature> signatures = new ArrayList<>();
        if (typeDecl instanceof InterfaceDecl interfaceDecl) {
            InterfaceBody interfaceBody = interfaceDecl.getInterfaceBody();
            if (interfaceBody != null) {
                for (InterfaceMember member : interfaceBody.getInterfaceMemberList()) {
                    MethodSignature signature = member.getMethodSignature();
                    if (signature != null && signature.getIdentifier().getText().equals(name)) {
                        signatures.add(signature);
                    }
                }
            }
            return signatures;
        }
        ClassBody classBody = classBody(typeDecl);
        if (classBody == null) {
            return signatures;
        }
        for (ClassMember member : classBody.getClassMemberList()) {
            MethodDecl methodDecl = member.getMethodDecl();
            if (methodDecl != null) {
                MethodSignature signature = methodDecl.getMethodSignature();
                if (signature.getIdentifier().getText().equals(name)) {
                    signatures.add(signature);
                }
            }
        }
        return signatures;
    }

    public static @NotNull List<MethodSignature> projectMethodSignatures(
            @NotNull Project project,
            @NotNull String name
    ) {
        List<MethodSignature> signatures = new ArrayList<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                signatures.addAll(methodSignatures(classDecl, name));
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                signatures.addAll(methodSignatures(recordDecl, name));
            }
            for (InterfaceDecl interfaceDecl : PsiTreeUtil.findChildrenOfType(file, InterfaceDecl.class)) {
                signatures.addAll(methodSignatures(interfaceDecl, name));
            }
        }
        return signatures;
    }

    public static @NotNull List<Parameter> parameters(@NotNull MethodSignature signature) {
        ParameterList parameterList = signature.getParameterList();
        if (parameterList == null) {
            return List.of();
        }
        return List.copyOf(PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class));
    }

    public static @NotNull List<Parameter> constructorParameters(@NotNull PsiElement typeDecl) {
        List<Parameter> parameters = new ArrayList<>();
        if (typeDecl instanceof RecordDecl recordDecl) {
            ParameterList parameterList = recordDecl.getRecordHeader().getParameterList();
            if (parameterList != null) {
                parameters.addAll(PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class));
            }
            return parameters;
        }
        ParameterList parameterList = constructorParameterList(typeDecl);
        if (parameterList != null) {
            parameters.addAll(PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class));
        }
        return parameters;
    }

    public static @NotNull List<Identifier> allEnumConstants(@NotNull EnumDecl enumDecl) {
        LinkedHashSet<Identifier> constants = new LinkedHashSet<>();
        EnumBody enumBody = enumDecl.getEnumBody();
        if (enumBody != null) {
            for (EnumConstant enumConstant : PsiTreeUtil.findChildrenOfType(enumBody, EnumConstant.class)) {
                constants.add(enumConstant.getIdentifier());
            }
        }
        return List.copyOf(constants);
    }

    public static @NotNull List<Identifier> allProjectFields(@NotNull Project project) {
        LinkedHashSet<Identifier> fields = new LinkedHashSet<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                collectFields(classDecl, fields);
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                collectFields(recordDecl, fields);
            }
        }
        return List.copyOf(fields);
    }

    public static @Nullable Identifier findUniqueProjectField(@NotNull Project project, @NotNull String name) {
        List<Identifier> matches = new ArrayList<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                Identifier field = findUniqueField(classDecl, name);
                if (field != null) {
                    matches.add(field);
                }
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                Identifier field = findUniqueField(recordDecl, name);
                if (field != null) {
                    matches.add(field);
                }
            }
        }
        return unique(matches);
    }

    public static @Nullable Identifier findUniqueProjectMethod(@NotNull Project project, @NotNull String name) {
        List<Identifier> matches = new ArrayList<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                Identifier method = findUniqueMethod(classDecl, name);
                if (method != null) {
                    matches.add(method);
                }
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                Identifier method = findUniqueMethod(recordDecl, name);
                if (method != null) {
                    matches.add(method);
                }
            }
        }
        return unique(matches);
    }

    public static @NotNull String resolveOwnerType(@NotNull PsiElement place, @NotNull String ownerName) {
        Identifier parameter = findParameterInScope(place, ownerName);
        if (parameter != null && parameter.getParent() instanceof Parameter parameterDecl) {
            TypeRef typeRef = parameterDecl.getTypeRef();
            return typeRef == null ? "" : simpleTypeName(typeRef.getText());
        }

        PsiElement classLikeDecl = enclosingClassLikeDecl(place);
        ClassBody classBody = classBody(classLikeDecl);
        if (classBody != null) {
            for (ClassMember member : classBody.getClassMemberList()) {
                FieldDecl fieldDecl = member.getFieldDecl();
                if (fieldDecl != null && fieldDecl.getIdentifier().getText().equals(ownerName)) {
                    String fieldType = declaredFieldType(fieldDecl);
                    if (!fieldType.isBlank()) {
                        return fieldType;
                    }
                }
            }
        }

        String localType = inferLocalTypeBefore(place, ownerName);
        return localType == null ? "" : localType;
    }

    public static boolean isTypeReferenceIdentifier(@NotNull PsiElement element) {
        Identifier identifier = element instanceof Identifier typed ? typed : PsiTreeUtil.getParentOfType(element, Identifier.class, false);
        if (identifier == null || !isLastIdentifierInQualifiedName(identifier)) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(identifier, TypeRef.class, false) != null;
    }

    public static @NotNull String simpleTypeName(@NotNull String typeName) {
        String cleaned = typeName.trim();
        if (cleaned.endsWith("?") || cleaned.endsWith("!")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        int generic = cleaned.indexOf('<');
        if (generic >= 0) {
            cleaned = cleaned.substring(0, generic);
        }
        while (cleaned.endsWith("[]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        }
        int dot = cleaned.lastIndexOf('.');
        return dot >= 0 ? cleaned.substring(dot + 1) : cleaned;
    }

    private static void collectParameters(@Nullable ParameterList parameterList, @NotNull LinkedHashSet<Identifier> out) {
        if (parameterList == null) {
            return;
        }
        for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
            out.add(parameter.getIdentifier());
        }
    }

    private static void collectForLoopBindings(@NotNull ForStatement forStatement, @NotNull LinkedHashSet<Identifier> out) {
        ForCondition forCondition = forStatement.getForCondition();
        if (forCondition == null) {
            return;
        }
        ForContent forContent = forCondition.getForContent();
        if (forContent == null) {
            return;
        }
        Identifier identifier = forContent.getIdentifier();
        if (identifier != null) {
            out.add(identifier);
        }
    }

    private static void collectFields(@NotNull PsiElement classLikeDecl, @NotNull Collection<Identifier> out) {
        ClassBody classBody = classBody(classLikeDecl);
        if (classBody != null) {
            for (ClassMember member : classBody.getClassMemberList()) {
                FieldDecl fieldDecl = member.getFieldDecl();
                if (fieldDecl != null) {
                    out.add(fieldDecl.getIdentifier());
                }
            }
        }
        ParameterList parameterList = constructorParameterList(classLikeDecl);
        if (parameterList != null) {
            for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                if (isPropertyParameter(parameter)) {
                    out.add(parameter.getIdentifier());
                }
            }
        }
        if (classLikeDecl instanceof RecordDecl recordDecl) {
            parameterList = recordDecl.getRecordHeader().getParameterList();
            if (parameterList != null) {
                for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                    out.add(parameter.getIdentifier());
                }
            }
        }
    }

    private static void collectMethods(@NotNull PsiElement typeDecl, @NotNull Collection<Identifier> out) {
        if (typeDecl instanceof InterfaceDecl interfaceDecl) {
            InterfaceBody interfaceBody = interfaceDecl.getInterfaceBody();
            if (interfaceBody != null) {
                for (InterfaceMember member : interfaceBody.getInterfaceMemberList()) {
                    MethodSignature methodSignature = member.getMethodSignature();
                    if (methodSignature != null) {
                        out.add(methodSignature.getIdentifier());
                    }
                }
            }
            return;
        }
        ClassBody classBody = classBody(typeDecl);
        if (classBody == null) {
            return;
        }
        for (ClassMember member : classBody.getClassMemberList()) {
            MethodDecl methodDecl = member.getMethodDecl();
            if (methodDecl != null) {
                out.add(methodDecl.getMethodSignature().getIdentifier());
            }
        }
    }

    private static @Nullable PsiElement enclosingClassLikeDecl(@NotNull PsiElement place) {
        PsiElement classDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classDecl != null) {
            return classDecl;
        }
        return PsiTreeUtil.getParentOfType(place, RecordDecl.class);
    }

    private static @Nullable Identifier classLikeIdentifier(@Nullable PsiElement element) {
        if (element instanceof ClassDecl classDecl) {
            return classDecl.getIdentifier();
        }
        if (element instanceof RecordDecl recordDecl) {
            return recordDecl.getIdentifier();
        }
        if (element instanceof EnumDecl enumDecl) {
            return enumDecl.getIdentifier();
        }
        if (element instanceof InterfaceDecl interfaceDecl) {
            return interfaceDecl.getIdentifier();
        }
        return null;
    }

    private static @Nullable Identifier findParameter(@Nullable ParameterList parameterList, @NotNull String name) {
        if (parameterList == null) {
            return null;
        }
        List<Identifier> matches = new ArrayList<>();
        for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
            if (parameter.getIdentifier().getText().equals(name)) {
                matches.add(parameter.getIdentifier());
            }
        }
        return unique(matches);
    }

    private static @Nullable ClassBody classBody(@Nullable PsiElement classLikeDecl) {
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

    private static boolean isLastIdentifierInQualifiedName(@NotNull Identifier identifier) {
        QualifiedName qualifiedName = PsiTreeUtil.getParentOfType(identifier, QualifiedName.class, false);
        if (qualifiedName == null) {
            return false;
        }
        List<Identifier> identifiers = qualifiedName.getIdentifierList();
        return !identifiers.isEmpty() && identifiers.get(identifiers.size() - 1) == identifier;
    }

    private static String declaredFieldType(@NotNull FieldDecl fieldDecl) {
        TypeRef typeRef = fieldDecl.getTypeRef();
        if (typeRef != null) {
            return simpleTypeName(typeRef.getText());
        }
        Expression expression = fieldDecl.getExpression();
        return expression == null ? "" : inferTypeFromInitializer(expression.getText());
    }

    private static @Nullable String inferLocalTypeBefore(@NotNull PsiElement place, @NotNull String ownerName) {
        Block block = PsiTreeUtil.getParentOfType(place, Block.class);
        if (block == null) {
            return null;
        }
        int relativeEnd = place.getTextRange().getStartOffset() - block.getTextRange().getStartOffset();
        if (relativeEnd <= 0 || relativeEnd > block.getTextLength()) {
            return null;
        }
        String prefix = block.getText().substring(0, relativeEnd);
        Pattern typed = Pattern.compile("\\b(?:var|let)?\\s*" + Pattern.quote(ownerName) + "\\s*:\\s*([A-Za-z_][A-Za-z0-9_.$]*(?:<[^>]+>)?)");
        Matcher typedMatcher = typed.matcher(prefix);
        String match = null;
        while (typedMatcher.find()) {
            match = simpleTypeName(typedMatcher.group(1));
        }
        if (match != null) {
            return match;
        }
        Pattern inferred = Pattern.compile("\\b(?:var|let)\\s+" + Pattern.quote(ownerName) + "\\s*=\\s*(?:new\\s+)?([A-Z][A-Za-z0-9_.$]*)\\s*(?:<[^>]+>)?\\s*\\(");
        Matcher inferredMatcher = inferred.matcher(prefix);
        while (inferredMatcher.find()) {
            match = simpleTypeName(inferredMatcher.group(1));
        }
        return match;
    }

    private static String inferTypeFromInitializer(@NotNull String expression) {
        Matcher matcher = Pattern.compile("^(?:new\\s+)?([A-Z][A-Za-z0-9_.$]*)\\s*(?:<[^>]+>)?\\s*\\(").matcher(expression.trim());
        return matcher.find() ? simpleTypeName(matcher.group(1)) : "";
    }

    private static @Nullable Identifier unique(@NotNull List<Identifier> identifiers) {
        return identifiers.size() == 1 ? identifiers.get(0) : null;
    }
}
