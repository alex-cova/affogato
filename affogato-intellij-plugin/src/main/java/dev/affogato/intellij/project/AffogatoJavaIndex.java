package dev.affogato.intellij.project;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoImports;
import dev.affogato.intellij.psi.AffogatoSymbols;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.intellij.psi.CommonClassNames.JAVA_UTIL_LIST;
import static com.intellij.psi.CommonClassNames.JAVA_UTIL_MAP;

public final class AffogatoJavaIndex {
    private AffogatoJavaIndex() {
    }

    public static @NotNull GlobalSearchScope scopeFor(@NotNull PsiFile file) {
        Project project = file.getProject();
        PsiFile originalFile = file.getOriginalFile();
        if (originalFile != null) {
            file = originalFile;
        }
        GlobalSearchScope projectScope = GlobalSearchScope.projectScope(project);
        if (file.getVirtualFile() == null) {
            return projectScope;
        }
        Module module = ModuleUtilCore.findModuleForFile(file.getVirtualFile(), project);
        if (module != null) {
            return projectScope.union(GlobalSearchScope.moduleWithDependenciesAndLibrariesScope(module));
        }
        return projectScope;
    }

    public static @NotNull List<PsiClass> classesByName(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String simpleName
    ) {
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(simpleName, scope);
        List<PsiClass> result = new ArrayList<>();
        for (PsiClass psiClass : classes) {
            if (psiClass.getQualifiedName() != null) {
                result.add(psiClass);
            }
        }
        return result;
    }

    public static @NotNull List<PsiClass> classesMatchingPrefix(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String packagePrefix,
            @NotNull String classPrefix
    ) {
        LinkedHashSet<PsiClass> classes = new LinkedHashSet<>();
        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        for (String className : cache.getAllClassNames()) {
            if (!classPrefix.isBlank() && !className.startsWith(classPrefix)) {
                continue;
            }
            for (PsiClass psiClass : cache.getClassesByName(className, scope)) {
                String qualifiedName = psiClass.getQualifiedName();
                if (qualifiedName == null) {
                    continue;
                }
                if (!packagePrefix.isBlank()) {
                    if (!qualifiedName.startsWith(packagePrefix + ".")) {
                        continue;
                    }
                    String remainder = qualifiedName.substring(packagePrefix.length() + 1);
                    if (remainder.contains(".")) {
                        continue;
                    }
                }
                classes.add(psiClass);
                break;
            }
        }
        addProjectSourceClasses(project, scope, packagePrefix, classPrefix, classes);
        if (classes.isEmpty()) {
            addFallbackClasses(project, scope, packagePrefix, classPrefix, classes);
        }
        return List.copyOf(classes);
    }

    public static @Nullable PsiClass resolveClass(
            @NotNull PsiElement context,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String typeName
    ) {
        if (typeName.isBlank()) {
            return null;
        }
        if (typeName.contains(".")) {
            return JavaPsiFacade.getInstance(project).findClass(typeName, scope);
        }
        PsiFile file = context.getContainingFile();
        if (file instanceof AffogatoFile affogatoFile) {
            PsiClass imported = findImportedClass(affogatoFile, project, scope, typeName);
            if (imported != null) {
                return imported;
            }
        }
        String simpleName = AffogatoSymbols.simpleTypeName(typeName);
        List<PsiClass> candidates = classesByName(project, scope, simpleName);
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        PsiClass javaLang = findClassByQualifiedName(project, scope, "java.lang." + simpleName);
        if (javaLang != null) {
            return javaLang;
        }
        if (candidates.size() > 1) {
            return candidates.get(0);
        }
        return null;
    }

