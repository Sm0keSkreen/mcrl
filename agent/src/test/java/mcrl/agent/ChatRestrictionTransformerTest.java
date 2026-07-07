package mcrl.agent;

import testfixtures.LegacyChatRestrictionEnum;
import testfixtures.LegacyGetterHolderOptions;
import testfixtures.LegacyGetterHolderProfile;
import testfixtures.ModernAdderHolder;
import testfixtures.ModernChatRestrictionEnum;
import testfixtures.UnrelatedEnum;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static mcrl.agent.TestSupport.classBytes;
import static mcrl.agent.TestSupport.internalName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatRestrictionTransformerTest {

    private final ClassLoader testLoader = getClass().getClassLoader();

    // The Microsoft/Xbox account restriction reason must flip to ENABLED; this is the whole point
    // of the project.
    @Test
    void legacyGetterSwapsProfileRestrictionToEnabled() throws Exception {
        ChatRestrictionTransformer transformer = new ChatRestrictionTransformer(false);

        assertNull(transformer.transform(testLoader, internalName(LegacyChatRestrictionEnum.class),
                null, null, classBytes(LegacyChatRestrictionEnum.class)));

        byte[] patched = transformer.transform(testLoader, internalName(LegacyGetterHolderProfile.class),
                null, null, classBytes(LegacyGetterHolderProfile.class));
        assertNotNull(patched, "getter returning DISABLED_BY_PROFILE should have been patched");

        TestSupport.ByteClassLoader loader = new TestSupport.ByteClassLoader(testLoader);
        loader.override(internalName(LegacyGetterHolderProfile.class), patched);
        Class<?> patchedClass = Class.forName(LegacyGetterHolderProfile.class.getName(), true, loader);
        Object instance = patchedClass.getDeclaredConstructor().newInstance();
        Method getRestriction = patchedClass.getMethod("getRestriction");
        Object result = getRestriction.invoke(instance);

        assertEquals("ENABLED", ((Enum<?>) result).name());
    }

    // A real user/launcher opt-out reason must survive untouched; the patch only targets the
    // account-linked restriction, not the player's own choice.
    @Test
    void legacyGetterLeavesOptionsRestrictionAlone() throws Exception {
        ChatRestrictionTransformer transformer = new ChatRestrictionTransformer(false);

        transformer.transform(testLoader, internalName(LegacyChatRestrictionEnum.class),
                null, null, classBytes(LegacyChatRestrictionEnum.class));

        byte[] patched = transformer.transform(testLoader, internalName(LegacyGetterHolderOptions.class),
                null, null, classBytes(LegacyGetterHolderOptions.class));
        assertNotNull(patched, "the getter method is still instrumented even though this reason is untouched");

        TestSupport.ByteClassLoader loader = new TestSupport.ByteClassLoader(testLoader);
        loader.override(internalName(LegacyGetterHolderOptions.class), patched);
        Class<?> patchedClass = Class.forName(LegacyGetterHolderOptions.class.getName(), true, loader);
        Object instance = patchedClass.getDeclaredConstructor().newInstance();
        Object result = patchedClass.getMethod("getRestriction").invoke(instance);

        assertEquals("DISABLED_BY_OPTIONS", ((Enum<?>) result).name());
    }

    // 26.1+ shape: adding the account-restriction reason becomes a no-op, every other reason is
    // still recorded normally.
    @Test
    void modernAdderSkipsOnlyProfileRestriction() throws Exception {
        ChatRestrictionTransformer transformer = new ChatRestrictionTransformer(false);

        transformer.transform(testLoader, internalName(ModernChatRestrictionEnum.class),
                null, null, classBytes(ModernChatRestrictionEnum.class));

        byte[] patched = transformer.transform(testLoader, internalName(ModernAdderHolder.class),
                null, null, classBytes(ModernAdderHolder.class));
        assertNotNull(patched, "the adder method should have been patched");

        TestSupport.ByteClassLoader loader = new TestSupport.ByteClassLoader(testLoader);
        loader.override(internalName(ModernAdderHolder.class), patched);
        Class<?> patchedClass = Class.forName(ModernAdderHolder.class.getName(), true, loader);
        Object instance = patchedClass.getDeclaredConstructor().newInstance();
        Class<?> enumClass = Class.forName(ModernChatRestrictionEnum.class.getName(), true, loader);
        Method addRestriction = patchedClass.getMethod("addRestriction", enumClass);

        Method valueOf = enumClass.getMethod("valueOf", String.class);
        Object profileReason = valueOf.invoke(null, "DISABLED_BY_PROFILE");
        Object optionsReason = valueOf.invoke(null, "DISABLED_BY_OPTIONS");
        addRestriction.invoke(instance, profileReason);
        addRestriction.invoke(instance, optionsReason);

        List<?> reasons = (List<?>) patchedClass.getMethod("getReasons").invoke(instance);
        assertEquals(1, reasons.size(), "DISABLED_BY_PROFILE should have been skipped, not recorded");
        assertEquals("DISABLED_BY_OPTIONS", ((Enum<?>) reasons.get(0)).name());
    }

    // A single transformer instance should never misclassify something that shares no shape with
    // either the legacy or modern enum, nor let that false read block real detection afterward.
    @Test
    void unrelatedEnumIsIgnoredAndDoesNotPoisonShapeDetection() throws Exception {
        ChatRestrictionTransformer transformer = new ChatRestrictionTransformer(false);

        assertNull(transformer.transform(testLoader, internalName(UnrelatedEnum.class),
                null, null, classBytes(UnrelatedEnum.class)));

        // Shape is still unknown afterward (UnrelatedEnum didn't get wrongly cached as the legacy
        // or modern enum), so the real legacy getter's return type is still found via the
        // resource-based discovery fallback.
        byte[] patched = transformer.transform(testLoader, internalName(LegacyGetterHolderOptions.class),
                null, null, classBytes(LegacyGetterHolderOptions.class));
        assertNotNull(patched, "the real legacy getter should still be discoverable afterward");
    }
}
