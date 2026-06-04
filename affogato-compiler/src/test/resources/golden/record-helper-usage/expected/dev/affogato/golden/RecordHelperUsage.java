package dev.affogato.golden;

public class RecordHelperUsage {
    public String run() {
        final UserRecord user = new UserRecord("Ada", 42);
        return UserRecordHelpers.label(user);
    }

}
