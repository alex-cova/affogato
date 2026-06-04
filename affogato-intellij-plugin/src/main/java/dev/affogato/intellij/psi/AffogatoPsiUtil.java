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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        if (isTypeReferenceIdentifier(element)) {
            return findClass(element.getProject(), name);
        }

        char previous = previousNonWhitespaceChar(element);
        char next = nextNonWhitespaceChar(element);
        if (previous == '.') {
            String ownerName = previousWordBeforeDot(element);
            String ownerType = ownerName.isBlank() ? "" : resolveOwnerType(element, ownerName);
            if (!ownerType.isBlank()) {
                PsiElement ownerClass = findClassLikeDecl(element.getProject(), ownerType);
                if (ownerClass != null) {
                    return next == '('
                            ? findUniqueMethod(ownerClass, name)
                            : findUniqueField(ownerClass, name);
                }
            }
            return next == '(' ? findUniqueProjectMethod(element.getProject(), name) : findUniqueProjectField(element.getProject(), name);
        }

        if (Character.isUpperCase(name.charAt(0)) && next == '(') {
            return findClass(element.getProject(), name);
        }
        if (next == '(') {
            Identifier currentMethod = findCurrentClassMethod(element, name);
            return currentMethod != null ? currentMethod : findUniqueProjectMethod(element.getProject(), name);
        }

        Identifier parameter = findParameterInScope(element, name);
        if (parameter != null) {
            return parameter;
        }
        Identifier field = findCurrentClassField(element, name);
        if (field != null) {
            return field;
        }
        if (Character.isUpperCase(name.charAt(0))) {
            return findClass(element.getProject(), name);
        }
        return null;
    }

    public static boolean isValidIdentifier(@NotNull String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static boolean isIdToken(PsiElement element) {
        return element.getNode() != null && element.getNode().getElementType() == AffogatoTypes.ID;
    }

    private static boolean isTypeReferenceIdentifier(PsiElement element) {
        Identifier identifier = element instanceof Identifier typed ? typed : PsiTreeUtil.getParentOfType(element, Identifier.class, false);
        if (identifier == null || !isLastIdentifierInQualifiedName(identifier)) {
            return false;
        }
        return PsiTreeUtil.getParentOfType(identifier, TypeRef.class, false) != null;
    }

    private static boolean isLastIdentifierInQualifiedName(Identifier identifier) {
        QualifiedName qualifiedName = PsiTreeUtil.getParentOfType(identifier, QualifiedName.class, false);
        if (qualifiedName == null) {
            return false;
        }
        List<Identifier> identifiers = qualifiedName.getIdentifierList();
        return !identifiers.isEmpty() && identifiers.get(identifiers.size() - 1) == identifier;
    }

    private static char previousNonWhitespaceChar(PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getStartOffset() - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        return index >= 0 ? text.charAt(index) : '\0';
    }

    private static char nextNonWhitespaceChar(PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getEndOffset();
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index < text.length() ? text.charAt(index) : '\0';
    }

    private static String previousWordBeforeDot(PsiElement element) {
        String text = element.getContainingFile().getText();
        int index = element.getTextRange().getStartOffset() - 1;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        if (index < 0 || text.charAt(index) != '.') {
            return "";
        }
        index--;
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) {
            index--;
        }
        int end = index + 1;
        while (index >= 0 && Character.isJavaIdentifierPart(text.charAt(index))) {
            index--;
        }
        return text.substring(index + 1, end);
    }

    private static @Nullable Identifier findClass(@NotNull Project project, @NotNull String name) {
        PsiElement classDecl = findClassLikeDecl(project, name);
        return classLikeIdentifier(classDecl);
    }

    private static @Nullable PsiElement findClassLikeDecl(@NotNull Project project, @NotNull String name) {
        List<PsiElement> matches = new ArrayList<>();
        for (AffogatoFile file : affogatoFiles(project)) {
            for (ClassDecl classDecl : PsiTreeUtil.findChildrenOfType(file, ClassDecl.class)) {
                if (classDecl.getIdentifier().getText().equals(simpleTypeName(name))) {
                    matches.add(classDecl);
                }
            }
            for (RecordDecl recordDecl : PsiTreeUtil.findChildrenOfType(file, RecordDecl.class)) {
                if (recordDecl.getIdentifier().getText().equals(simpleTypeName(name))) {
                    matches.add(recordDecl);
                }
            }
            for (EnumDecl enumDecl : PsiTreeUtil.findChildrenOfType(file, EnumDecl.class)) {
                if (enumDecl.getIdentifier().getText().equals(simpleTypeName(name))) {
                    matches.add(enumDecl);
                }
            }
            for (InterfaceDecl interfaceDecl : PsiTreeUtil.findChildrenOfType(file, InterfaceDecl.class)) {
                if (interfaceDecl.getIdentifier().getText().equals(simpleTypeName(name))) {
                    matches.add(interfaceDecl);
                }
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
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

    private static List<AffogatoFile> affogatoFiles(@NotNull Project project) {
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

    private static @Nullable Identifier findParameterInScope(PsiElement place, String name) {
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

    private static @Nullable Identifier findParameter(ParameterList parameterList, String name) {
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

    private static @Nullable Identifier findCurrentClassField(PsiElement place, String name) {
        ClassDecl classDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classDecl != null) {
            return findUniqueField(classDecl, name);
        }
        RecordDecl recordDecl = PsiTreeUtil.getParentOfType(place, RecordDecl.class);
        return recordDecl == null ? null : findUniqueField(recordDecl, name);
    }

    private static @Nullable Identifier findCurrentClassMethod(PsiElement place, String name) {
        ClassDecl classDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classDecl != null) {
            return findUniqueMethod(classDecl, name);
        }
        RecordDecl recordDecl = PsiTreeUtil.getParentOfType(place, RecordDecl.class);
        return recordDecl == null ? null : findUniqueMethod(recordDecl, name);
    }

    private static @Nullable Identifier findUniqueField(PsiElement classLikeDecl, String name) {
        List<Identifier> matches = new ArrayList<>();
        ClassBody classBody = classBody(classLikeDecl);
        if (classBody != null) {
            for (ClassMember member : classBody.getClassMemberList()) {
                FieldDecl fieldDecl = member.getFieldDecl();
                if (fieldDecl != null && fieldDecl.getIdentifier().getText().equals(name)) {
                    matches.add(fieldDecl.getIdentifier());
                }
            }
        }
        ParameterList parameterList = constructorParameterList(classLikeDecl);
        if (parameterList != null) {
            for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                if (isPropertyParameter(parameter) && parameter.getIdentifier().getText().equals(name)) {
                    matches.add(parameter.getIdentifier());
                }
            }
        }
        if (classLikeDecl instanceof RecordDecl recordDecl) {
            parameterList = recordDecl.getRecordHeader().getParameterList();
            if (parameterList != null) {
                for (Parameter parameter : PsiTreeUtil.findChildrenOfType(parameterList, Parameter.class)) {
                    if (parameter.getIdentifier().getText().equals(name)) {
                        matches.add(parameter.getIdentifier());
                    }
                }
            }
        }
        return unique(matches);
    }

    private static @Nullable Identifier findUniqueMethod(PsiElement classLikeDecl, String name) {
        List<Identifier> matches = new ArrayList<>();
        ClassBody classBody = classBody(classLikeDecl);
        if (classBody != null) {
            for (ClassMember member : classBody.getClassMemberList()) {
                MethodDecl methodDecl = member.getMethodDecl();
                if (methodDecl != null && methodDecl.getMethodSignature().getIdentifier().getText().equals(name)) {
                    matches.add(methodDecl.getMethodSignature().getIdentifier());
                }
            }
        }
        return unique(matches);
    }

    private static @Nullable ClassBody classBody(PsiElement classLikeDecl) {
        if (classLikeDecl instanceof ClassDecl classDecl) {
            return classDecl.getClassBody();
        }
        if (classLikeDecl instanceof RecordDecl recordDecl) {
            return recordDecl.getClassBody();
        }
        return null;
    }

    private static @Nullable ParameterList constructorParameterList(PsiElement classLikeDecl) {
        if (classLikeDecl instanceof ClassDecl classDecl && classDecl.getCompactConstructor() != null) {
            return classDecl.getCompactConstructor().getParameterList();
        }
        return null;
    }

    private static boolean isPropertyParameter(Parameter parameter) {
        VariableKind variableKind = parameter.getVariableKind();
        return variableKind != null && (variableKind.getText().equals("var") || variableKind.getText().equals("let"));
    }

    private static @Nullable Identifier findUniqueProjectField(Project project, String name) {
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

    private static @Nullable Identifier findUniqueProjectMethod(Project project, String name) {
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

    private static String resolveOwnerType(PsiElement place, String ownerName) {
        Identifier parameter = findParameterInScope(place, ownerName);
        if (parameter != null && parameter.getParent() instanceof Parameter parameterDecl) {
            return simpleTypeName(parameterDecl.getTypeRef().getText());
        }

        PsiElement classLikeDecl = PsiTreeUtil.getParentOfType(place, ClassDecl.class);
        if (classLikeDecl == null) {
            classLikeDecl = PsiTreeUtil.getParentOfType(place, RecordDecl.class);
        }
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

    private static String declaredFieldType(FieldDecl fieldDecl) {
        TypeRef typeRef = fieldDecl.getTypeRef();
        if (typeRef != null) {
            return simpleTypeName(typeRef.getText());
        }
        Expression expression = fieldDecl.getExpression();
        return expression == null ? "" : inferTypeFromInitializer(expression.getText());
    }

    private static @Nullable String inferLocalTypeBefore(PsiElement place, String ownerName) {
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

    private static String inferTypeFromInitializer(String expression) {
        Matcher matcher = Pattern.compile("^(?:new\\s+)?([A-Z][A-Za-z0-9_.$]*)\\s*(?:<[^>]+>)?\\s*\\(").matcher(expression.trim());
        return matcher.find() ? simpleTypeName(matcher.group(1)) : "";
    }

    private static @Nullable Identifier unique(List<Identifier> identifiers) {
        return identifiers.size() == 1 ? identifiers.get(0) : null;
    }

    private static String simpleTypeName(String typeName) {
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
}
