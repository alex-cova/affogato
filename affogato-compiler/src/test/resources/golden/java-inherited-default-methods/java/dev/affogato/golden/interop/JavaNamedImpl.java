package dev.affogato.golden.interop;

public final class JavaNamedImpl implements JavaNamed {
    private final String name;

    public JavaNamedImpl(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }
}
