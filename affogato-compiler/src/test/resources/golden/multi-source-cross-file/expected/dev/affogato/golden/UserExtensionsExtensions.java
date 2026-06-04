package dev.affogato.golden;

public final class UserExtensionsExtensions {
    public static String label(CrossUser $this, String prefix) {
        return prefix + ":" + $this.getName() + ":" + $this.getAge();
    }

}
