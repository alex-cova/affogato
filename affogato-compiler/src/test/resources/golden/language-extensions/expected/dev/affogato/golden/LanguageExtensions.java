package dev.affogato.golden;

public class LanguageExtensions {
    public int run() {
        final int mask = 0xFF;
        assert mask == 255 : "mask must be 255";
        assert 1 << 8 == 256;
        assert 255 >> 2 == 63;
        assert -1 >>> 28 == 15;
        final int[] sized = new int[4];
        sized[0] = mask;
        assert sized.length == 4;
        assert sized[0] == 255;
        final String marker = "\u0041";
        assert marker == "A";
        return mask;
    }

}
