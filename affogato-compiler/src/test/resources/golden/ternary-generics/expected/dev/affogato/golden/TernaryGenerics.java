package dev.affogato.golden;

import java.util.List;

public class TernaryGenerics {
    public List<String> choose(boolean flag, List<String> first, List<String> second) {
        return flag ? first : second;
    }

    public <T> T chooseValue(boolean flag, T first, T second) {
        return flag ? first : second;
    }

}
