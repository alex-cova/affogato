package dev.affogato.golden;

public final class ReceiversExtensions {
    public static String greeting(ReceiverPerson $this, String prefix) {
        return prefix + " " + $this.getName();
    }

    public static String describe(ReceiverPerson $this) {
        return $this.getName() + " is " + $this.getAge();
    }

    public static int lengthPlus(CharSequence $this) {
        return $this.length() + 1;
    }

}
