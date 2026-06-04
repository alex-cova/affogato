package dev.affogato.golden;

import dev.affogato.golden.model.CrossPackageModel;
import dev.affogato.golden.service.CrossPackageService;

public class SubpackageCrossFile {
    public String run() {
        final CrossPackageService service = new CrossPackageService();
        final CrossPackageModel model = service.build("affogato");
        return model.label();
    }

}
