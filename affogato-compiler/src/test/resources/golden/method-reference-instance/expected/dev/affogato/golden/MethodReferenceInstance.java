package dev.affogato.golden;

import java.util.function.Supplier;

public class MethodReferenceInstance {
    public String run() {
        final StringBuilder value = new StringBuilder(" affogato ");
        final Supplier supplier = value::toString;
        final java.lang.String text = supplier.get().toString();
        return text.trim();
    }

}
