package testfixtures;

// Zero-arg getter returning the Microsoft/Xbox-account restriction reason; this is the one the
// patch should swap to ENABLED.
public class LegacyGetterHolderProfile {
    public LegacyChatRestrictionEnum getRestriction() {
        return LegacyChatRestrictionEnum.DISABLED_BY_PROFILE;
    }
}
