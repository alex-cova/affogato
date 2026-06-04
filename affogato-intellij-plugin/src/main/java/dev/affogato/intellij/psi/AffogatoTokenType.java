package dev.affogato.intellij.psi;

import com.intellij.psi.tree.IElementType;
import dev.affogato.intellij.AffogatoLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class AffogatoTokenType extends IElementType {
    public AffogatoTokenType(@NotNull @NonNls String debugName) {
        super(debugName, AffogatoLanguage.INSTANCE);
    }
}
