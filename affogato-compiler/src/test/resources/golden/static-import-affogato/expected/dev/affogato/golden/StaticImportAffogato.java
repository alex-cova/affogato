package dev.affogato.golden;

import static dev.affogato.golden.StaticImportSource.echo;

public class StaticImportAffogato {
    public String run() {
        return StaticImportSource.echo("imported");
    }

}
