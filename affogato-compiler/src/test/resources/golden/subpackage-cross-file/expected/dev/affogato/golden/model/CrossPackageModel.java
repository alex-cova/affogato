package dev.affogato.golden.model;

public class CrossPackageModel {
    private String name;

    public CrossPackageModel(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String label() {
        return "model:" + name;
    }

}