    public static @Nullable PsiClass findImportedClass(
            @NotNull AffogatoFile file,
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String simpleName
    ) {
        String normalized = AffogatoSymbols.simpleTypeName(simpleName);
        for (AffogatoImports.ImportEntry entry : AffogatoImports.importEntries(file)) {
            if (entry.isStatic()) {
                continue;
            }
            if (entry.isWildcard()) {
                PsiClass wildcard = findClassByQualifiedName(project, scope, entry.packageName() + "." + normalized);
                if (wildcard != null) {
                    return wildcard;
                }
                continue;
            }
            if (entry.simpleName().equals(normalized)) {
                PsiClass imported = findClassByQualifiedName(project, scope, entry.qualifiedName());
                if (imported != null) {
                    return imported;
                }
            }
        }
        return null;
    }

    public static @Nullable PsiClass findClassByQualifiedName(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String qualifiedName
    ) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass psiClass = facade.findClass(qualifiedName, scope);
        if (psiClass != null) {
            return psiClass;
        }
        return facade.findClass(qualifiedName, GlobalSearchScope.allScope(project));
    }

    public static boolean isStaticMemberContext(@NotNull PsiElement place, @NotNull String receiver) {
        if (receiver.contains(".")) {
            return false;
        }
        String name = receiver.trim();
        if (name.isEmpty()) {
            return false;
        }
        if (!AffogatoSymbols.resolveOwnerType(place, name).isBlank()) {
            return false;
        }
        if (AffogatoSymbols.findClassLikeDecl(place, name) != null) {
            return true;
        }
        PsiFile file = place.getContainingFile();
        if (!(file instanceof AffogatoFile affogatoFile)) {
            return false;
        }
        GlobalSearchScope scope = scopeFor(file);
        return findImportedClass(affogatoFile, place.getProject(), scope, name) != null
                || JavaPsiFacade.getInstance(place.getProject()).findClass("java.lang." + name, scope) != null;
    }

    public static @NotNull String memberType(@NotNull PsiClass owner, @NotNull String memberName) {
        return AffogatoJavaMembers.memberType(owner, memberName);
    }

    private static void addProjectSourceClasses(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String packagePrefix,
            @NotNull String classPrefix,
            @NotNull Set<PsiClass> classes
    ) {
        if (!packagePrefix.isBlank() && !classPrefix.isBlank()) {
            PsiClass direct = JavaPsiFacade.getInstance(project).findClass(packagePrefix + "." + classPrefix, scope);
            if (direct != null) {
                classes.add(direct);
            }
        }
        if (packagePrefix.isBlank()) {
            return;
        }
        String packagePath = packagePrefix.replace('.', '/');
        Collection<VirtualFile> javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope);
        PsiManager psiManager = PsiManager.getInstance(project);
        for (VirtualFile virtualFile : javaFiles) {
            if (!virtualFile.getPath().contains("/" + packagePath + "/")) {
                continue;
            }
            PsiFile psiFile = psiManager.findFile(virtualFile);
            if (!(psiFile instanceof PsiJavaFile javaFile)) {
                continue;
            }
            if (!packagePrefix.equals(javaFile.getPackageName())) {
                continue;
            }
            for (PsiClass psiClass : javaFile.getClasses()) {
                String name = psiClass.getName();
                if (name == null) {
                    continue;
                }
                if (classPrefix.isBlank() || name.startsWith(classPrefix)) {
                    classes.add(psiClass);
                }
            }
        }
    }

    private static void addFallbackClasses(
            @NotNull Project project,
            @NotNull GlobalSearchScope scope,
            @NotNull String packagePrefix,
            @NotNull String classPrefix,
            @NotNull Set<PsiClass> classes
    ) {
        for (String qualifiedName : List.of(JAVA_UTIL_LIST, JAVA_UTIL_MAP, "java.util.ArrayList", "java.util.Set")) {
            if (!packagePrefix.isBlank() && !qualifiedName.startsWith(packagePrefix + ".")) {
                continue;
            }
            String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
            if (!classPrefix.isBlank() && !simpleName.startsWith(classPrefix)) {
                continue;
            }
            PsiClass psiClass = JavaPsiFacade.getInstance(project).findClass(qualifiedName, scope);
            if (psiClass != null) {
                classes.add(psiClass);
            }
        }
    }
}
