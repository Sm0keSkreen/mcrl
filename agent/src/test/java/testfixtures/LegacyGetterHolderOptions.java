package testfixtures;

// Zero-arg getter returning a real user/launcher choice, not the account restriction; the patch
// must leave this reason alone since overriding it would ignore the user's own opt-out.
public class LegacyGetterHolderOptions {
    public LegacyChatRestrictionEnum getRestriction() {
        return LegacyChatRestrictionEnum.DISABLED_BY_OPTIONS;
    }
}
