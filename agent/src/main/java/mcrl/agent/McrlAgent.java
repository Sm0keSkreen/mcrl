package mcrl.agent;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.List;

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
        if (hasFeature(agentArgs, "extras")) {
            inst.addTransformer(new AccountFlagsTransformer(verbose), false);
        }
    }

    private static boolean looksLikeMinecraft() {
        String classPath = System.getProperty("java.class.path", "");
        String command = System.getProperty("sun.java.command", "");
        return classPath.toLowerCase().contains("minecraft") || command.toLowerCase().contains("minecraft");
    }

    // agentArgs is the comma-separated =options suffix on -javaagent:mcrl.jar=extras, absent by default.
    private static boolean hasFeature(String agentArgs, String feature) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return false;
        }
        List<String> features = Arrays.asList(agentArgs.split(","));
        return features.contains(feature);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
