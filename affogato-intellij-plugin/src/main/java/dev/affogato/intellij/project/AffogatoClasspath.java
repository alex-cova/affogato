package dev.affogato.intellij.project;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AffogatoClasspath {
    public record ModuleInfo(@NotNull Path filePath, @NotNull Path sourceRoot, @NotNull List<Path> libraryPaths) {}

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");

    private AffogatoClasspath() {
    }

    public static @Nullable ModuleInfo moduleInfo(@NotNull PsiFile file) {
        VirtualFile virtualFile = file.getVirtualFile();
        if (virtualFile == null) {
            return null;
        }
        Path filePath = Path.of(virtualFile.getPath());
        String packageName = extractPackage(file.getText());
        Path sourceRoot = affogatoSourceRoot(filePath, packageName);
        if (sourceRoot == null) {
            return null;
        }
        return new ModuleInfo(filePath, sourceRoot, libraryPaths(file.getProject(), virtualFile));
    }

    public static @NotNull List<Path> libraryPaths(@NotNull Project project, @Nullable VirtualFile file) {
        List<Path> classpath = new ArrayList<>();
        if (file == null) {
            return classpath;
        }
        Module module = ModuleUtilCore.findModuleForFile(file, project);
        if (module != null) {
            for (String entry : ModuleRootManager.getInstance(module).orderEntries().librariesOnly().getPathsList().getPathList()) {
                classpath.add(Path.of(entry));
            }
        }
        return classpath;
    }

    public static @Nullable Path affogatoSourceRoot(@NotNull Path filePath, @Nullable String packageName) {
        Path dir = filePath.getParent();
        if (dir == null) {
            return null;
        }
        if (packageName == null || packageName.isBlank()) {
            return dir;
        }
        String[] parts = packageName.split("\\.");
        Path root = dir;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (root == null || !root.getFileName().toString().equals(parts[i])) {
                return dir;
            }
            root = root.getParent();
        }
        return root != null ? root : dir;
    }

    public static @Nullable String extractPackage(@NotNull String source) {
        Matcher matcher = PACKAGE_PATTERN.matcher(source);
        return matcher.find() ? matcher.group(1) : null;
    }
}
