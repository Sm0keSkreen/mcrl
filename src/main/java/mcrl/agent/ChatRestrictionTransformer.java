package mcrl.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds Minecraft's client-side chat restriction gate by shape, not by name.
 *
 * Two real shapes exist depending on Minecraft's era, both confirmed against real
 * client jars and official mappings (see the project's verification notes):
 *
 * Legacy shape (1.19 through 1.21.11, Forge "Minecraft.getChatStatus()" / Fabric
 * "MinecraftClient.getChatRestriction()"): a single enum with constants ENABLED,
 * DISABLED_BY_OPTIONS, DISABLED_BY_PROFILE, DISABLED_BY_LAUNCHER, and a zero-arg
 * getter returning it. Patched by swapping DISABLED_BY_PROFILE for ENABLED on return.
 *
 * Modern shape (26.1+, unobfuscated): Mojang restructured this into a
 * "ChatAbilities" object built from a set of "ChatRestriction" enum constants
 * (CHAT_AND_COMMANDS_DISABLED_BY_OPTIONS, CHAT_DISABLED_BY_OPTIONS,
 * DISABLED_BY_LAUNCHER, DISABLED_BY_PROFILE, no ENABLED constant at all; "no
 * restriction" is the absence of any). Patched by no-opping the fluent
 * "addRestriction(ChatRestriction) -> same builder type" method whenever it's
 * called with DISABLED_BY_PROFILE, so that reason never gets recorded.
 *
 * Neither strategy hardcodes a class or method name, both match purely on the
 * enum's constant-name strings (see classifyEnum's note on why that survives
 * obfuscation/remapping where field-symbol matching doesn't), so the same jar
 * works across every loader (Forge/NeoForge/Fabric/Quilt) and true unmodified
 * vanilla, for whichever era of the game is actually running.
 */
public class ChatRestrictionTransformer implements ClassFileTransformer {

    // Set.of() is Java 9+; this needs to load on the Java 16 runtime Minecraft 1.17
    // itself requires (and be inert rather than crash-on-load for anything older).
    private static final Set<String> LEGACY_REQUIRED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ENABLED", "DISABLED_BY_OPTIONS", "DISABLED_BY_PROFILE", "DISABLED_BY_LAUNCHER")));

    private final Instrumentation instrumentation;
    private final AtomicReference<String> legacyEnumInternalName = new AtomicReference<>();
    private final AtomicReference<String> modernEnumInternalName = new AtomicReference<>();

    public ChatRestrictionTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);

            EnumShape shape = classifyEnum(reader);
            if (shape == EnumShape.LEGACY && legacyEnumInternalName.compareAndSet(null, reader.getClassName())) {
                String enumInternalName = reader.getClassName();
                System.out.println("[mcrl] found legacy chat-restriction enum: " + enumInternalName);
                retransformLaterFor(enumInternalName, loader);
                return null;
            }
            if (shape == EnumShape.MODERN && modernEnumInternalName.compareAndSet(null, reader.getClassName())) {
                String enumInternalName = reader.getClassName();
                System.out.println("[mcrl] found modern chat-restriction enum: " + enumInternalName);
                retransformLaterFor(enumInternalName, loader);
                return null;
            }
            if (shape != EnumShape.NONE) {
                return null;
            }

            String legacyName = legacyEnumInternalName.get();
            if (legacyName != null && hasLegacyGetter(reader, legacyName)) {
                return patchLegacyGetter(reader, legacyName, className);
            }

            String modernName = modernEnumInternalName.get();
            if (modernName != null && hasModernAdder(reader, modernName)) {
                return patchModernAdder(reader, modernName, className, loader);
            }

            return null;
        } catch (Throwable t) {
            System.err.println("[mcrl] failed to inspect " + className);
            t.printStackTrace();
            return null;
        }
    }

    private enum EnumShape { NONE, LEGACY, MODERN }

    /**
     * Identifies the enum by the constant-name strings baked into its own
     * &lt;clinit&gt; (the argument every enum constant passes to the implicit
     * Enum(String name, int ordinal) constructor) rather than by the enum
     * field's own bytecode symbol.
     *
     * This distinction matters: obfuscators and remappers rename symbols (field
     * names, method names, class names) but leave arbitrary string literals
     * alone, since altering them could change program behavior. Confirmed
     * empirically, raw vanilla obfuscated jars and Fabric's production
     * "Intermediary" remapping both rename the enum's field symbols to
     * meaningless IDs (e.g. "a", "field_28943"), but the constant-name strings
     * ("ENABLED", "DISABLED_BY_PROFILE", ...) survive untouched in both cases,
     * because they're read back at runtime via Enum.name()/.valueOf() and various
     * data-driven lookups that would break if the obfuscator touched them.
     * Matching on field symbols only ever worked in a Forge/official-mappings or
     * Fabric-development (Yarn) environment, this matches everywhere instead.
     */
    private EnumShape classifyEnum(ClassReader reader) {
        if (!"java/lang/Enum".equals(reader.getSuperName())) {
            return EnumShape.NONE;
        }
        Set<String> constantNames = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (!"<clinit>".equals(name)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String) {
                            constantNames.add((String) value);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (constantNames.containsAll(LEGACY_REQUIRED)) {
            return EnumShape.LEGACY;
        }
        if (constantNames.contains("DISABLED_BY_PROFILE") && constantNames.contains("DISABLED_BY_LAUNCHER")
                && !constantNames.contains("ENABLED")) {
            return EnumShape.MODERN;
        }
        return EnumShape.NONE;
    }

    // ---- legacy shape: zero-arg getter returning the enum ----

    private boolean hasLegacyGetter(ClassReader reader, String enumInternalName) {
        String targetDescriptor = "()L" + enumInternalName + ";";
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (targetDescriptor.equals(descriptor)) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * Rewrites every ARETURN in the getter to swap a DISABLED_BY_PROFILE return
     * value for ENABLED. Inlined entirely with java.lang.Enum/String plus the
     * enum's own compiler-generated valueOf(String), deliberately calls no custom
     * helper class. Confirmed live against real Fabric: the patched class
     * (net.minecraft.class_310) loads through Fabric's KnotClassLoader, which is
     * isolated from whatever -javaagent added to the plain system classpath, so an
     * INVOKESTATIC to a class living only in this agent's own jar threw
     * NoClassDefFoundError the moment the patched method actually ran. Every class
     * referenced here is either java.lang.* (visible from any classloader via the
     * bootstrap loader) or the enum itself (already loaded by whatever loader owns
     * the class being patched, since it's that method's own return type).
     */
    private byte[] patchLegacyGetter(ClassReader reader, String enumInternalName, String className) {
        String targetDescriptor = "()L" + enumInternalName + ";";
        ClassWriter writer = newClassWriter(reader, null);
        boolean[] patched = {false};

        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targetDescriptor.equals(descriptor)) {
                    return mv;
                }
                patched[0] = true;
                System.out.println("[mcrl] patching (legacy) " + className + "#" + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            Label notProfile = new Label();
                            super.visitInsn(Opcodes.DUP);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                                    "()Ljava/lang/String;", false);
                            super.visitLdcInsn("DISABLED_BY_PROFILE");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                                    "(Ljava/lang/Object;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, notProfile);
                            super.visitInsn(Opcodes.POP);
                            super.visitLdcInsn("ENABLED");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, enumInternalName, "valueOf",
                                    "(Ljava/lang/String;)L" + enumInternalName + ";", false);
                            super.visitLabel(notProfile);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };

        reader.accept(classVisitor, 0);
        return patched[0] ? writer.toByteArray() : null;
    }

    // ---- modern shape: fluent "addRestriction(Enum) -> same builder type" ----

    private boolean hasModernAdder(ClassReader reader, String enumInternalName) {
        String targetDescriptor = "(L" + enumInternalName + ";)L" + reader.getClassName() + ";";
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (targetDescriptor.equals(descriptor) && (access & Opcodes.ACC_STATIC) == 0) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private byte[] patchModernAdder(ClassReader reader, String enumInternalName, String className, ClassLoader loader) {
        String targetDescriptor = "(L" + enumInternalName + ";)L" + reader.getClassName() + ";";
        ClassWriter writer = newClassWriter(reader, loader);
        boolean[] patched = {false};

        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!targetDescriptor.equals(descriptor) || (access & Opcodes.ACC_STATIC) != 0) {
                    return mv;
                }
                patched[0] = true;
                System.out.println("[mcrl] patching (modern) " + className + "#" + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label continueLabel = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Enum", "name",
                                "()Ljava/lang/String;", false);
                        super.visitLdcInsn("DISABLED_BY_PROFILE");
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                                "(Ljava/lang/Object;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, continueLabel);
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(continueLabel);
                    }
                };
            }
        };

        reader.accept(classVisitor, 0);
        return patched[0] ? writer.toByteArray() : null;
    }

    private ClassWriter newClassWriter(ClassReader reader, ClassLoader loader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
    }

    /**
     * The enum is usually only resolved (and thus first loaded) the moment the
     * player opens chat for the first time, which can easily be after the class
     * holding the target method has already loaded and been passed through
     * untouched. Once we learn the enum's real name, retransform any already-loaded
     * class matching the given shape test, so we don't miss that case.
     */
    private void retransformLaterFor(String enumInternalName, ClassLoader loader) {
        Thread worker = new Thread(() -> {
            try {
                ClassLoader lookupLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
                Class<?> enumClass = Class.forName(enumInternalName.replace('/', '.'), false, lookupLoader);

                for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                    if (!instrumentation.isModifiableClass(loadedClass)) {
                        continue;
                    }
                    try {
                        boolean matches = false;
                        for (Method method : loadedClass.getDeclaredMethods()) {
                            Class<?>[] params = method.getParameterTypes();
                            boolean legacyShape = params.length == 0 && method.getReturnType() == enumClass;
                            boolean modernShape = params.length == 1 && params[0] == enumClass
                                    && method.getReturnType() == loadedClass;
                            if (legacyShape || modernShape) {
                                matches = true;
                                break;
                            }
                        }
                        if (matches) {
                            System.out.println("[mcrl] retransforming already-loaded " + loadedClass.getName());
                            instrumentation.retransformClasses(loadedClass);
                        }
                    } catch (Throwable ignored) {
                        // Not introspectable right now, if it loads again fresh it's still covered by transform().
                    }
                }
            } catch (Throwable t) {
                System.err.println("[mcrl] could not scan already-loaded classes for the chat gate");
                t.printStackTrace();
            }
        }, "mcrl-retransform");
        worker.setDaemon(true);
        worker.start();
    }
}
