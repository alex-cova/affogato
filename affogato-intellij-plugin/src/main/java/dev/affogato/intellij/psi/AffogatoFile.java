package dev.affogato.intellij.psi;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.psi.FileViewProvider;
import dev.affogato.intellij.AffogatoFileType;
import dev.affogato.intellij.AffogatoLanguage;
import org.jetbrains.annotations.NotNull;

public final class AffogatoFile extends PsiFileBase {
    public AffogatoFile(@NotNull FileViewProvider viewProvider) {
        super(viewProvider, AffogatoLanguage.INSTANCE);
    }

    @Override
    public @NotNull AffogatoFileType getFileType() {
        return AffogatoFileType.INSTANCE;
    }

    @Override
    public String toString() {
        return "Affogato File";
    }
}
