package dev.affogato.intellij;

import com.intellij.lang.Language;

public final class AffogatoLanguage extends Language {
    public static final AffogatoLanguage INSTANCE = new AffogatoLanguage();

    private AffogatoLanguage() {
        super("Affogato");
    }
}
