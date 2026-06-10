package dev.affogato.intellij.psi;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import dev.affogato.intellij.project.AffogatoJavaIndex;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AffogatoImports {
    public record ImportEntry(@NotNull String qualifiedName, boolean isStatic, boolean isWildcard) {
        public @NotNull String packageName() {
            if (isWildcard) {
                return qualifiedName.endsWith(".*")
                        ? qualifiedName.substring(0, qualifiedName.length() - 2)
                        : qualifiedName;
            }
            int dot = qualifiedName.lastIndexOf('.');
            return dot >= 0 ? qualifiedName.substring(0, dot) : "";
        }

        public @NotNull String simpleName() {
            if (isWildcard) {
                return "";
            }
            int dot = qualifiedName.lastIndexOf('.');
            return dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
        }
    }

    private AffogatoImports() {
    }

    public static @NotNull String qualifiedName(@NotNull AffogatoSymbols.TopLevelType type) {
        if (type.packageName().isBlank()) {
            return type.identifier().getText();
        }
        return type.packageName() + "." + type.identifier().getText();
    }

    public static @NotNull List<ImportEntry> importEntries(@NotNull AffogatoFile file) {
        List<ImportEntry> imports = new ArrayList<>();
        for (ImportDecl importDecl : PsiTreeUtil.findChildrenOfType(file, ImportDecl.class)) {
            String text = importDecl.getText().trim();
            boolean isStatic = text.contains("import static");
            boolean isWildcard = text.contains(".*");
            QualifiedName qualifiedName = importDecl.getQualifiedName();
            imports.add(new ImportEntry(qualifiedName.getText(), isStatic, isWildcard));
        }
        return imports;
    }

    public static boolean isAccessibleWithoutImport(@NotNull AffogatoFile file, @NotNull String qualifiedName) {
        String filePackage = AffogatoSymbols.packageName(file);
        int dot = qualifiedName.lastIndexOf('.');
        String typePackage = dot >= 0 ? qualifiedName.substring(0, dot) : "";
        if (!filePackage.isBlank() && filePackage.equals(typePackage)) {
            return true;
        }
        String simpleName = dot >= 0 ? qualifiedName.substring(dot + 1) : qualifiedName;
        return isImported(file, simpleName, qualifiedName);
    }

    public static boolean isImported(@NotNull AffogatoFile file, @NotNull String simpleName, @NotNull String qualifiedName) {
        for (ImportEntry entry : importEntries(file)) {
            if (entry.isWildcard() && qualifiedName.startsWith(entry.packageName() + ".")) {
                return true;
            }
            if (!entry.isWildcard() && entry.qualifiedName().equals(qualifiedName)) {
                return true;
            }
            if (!entry.isWildcard() && entry.simpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable PsiElement findImportedOrSamePackageType(
            @NotNull AffogatoFile file,
            @NotNull Project project,
            @NotNull String simpleName
    ) {
        String normalized = AffogatoSymbols.simpleTypeName(simpleName);
        String filePackage = AffogatoSymbols.packageName(file);

        GlobalSearchScope scope = AffogatoJavaIndex.scopeFor(file);
        for (ImportEntry entry : importEntries(file)) {
            if (entry.isWildcard()) {
                PsiClass javaClass = AffogatoJavaIndex.findClassByQualifiedName(
                        project,
                        scope,
                        entry.packageName() + "." + normalized
                );
                if (javaClass != null) {
                    return javaClass;
                }
                for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
                    if (type.identifier().getText().equals(normalized) && type.packageName().equals(entry.packageName())) {
                        return type.declaration();
                    }
                }
                continue;
            }
            if (entry.simpleName().equals(normalized)) {
                PsiElement resolved = findTypeByQualifiedName(project, entry.qualifiedName());
                if (resolved != null) {
                    return resolved;
                }
            }
        }

        List<PsiElement> samePackage = new ArrayList<>();
        for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
            if (type.identifier().getText().equals(normalized) && type.packageName().equals(filePackage)) {
                samePackage.add(type.declaration());
            }
        }
        if (samePackage.size() == 1) {
            return samePackage.get(0);
        }
        return null;
    }

    public static @Nullable PsiElement findTypeByQualifiedName(@NotNull Project project, @NotNull String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String packageName = qualifiedName.substring(0, dot);
        String simpleName = qualifiedName.substring(dot + 1);
        for (AffogatoSymbols.TopLevelType type : AffogatoSymbols.allTopLevelTypes(project)) {
            if (type.packageName().equals(packageName) && type.identifier().getText().equals(simpleName)) {
                return type.declaration();
            }
        }
        PsiClass javaClass = AffogatoJavaIndex.findClassByQualifiedName(
                project,
                GlobalSearchScope.allScope(project),
                qualifiedName
        );
        return javaClass;
    }

    public static void addImport(@NotNull AffogatoFile file, @NotNull String qualifiedName) {
        if (isAccessibleWithoutImport(file, qualifiedName)) {
            return;
        }
        String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        if (isImported(file, simpleName, qualifiedName)) {
            return;
        }

        WriteCommandAction.runWriteCommandAction(file.getProject(), () -> {
            Document document = PsiDocumentManager.getInstance(file.getProject()).getDocument(file);
            if (document == null) {
                return;
            }
            int offset = importInsertOffset(file);
            String insertion = (offset > 0 && document.getTextLength() > 0 && document.getText().charAt(offset - 1) != '\n'
                    ? "\n"
                    : "")
                    + "import " + qualifiedName + "\n";
            document.insertString(offset, insertion);
            PsiDocumentManager.getInstance(file.getProject()).commitDocument(document);
        });
    }

    private static int importInsertOffset(@NotNull AffogatoFile file) {
        ImportDecl[] imports = PsiTreeUtil.getChildrenOfType(file, ImportDecl.class);
        if (imports != null && imports.length > 0) {
            return imports[imports.length - 1].getTextRange().getEndOffset();
        }
        PackageDecl packageDecl = PsiTreeUtil.findChildOfType(file, PackageDecl.class);
        if (packageDecl != null) {
            return packageDecl.getTextRange().getEndOffset();
        }
        return 0;
    }
}
