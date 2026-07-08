package mcrl.agent;

import java.lang.instrument.Instrumentation;

// Mcrl: lifts the client-side chat-restriction check.
public final class McrlAgent {

    // How long to wait before warning that this version/loader's chat-restriction shape was never
    // found; a guess balancing "warn promptly" against slow-loading modpacks/older hardware.
    private static final long SELF_CHECK_DELAY_MS = 90_000;

    private McrlAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // JDK_JAVA_OPTIONS is global, so this loads into every Java process on the system; stay
        // quiet for anything that doesn't look like Minecraft instead of spamming unrelated logs.
        boolean verbose = looksLikeMinecraft();
        if (verbose) {
            System.out.println("[mcrl] installed, scanning for the client chat-restriction enum");
        }
        ChatRestrictionTransformer chatRestrictionTransformer = new ChatRestrictionTransformer(verbose);
        // retransformClasses() is never used, so false is fine here.
        inst.addTransformer(chatRestrictionTransformer, false);
        if (verbose) {
            scheduleUnsupportedVersionCheck(chatRestrictionTransformer);
            UpdateChecker.checkInBackground();
        }

        McrlConfig config = McrlConfig.load(agentArgs);
        if (config.extras || config.blockTelemetry || config.blockProfanityFilter) {
            inst.addTransformer(new AccountFlagsTransformer(config.extras, config.blockTelemetry,
                    config.blockProfanityFilter, verbose), false);
        }
        // Same "extras" toggle as Realms/servers/friends: a locally-flagged forced-name-change or
        // banned-skin action otherwise blocks connecting to third-party servers over nothing they
        // enforce themselves.
        if (config.extras) {
            inst.addTransformer(new ProfileActionsTransformer(verbose), false);
        }
    }

    // Fires once, well after a real game should have loaded the chat-restriction enum; if it
    // never showed up, this version/loader's bytecode shape likely isn't one we recognize yet, so
    // say so instead of leaving the user to wonder why chat is still blocked.
    private static void scheduleUnsupportedVersionCheck(ChatRestrictionTransformer transformer) {
        Thread checker = new Thread(() -> {
            try {
                Thread.sleep(SELF_CHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!transformer.shapeFound()) {
                System.err.println("[mcrl] didn't recognize this Minecraft version/loader's "
                        + "chat-restriction code after " + (SELF_CHECK_DELAY_MS / 1000) + "s; chat may "
                        + "still be restricted. This version may not be supported yet; if chat is still "
                        + "blocked, please open an issue at https://github.com/Sm0keSkreen/mcrl/issues");
            }
        }, "mcrl-self-check");
        checker.setDaemon(true);
        checker.start();
    }

    private static boolean looksLikeMinecraft() {
        String classPath = System.getProperty("java.class.path", "");
        String command = System.getProperty("sun.java.command", "");
        return classPath.toLowerCase().contains("minecraft") || command.toLowerCase().contains("minecraft");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
