package dev.affogato.golden;

class PackageVisibleJavaBox {
    String label;

    PackageVisibleJavaBox(String label) {
        this.label = label;
    }

    String reveal() {
        return label;
    }
}
