package dev.affogato.golden;

public class BlockLocalShadowing {
    public String run() {
        final BlockShadowBox box = new BlockShadowBox("field");
        final String param = box.paramShadow("param");
        final String local = box.describe("input");
        return param + ":" + local;
    }

}
