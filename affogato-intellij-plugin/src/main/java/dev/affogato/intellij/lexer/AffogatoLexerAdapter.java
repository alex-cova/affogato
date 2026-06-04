package dev.affogato.intellij.lexer;

import com.intellij.lexer.FlexAdapter;

public final class AffogatoLexerAdapter extends FlexAdapter {
    public AffogatoLexerAdapter() {
        super(new _AffogatoLexer(null));
    }
}
