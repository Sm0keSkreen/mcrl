package mcrl.agent;

/**
 * Pure reflection, no Minecraft/Forge/Fabric classes on the compile classpath: matches
 * the returned enum constant by its .name() rather than a hardcoded class reference,
 * since the enum's own class name varies by loader and mapping scheme.
 */
public final class ChatRestrictionFix {

    private ChatRestrictionFix() {
    }

    public static Object fix(Object status) {
        if (!(status instanceof Enum)) {
            return status;
        }
        Enum<?> current = (Enum<?>) status;
        if (!"DISABLED_BY_PROFILE".equals(current.name())) {
            return status;
        }
        for (Object constant : status.getClass().getEnumConstants()) {
            if (constant instanceof Enum && "ENABLED".equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return status;
    }
}
