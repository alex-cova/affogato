package dev.affogato.golden;

public class BlockShadowBox {
    private String label;

    public BlockShadowBox(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String value) {
        this.label = value;
    }

    public String paramShadow(String label) {
        return this.label + ":" + label;
    }

    public String describe(String input) {
        final String before = this.label + ":" + input;
        {
            final String label = "local-field";
            {
                final String nestedLabel = "nested-local";
                final String inside = label + ":" + nestedLabel + ":" + this.label;
                System.out.println(inside);
            }
            System.out.println(label);
        }
        return before + ":" + this.label + ":" + input;
    }

}
