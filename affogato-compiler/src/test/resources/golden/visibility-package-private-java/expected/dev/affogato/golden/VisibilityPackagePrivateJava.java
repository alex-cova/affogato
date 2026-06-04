package dev.affogato.golden;

public class VisibilityPackagePrivateJava {
    public String run() {
        final PackageVisibleJavaBox box = new PackageVisibleJavaBox("java");
        box.label = box.label + "-field";
        return box.reveal();
    }

}
