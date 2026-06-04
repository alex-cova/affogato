package dev.affogato.golden;

import dev.affogato.golden.interop.JavaNamed;
import dev.affogato.golden.interop.JavaNamedImpl;

public class JavaInheritedDefaultMethods {
    public String run() {
        final JavaNamed named = new JavaNamedImpl("bean");
        return named.label();
    }

}
