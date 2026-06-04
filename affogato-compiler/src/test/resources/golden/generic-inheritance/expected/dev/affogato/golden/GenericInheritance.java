package dev.affogato.golden;

public class GenericInheritance {
    public String run() {
        final Holder<String> holder = new StringHolder();
        return holder.echo("latte");
    }

}
