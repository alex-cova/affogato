package dev.affogato.intellij;

import com.intellij.openapi.fileTypes.LanguageFileType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

public final class AffogatoFileType extends LanguageFileType {
    public static final AffogatoFileType INSTANCE = new AffogatoFileType();

    private AffogatoFileType() {
        super(AffogatoLanguage.INSTANCE);
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "Affogato";
    }

    @Override
    public @NotNull String getDescription() {
        return "Affogato source file";
    }

    @Override
    public @NotNull String getDefaultExtension() {
        return "aff";
    }

    @Override
    public @NotNull Icon getIcon() {
        return AffogatoIcons.FILE;
    }
}
