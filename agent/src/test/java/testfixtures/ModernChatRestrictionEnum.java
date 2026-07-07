package testfixtures;

// Mirrors the real 26.1+ chat-restriction enum's constant-name shape: DISABLED_BY_PROFILE and
// DISABLED_BY_LAUNCHER both present, no ENABLED constant (26.1+ tracks "reasons," not a state).
public enum ModernChatRestrictionEnum {
    DISABLED_BY_OPTIONS,
    DISABLED_BY_PROFILE,
    DISABLED_BY_LAUNCHER
}
