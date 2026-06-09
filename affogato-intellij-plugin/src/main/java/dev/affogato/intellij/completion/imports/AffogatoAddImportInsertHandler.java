package dev.affogato.intellij.completion.imports;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;
import dev.affogato.intellij.psi.AffogatoFile;
import dev.affogato.intellij.psi.AffogatoImports;
import org.jetbrains.annotations.NotNull;

public final class AffogatoAddImportInsertHandler implements com.intellij.codeInsight.completion.InsertHandler<LookupElement> {
    public static final Key<String> IMPORT_FQCN = Key.create("AFFOGATO_IMPORT_FQCN");

    public static final AffogatoAddImportInsertHandler INSTANCE = new AffogatoAddImportInsertHandler();

    private AffogatoAddImportInsertHandler() {
    }

    @Override
    public void handleInsert(@NotNull InsertionContext context, @NotNull LookupElement item) {
        String qualifiedName = item.getUserData(IMPORT_FQCN);
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return;
        }
        PsiFile file = context.getFile();
        if (!(file instanceof AffogatoFile affogatoFile)) {
            return;
        }
        if (AffogatoImports.isAccessibleWithoutImport(affogatoFile, qualifiedName)) {
            return;
        }
        AffogatoImports.addImport(affogatoFile, qualifiedName);
    }

    public static @NotNull LookupElement withAutoImport(@NotNull LookupElementBuilder builder, @NotNull String qualifiedName) {
        return builder.withInsertHandler((context, item) -> {
            PsiFile file = context.getFile();
            if (!(file instanceof AffogatoFile affogatoFile)) {
                return;
            }
            if (AffogatoImports.isAccessibleWithoutImport(affogatoFile, qualifiedName)) {
                return;
            }
            AffogatoImports.addImport(affogatoFile, qualifiedName);
        });
    }
}
