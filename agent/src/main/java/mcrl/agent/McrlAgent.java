package mcrl.agent;

import java.lang.instrument.Instrumentation;

// Mcrl: lifts the client-side chat-restriction check.
public final class McrlAgent {

    private McrlAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // JDK_JAVA_OPTIONS is global, so this loads into every Java process on the system; stay
        // quiet for anything that doesn't look like Minecraft instead of spamming unrelated logs.
        boolean verbose = looksLikeMinecraft();
        if (verbose) {
            System.out.println("[mcrl] installed, scanning for the client chat-restriction enum");
        }
        // retransformClasses() is never used, so false is fine here.
        inst.addTransformer(new ChatRestrictionTransformer(verbose), false);

        McrlConfig config = McrlConfig.load(agentArgs);
        if (config.extras || config.blockTelemetry || config.blockProfanityFilter) {
            inst.addTransformer(new AccountFlagsTransformer(config.extras, config.blockTelemetry,
                    config.blockProfanityFilter, verbose), false);
        }
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
