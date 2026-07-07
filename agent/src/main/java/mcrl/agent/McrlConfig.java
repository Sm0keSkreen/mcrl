package mcrl.agent;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads config.json next to the agent's own jar; absent file means "do nothing new" so an
// existing install upgrading its jar in place doesn't silently start behaving differently.
final class McrlConfig {

    final boolean extras;
    final boolean blockTelemetry;
    final boolean blockProfanityFilter;

    private McrlConfig(boolean extras, boolean blockTelemetry, boolean blockProfanityFilter) {
        this.extras = extras;
        this.blockTelemetry = blockTelemetry;
        this.blockProfanityFilter = blockProfanityFilter;
    }

    static McrlConfig load(String legacyAgentArgs) {
        return load(legacyAgentArgs, locateConfigFile());
    }

    // Split out from load(String) so tests can point at an arbitrary config.json without needing
    // to control where McrlConfig.class's own jar/classes appear to live on disk.
    static McrlConfig load(String legacyAgentArgs, File configFile) {
        if (configFile != null && configFile.isFile()) {
            try {
                String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                boolean extras = Boolean.TRUE.equals(readBoolean(json, "extras"));
                // A missing allow* key means "leave alone" (same as no config.json at all), not
                // "block"; only an explicit false actively strips that flag if the account has it.
                Boolean allowTelemetry = readBoolean(json, "allowTelemetry");
                Boolean allowProfanityFilter = readBoolean(json, "allowProfanityFilter");
                boolean blockTelemetry = Boolean.FALSE.equals(allowTelemetry);
                boolean blockProfanityFilter = Boolean.FALSE.equals(allowProfanityFilter);
                return new McrlConfig(extras, blockTelemetry, blockProfanityFilter);
            } catch (Throwable t) {
                System.err.println("[mcrl] failed to read " + configFile + ", ignoring it");
            }
        }
        return new McrlConfig(hasLegacyFeature(legacyAgentArgs, "extras"), false, false);
    }

    // Pre-config-file installs used -javaagent:mcrl.jar=extras; still honored if no config.json exists.
    private static boolean hasLegacyFeature(String agentArgs, String feature) {
        if (agentArgs == null || agentArgs.isEmpty()) {
            return false;
        }
        return Arrays.asList(agentArgs.split(",")).contains(feature);
    }

    private static File locateConfigFile() {
        try {
            File jarFile = new File(McrlConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return new File(jarFile.getParentFile(), "config.json");
        } catch (Throwable t) {
            return null;
        }
    }

    // null means the key is absent, distinct from an explicit false.
    private static Boolean readBoolean(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : null;
    }
}
