package mcrl.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void higherMinorVersionIsNewer() {
        assertTrue(UpdateChecker.isNewer("1.4.0", "1.3.2"));
    }

    @Test
    void doubleDigitComponentBeatsSingleDigitNumerically() {
        // A naive string comparison would get this backwards ("1.9.0" > "1.10.0" lexically).
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"));
    }

    @Test
    void sameVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.3.2", "1.3.2"));
    }

    @Test
    void olderVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.3.2"));
    }

    @Test
    void missingTrailingComponentCountsAsZero() {
        assertFalse(UpdateChecker.isNewer("1.3", "1.3.0"));
        assertTrue(UpdateChecker.isNewer("1.3.1", "1.3"));
    }

    @Test
    void unparseableVersionIsNeverTreatedAsNewer() {
        assertFalse(UpdateChecker.isNewer("not-a-version", "1.3.2"));
        assertFalse(UpdateChecker.isNewer("1.3.2", "not-a-version"));
    }
}
