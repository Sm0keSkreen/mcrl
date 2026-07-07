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
        File configFile = locateConfigFile();
        if (configFile != null && configFile.isFile()) {
            try {
                String json = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.UTF_8);
                boolean extras = readBoolean(json, "extras", false);
                boolean allowTelemetry = readBoolean(json, "allowTelemetry", false);
                boolean allowProfanityFilter = readBoolean(json, "allowProfanityFilter", false);
                return new McrlConfig(extras, !allowTelemetry, !allowProfanityFilter);
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

    private static boolean readBoolean(String json, String key, boolean defaultValue) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : defaultValue;
    }
}
