package dev.affogato.intellij.psi;

import com.intellij.psi.tree.IElementType;
import dev.affogato.intellij.AffogatoLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class AffogatoElementType extends IElementType {
    public AffogatoElementType(@NotNull @NonNls String debugName) {
        super(debugName, AffogatoLanguage.INSTANCE);
    }
}
