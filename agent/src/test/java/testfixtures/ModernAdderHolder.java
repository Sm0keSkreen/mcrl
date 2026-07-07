package testfixtures;

import java.util.ArrayList;
import java.util.List;

// Fluent-builder "adder": non-static, one enum param, returns its own declaring class. The patch
// should make DISABLED_BY_PROFILE a no-op while leaving every other reason recorded normally, so
// this single method is exercised twice (once per reason) rather than needing two fixtures.
public class ModernAdderHolder {
    private final List<ModernChatRestrictionEnum> reasons = new ArrayList<>();

    public ModernAdderHolder addRestriction(ModernChatRestrictionEnum reason) {
        reasons.add(reason);
        return this;
    }

    public List<ModernChatRestrictionEnum> getReasons() {
        return reasons;
    }
}
