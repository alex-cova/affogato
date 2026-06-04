package dev.affogato.golden.service;

import dev.affogato.golden.model.CrossPackageModel;

public class CrossPackageService {
    public CrossPackageModel build(String name) {
        return new CrossPackageModel(name);
    }

}
