package dev.affogato.golden;

public class PrivateFieldBox {
    private String secret = "initial";

    public String getSecret() {
        return secret;
    }

    public void setSecret(String value) {
        this.secret = value;
    }

    public String reveal() {
        return secret;
    }

}
