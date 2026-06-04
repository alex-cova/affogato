package dev.affogato.golden;

public class BlockLabelLikeNames {
    public String run() {
        final String caseValue = "case";
        {
            final String defaultValue = "default";
            final String guardValue = "guard";
            final String switchValue = caseValue + ":" + defaultValue + ":" + guardValue;
            System.out.println(switchValue);
        }
        final String returnValue = "return";
        return caseValue + ":" + returnValue;
    }

}
