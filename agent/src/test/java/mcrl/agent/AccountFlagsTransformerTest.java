package mcrl.agent;

import com.mojang.authlib.minecraft.UserApiService;
import testfixtures.FakeUserApiService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static mcrl.agent.TestSupport.classBytes;
import static mcrl.agent.TestSupport.internalName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountFlagsTransformerTest {

    private final ClassLoader testLoader = getClass().getClassLoader();

    @SuppressWarnings("unchecked")
    private Set<UserApiService.UserFlag> patchAndGetFlags(boolean extras, boolean blockTelemetry,
            boolean blockProfanityFilter, Set<UserApiService.UserFlag> startingFlags) throws Exception {
        AccountFlagsTransformer transformer =
                new AccountFlagsTransformer(extras, blockTelemetry, blockProfanityFilter, false);

        byte[] patched = transformer.transform(testLoader, internalName(FakeUserApiService.class),
                null, null, classBytes(FakeUserApiService.class));
        assertNotNull(patched, "expected the properties() getter to be patched for this config");

        TestSupport.ByteClassLoader loader = new TestSupport.ByteClassLoader(testLoader);
        loader.override(internalName(FakeUserApiService.class), patched);
        Class<?> patchedClass = Class.forName(FakeUserApiService.class.getName(), true, loader);
        Constructor<?> ctor = patchedClass.getDeclaredConstructor(Set.class);
        Object instance = ctor.newInstance(startingFlags);
        Method propertiesMethod = patchedClass.getMethod("properties");
        UserApiService.UserProperties result = (UserApiService.UserProperties) propertiesMethod.invoke(instance);
        return (Set<UserApiService.UserFlag>) result.flags();
    }

    @Test
    void extrasAddsServersRealmsFriendsWithoutTouchingUnrelatedFlags() throws Exception {
        Set<UserApiService.UserFlag> starting = new HashSet<>();
        starting.add(UserApiService.UserFlag.CHAT_ALLOWED);

        Set<UserApiService.UserFlag> result = patchAndGetFlags(true, false, false, starting);

        assertTrue(result.contains(UserApiService.UserFlag.SERVERS_ALLOWED));
        assertTrue(result.contains(UserApiService.UserFlag.REALMS_ALLOWED));
        assertTrue(result.contains(UserApiService.UserFlag.FRIENDS_ENABLED));
        assertTrue(result.contains(UserApiService.UserFlag.CHAT_ALLOWED));
    }

    @Test
    void blockTelemetryStripsOnlyTelemetryFlags() throws Exception {
        Set<UserApiService.UserFlag> starting = new HashSet<>();
        starting.add(UserApiService.UserFlag.CHAT_ALLOWED);
        starting.add(UserApiService.UserFlag.TELEMETRY_ENABLED);
        starting.add(UserApiService.UserFlag.OPTIONAL_TELEMETRY_AVAILABLE);

        Set<UserApiService.UserFlag> result = patchAndGetFlags(false, true, false, starting);

        assertFalse(result.contains(UserApiService.UserFlag.TELEMETRY_ENABLED));
        assertFalse(result.contains(UserApiService.UserFlag.OPTIONAL_TELEMETRY_AVAILABLE));
        assertTrue(result.contains(UserApiService.UserFlag.CHAT_ALLOWED));
    }

    @Test
    void blockProfanityFilterStripsOnlyThatFlag() throws Exception {
        Set<UserApiService.UserFlag> starting = new HashSet<>();
        starting.add(UserApiService.UserFlag.CHAT_ALLOWED);
        starting.add(UserApiService.UserFlag.PROFANITY_FILTER_ENABLED);

        Set<UserApiService.UserFlag> result = patchAndGetFlags(false, false, true, starting);

        assertFalse(result.contains(UserApiService.UserFlag.PROFANITY_FILTER_ENABLED));
        assertTrue(result.contains(UserApiService.UserFlag.CHAT_ALLOWED));
    }

    // Nothing to force, nothing to strip: the class must be left completely untouched instead of
    // rewriting it for no reason.
    @Test
    void allOptionsOffLeavesPropertiesGetterUnpatched() throws Exception {
        AccountFlagsTransformer transformer = new AccountFlagsTransformer(false, false, false, false);
        byte[] patched = transformer.transform(testLoader, internalName(FakeUserApiService.class),
                null, null, classBytes(FakeUserApiService.class));
        assertNull(patched);
    }

    // Regression test for the abstract-method matching bug: UserApiService.properties() itself is
    // the abstract interface declaration and must never be matched/rewritten.
    @Test
    void abstractInterfaceMethodIsNeverPatched() throws Exception {
        AccountFlagsTransformer transformer = new AccountFlagsTransformer(true, true, true, false);
        byte[] patched = transformer.transform(testLoader, internalName(UserApiService.class),
                null, null, classBytes(UserApiService.class));
        assertNull(patched, "the abstract interface method must never be matched");
    }
}
