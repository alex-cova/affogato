package dev.affogato.golden;

public class UserRecordHelpers {
    public static String label(UserRecord user) {
        return user.name() + ":" + user.age();
    }

}
