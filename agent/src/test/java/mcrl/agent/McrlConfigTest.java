package mcrl.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McrlConfigTest {

    @TempDir
    Path tempDir;

    private File writeConfig(String json) throws IOException {
        File file = tempDir.resolve("config.json").toFile();
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void missingKeysMeanLeaveAlone() throws IOException {
        McrlConfig config = McrlConfig.load(null, writeConfig("{\"extras\": true}"));

        assertTrue(config.extras);
        assertFalse(config.blockTelemetry, "missing allowTelemetry must not block it");
        assertFalse(config.blockProfanityFilter, "missing allowProfanityFilter must not block it");
    }

    @Test
    void explicitFalseBlocks() throws IOException {
        McrlConfig config = McrlConfig.load(null,
                writeConfig("{\"extras\": false, \"allowTelemetry\": false, \"allowProfanityFilter\": false}"));

        assertFalse(config.extras);
        assertTrue(config.blockTelemetry);
        assertTrue(config.blockProfanityFilter);
    }

    @Test
    void explicitTrueDoesNotBlock() throws IOException {
        McrlConfig config = McrlConfig.load(null,
                writeConfig("{\"allowTelemetry\": true, \"allowProfanityFilter\": true}"));

        assertFalse(config.blockTelemetry);
        assertFalse(config.blockProfanityFilter);
    }

    // The Nix packaging's home-manager module generates config.json with builtins.toJSON, which
    // renders an unset nullable option as a literal JSON `null` rather than omitting the key;
    // readBoolean's regex only matches a literal true/false value, so null must behave the same
    // as the key being absent entirely (leave alone), not crash or get treated as false/block.
    @Test
    void explicitJsonNullMeansLeaveAlone() throws IOException {
        McrlConfig config = McrlConfig.load(null,
                writeConfig("{\"extras\": false, \"allowTelemetry\": null, \"allowProfanityFilter\": null}"));

        assertFalse(config.blockTelemetry);
        assertFalse(config.blockProfanityFilter);
    }

    @Test
    void noFileFallsBackToLegacyAgentArgs() {
        File nonExistent = tempDir.resolve("does-not-exist.json").toFile();

        McrlConfig withExtras = McrlConfig.load("extras", nonExistent);
        assertTrue(withExtras.extras);
        assertFalse(withExtras.blockTelemetry);
        assertFalse(withExtras.blockProfanityFilter);

        McrlConfig withoutExtras = McrlConfig.load(null, nonExistent);
        assertFalse(withoutExtras.extras);
    }

    @Test
    void malformedJsonFallsBackWithoutThrowing() throws IOException {
        McrlConfig config = McrlConfig.load("extras", writeConfig("{ not valid json"));

        // readBoolean's regex just won't match anything in garbage input, so this degrades to
        // "extras off, nothing blocked" rather than falling back to the legacy agent arg; either
        // way it must not throw.
        assertFalse(config.blockTelemetry);
        assertFalse(config.blockProfanityFilter);
    }
}
