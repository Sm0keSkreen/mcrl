package mcrl.agent;

import java.lang.instrument.Instrumentation;

/**
 * Mcrl, Minecraft Chat Restrictions Lifted.
 *
 * A JVM agent, not a mod: it attaches below whatever mod loader (or no loader at
 * all, vanilla) is running, so the same jar works across Forge, NeoForge, Fabric,
 * Quilt, and vanilla without a rebuild per loader.
 */
public final class McrlAgent {

    private McrlAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[mcrl] installed, scanning for the client chat-restriction enum");
        // false: retransformClasses() is never used (see ChatRestrictionTransformer's
        // class doc for why), so retransform support isn't needed.
        inst.addTransformer(new ChatRestrictionTransformer(), false);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        premain(agentArgs, inst);
    }
}
