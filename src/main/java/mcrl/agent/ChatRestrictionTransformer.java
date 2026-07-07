package mcrl.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
 *
 * IMPORTANT: there is deliberately no retransformClasses() anywhere in this file.
 * An earlier version used it to patch a getter/adder class that had already
 * loaded before the enum was identified (which happens routinely, the class
 * holding the method usually loads at startup, long before the enum is ever
 * actually touched, e.g. the moment a server connection is opened). That version
 * went through three iterations, and all three broke, confirmed by direct
 * testing, not theory: calling retransformClasses() synchronously from within
 * transform() while it's processing the enum's own definition crashes with
 * "LinkageError: attempted duplicate class definition" for the enum itself, no
 * matter whether a Class object for the enum is ever requested or not. Moving
 * the retransformClasses() call to a background thread avoids that crash but
 * loses a real race instead, confirmed live against an actual account, where
 * the game built its per-connection permissions object with the unpatched value
 * because the background thread hadn't finished yet; only reconnecting within
 * the same session (by which point the background thread had caught up) worked.
 * Having the original thread join() on that background thread to close the race
 * deadlocks instead, confirmed by every single test run hanging. So instead of
 * ever patching an already-loaded class after the fact, this scans every class
 * as it loads for the getter/adder *shape* even before the enum's identity is
 * known, using the return/parameter type name straight out of the method
 * descriptor string, then confirms that candidate by fetching its bytes as a
 * plain classloader resource (getResourceAsStream, never a real class load or
 * Class object) and running the exact same constant-string check used to
 * recognize the enum normally. That means the getter/adder class gets caught
 * and patched at its own natural first load no matter which of it or the enum
 * loads first, so there is never an already-loaded class left to retransform.
 */
public class ChatRestrictionTransformer implements ClassFileTransformer {

    // Set.of() is Java 9+; this needs to load on the Java 16 runtime Minecraft 1.17
    // itself requires (and be inert rather than crash-on-load for anything older).
    private static final Set<String> LEGACY_REQUIRED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ENABLED", "DISABLED_BY_OPTIONS", "DISABLED_BY_PROFILE", "DISABLED_BY_LAUNCHER")));

    private final AtomicReference<String> legacyEnumInternalName = new AtomicReference<>();
    private final AtomicReference<String> modernEnumInternalName = new AtomicReference<>();

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);

            EnumShape shape = classifyEnum(reader);
            if (shape == EnumShape.LEGACY) {
                if (legacyEnumInternalName.compareAndSet(null, reader.getClassName())) {
                    System.out.println("[mcrl] found legacy chat-restriction enum: " + reader.getClassName());
                }
                return null;
            }
            if (shape == EnumShape.MODERN) {
                if (modernEnumInternalName.compareAndSet(null, reader.getClassName())) {
                    System.out.println("[mcrl] found modern chat-restriction enum: " + reader.getClassName());
                }
                return null;
            }

            String legacyName = legacyEnumInternalName.get();
            if (legacyName != null && hasLegacyGetter(reader, legacyName)) {
                return patchLegacyGetter(reader, legacyName, className);
            }
            if (legacyName == null) {
                for (String candidate : discoverLegacyGetterCandidates(reader)) {
                    if (classifyResource(loader, candidate) == EnumShape.LEGACY) {
                        if (legacyEnumInternalName.compareAndSet(null, candidate)) {
                            System.out.println("[mcrl] found legacy chat-restriction enum: " + candidate
                                    + " (via getter in " + className + ")");
                        }
                        return patchLegacyGetter(reader, candidate, className);
                    }
                }
            }

            String modernName = modernEnumInternalName.get();
            if (modernName != null && hasModernAdder(reader, modernName)) {
                return patchModernAdder(reader, modernName, className, loader);
            }
            if (modernName == null) {
                for (String candidate : discoverModernAdderCandidates(reader)) {
                    if (classifyResource(loader, candidate) == EnumShape.MODERN) {
                        if (modernEnumInternalName.compareAndSet(null, candidate)) {
                            System.out.println("[mcrl] found modern chat-restriction enum: " + candidate
                                    + " (via adder in " + className + ")");
                        }
                        return patchModernAdder(reader, candidate, className, loader);
                    }
                }
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

    /**
     * Fetches a candidate class's bytes as a plain classloader resource and runs
     * the same check classifyEnum does on it, deliberately not a real class load
     * (no Class.forName, no defineClass). getResourceAsStream only ever reads
     * bytes off the classpath/jar; it never asks the classloader to define
     * anything, so calling it here for some other, unrelated, not-yet-loaded
     * class carries none of the reentrancy risk retransformClasses() did.
     */
    private EnumShape classifyResource(ClassLoader loader, String candidateInternalName) {
        ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        try (InputStream in = effectiveLoader.getResourceAsStream(candidateInternalName + ".class")) {
            if (in == null) {
                return EnumShape.NONE;
            }
            return classifyEnum(new ClassReader(readAllBytes(in)));
        } catch (Throwable t) {
            return EnumShape.NONE;
        }
    }

    private static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ---- legacy shape: zero-arg getter returning the enum ----

    /** Every distinct return type of a non-static zero-arg getter in this class, in declaration order. */
    private List<String> discoverLegacyGetterCandidates(ClassReader reader) {
        Set<String> found = new LinkedHashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_STATIC) == 0 && descriptor.startsWith("()L") && descriptor.endsWith(";")) {
                    found.add(descriptor.substring(3, descriptor.length() - 1));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ArrayList<>(found);
    }

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

    /** Every distinct single-parameter type of a non-static fluent setter (returns its own declaring class). */
    private List<String> discoverModernAdderCandidates(ClassReader reader) {
        Set<String> found = new LinkedHashSet<>();
        String selfReturn = ")L" + reader.getClassName() + ";";
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if ((access & Opcodes.ACC_STATIC) != 0 || !descriptor.startsWith("(L")) {
                    return null;
                }
                int paramEnd = descriptor.indexOf(';');
                if (paramEnd > 0 && descriptor.substring(paramEnd + 1).equals(selfReturn)) {
                    found.add(descriptor.substring(2, paramEnd));
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ArrayList<>(found);
    }

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
}
