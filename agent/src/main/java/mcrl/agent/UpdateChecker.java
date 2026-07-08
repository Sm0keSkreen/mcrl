package mcrl.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Checks GitHub's releases API once per launch for a newer mcrl version. Entirely best-effort:
// runs on its own daemon thread so it never delays game startup, and fails completely silently
// on any error (offline, rate-limited, whatever) since this is a courtesy notice, not core
// functionality. Only ever reads the public releases endpoint, no data about this install or
// account is sent.
final class UpdateChecker {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/Sm0keSkreen/mcrl/releases/latest";
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"");
    private static final int TIMEOUT_MS = 5000;

    private UpdateChecker() {
    }

    static void checkInBackground() {
        Thread checker = new Thread(UpdateChecker::checkNow, "mcrl-update-check");
        checker.setDaemon(true);
        checker.start();
    }

    private static void checkNow() {
        try {
            String currentVersion = McrlAgent.class.getPackage().getImplementationVersion();
            if (currentVersion == null) {
                return;
            }
            String latestVersion = fetchLatestVersion();
            if (latestVersion != null && isNewer(latestVersion, currentVersion)) {
                System.out.println("[mcrl] a newer version is available: v" + latestVersion
                        + " (running v" + currentVersion + "). Rerun the install script to upgrade.");
            }
        } catch (Throwable t) {
            // Best-effort only; never let a failed update check surface as an error.
        }
    }

    private static String fetchLatestVersion() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "mcrl-update-check");
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }
            Matcher matcher = TAG_NAME_PATTERN.matcher(body);
            return matcher.find() ? matcher.group(1) : null;
        } finally {
            connection.disconnect();
        }
    }

    // Numeric, component-by-component comparison ("1.10.0" > "1.9.0"); a missing trailing
    // component counts as 0. Anything that doesn't parse as dotted numbers is treated as "not
    // newer" rather than guessed at.
    static boolean isNewer(String candidate, String current) {
        String[] candidateParts = candidate.split("\\.");
        String[] currentParts = current.split("\\.");
        int length = Math.max(candidateParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int candidatePart = part(candidateParts, i);
            int currentPart = part(currentParts, i);
            if (candidatePart < 0 || currentPart < 0) {
                return false;
            }
            if (candidatePart != currentPart) {
                return candidatePart > currentPart;
            }
        }
        return false;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
