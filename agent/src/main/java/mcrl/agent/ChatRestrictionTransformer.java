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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

// Finds and patches Minecraft's chat-restriction check by bytecode shape, not by name.
public class ChatRestrictionTransformer implements ClassFileTransformer {

    // Not Set.of(): this needs to load on Java 8.
    private static final Set<String> LEGACY_REQUIRED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "ENABLED", "DISABLED_BY_OPTIONS", "DISABLED_BY_PROFILE", "DISABLED_BY_LAUNCHER")));

    private final AtomicReference<String> legacyEnumInternalName = new AtomicReference<>();
    private final AtomicReference<String> modernEnumInternalName = new AtomicReference<>();
    private final ConcurrentHashMap<ClassLoader, ConcurrentHashMap<String, EnumShape>> resourceShapeCache = new ConcurrentHashMap<>();
    private final boolean verbose;

    // verbose off keeps this quiet in every other Java process JDK_JAVA_OPTIONS reaches, patching still runs regardless.
    public ChatRestrictionTransformer(boolean verbose) {
        this.verbose = verbose;
    }

    // Catches the enum and its getter/adder at their own first load; never retransforms.
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        // Skips bootstrap/JDK/our-own-shaded classes; touching those here can crash with ClassCircularityError.
        if (loader == null || className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("jdk/") || className.startsWith("sun/") || className.startsWith("mcrl/")) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            String legacyName = legacyEnumInternalName.get();
            String modernName = modernEnumInternalName.get();
            // A single running game only ever has one of these two shapes, never both, so once
            // either is confirmed the other will never appear; stop searching for it at all
            // (classifyEnum and both discovery loops) instead of scanning every class forever.
            boolean shapeUnknown = legacyName == null && modernName == null;

            if (shapeUnknown) {
                EnumShape shape = classifyEnum(reader);
                if (shape == EnumShape.LEGACY) {
                    if (legacyEnumInternalName.compareAndSet(null, reader.getClassName()) && verbose) {
                        System.out.println("[mcrl] found legacy chat-restriction enum: " + reader.getClassName());
                    }
                    return null;
                }
                if (shape == EnumShape.MODERN) {
                    if (modernEnumInternalName.compareAndSet(null, reader.getClassName()) && verbose) {
                        System.out.println("[mcrl] found modern chat-restriction enum: " + reader.getClassName());
                    }
                    return null;
                }
            }
            if (legacyName != null && hasLegacyGetter(reader, legacyName)) {
                return patchLegacyGetter(reader, legacyName, className, loader);
            }
            if (shapeUnknown) {
                for (String candidate : discoverLegacyGetterCandidates(reader)) {
                    if (classifyResource(loader, candidate) == EnumShape.LEGACY) {
                        if (legacyEnumInternalName.compareAndSet(null, candidate) && verbose) {
                            System.out.println("[mcrl] found legacy chat-restriction enum: " + candidate
                                    + " (via getter in " + className + ")");
                        }
                        return patchLegacyGetter(reader, candidate, className, loader);
                    }
                }
            }

            if (modernName != null && hasModernAdder(reader, modernName)) {
                return patchModernAdder(reader, modernName, className, loader);
            }
            if (shapeUnknown) {
                for (String candidate : discoverModernAdderCandidates(reader)) {
                    if (classifyResource(loader, candidate) == EnumShape.MODERN) {
                        if (modernEnumInternalName.compareAndSet(null, candidate) && verbose) {
                            System.out.println("[mcrl] found modern chat-restriction enum: " + candidate
                                    + " (via adder in " + className + ")");
                        }
                        return patchModernAdder(reader, candidate, className, loader);
                    }
                }
            }

            return null;
        } catch (Throwable t) {
            if (verbose) {
                System.err.println("[mcrl] failed to inspect " + className);
                t.printStackTrace();
            }
            return null;
        }
    }

    // Used by the startup self-check to warn if this Minecraft version/loader's chat-restriction
    // code was never recognized at all, instead of silently doing nothing.
    boolean shapeFound() {
        return legacyEnumInternalName.get() != null || modernEnumInternalName.get() != null;
    }

    private enum EnumShape { NONE, LEGACY, MODERN }

    // Matches the enum by its constant-name strings, which survive obfuscation/remapping.
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

    // Reads a candidate class's bytes as a plain resource, never a real class load; cached per
    // loader since the same non-matching candidate often turns up from several classes in a row.
    private EnumShape classifyResource(ClassLoader loader, String candidateInternalName) {
        ClassLoader effectiveLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
        ConcurrentHashMap<String, EnumShape> cache =
                resourceShapeCache.computeIfAbsent(effectiveLoader, l -> new ConcurrentHashMap<>());
        EnumShape cached = cache.get(candidateInternalName);
        if (cached != null) {
            return cached;
        }
        EnumShape shape = EnumShape.NONE;
        try (InputStream in = effectiveLoader.getResourceAsStream(candidateInternalName + ".class")) {
            if (in != null) {
                shape = classifyEnum(new ClassReader(readAllBytes(in)));
            }
        } catch (Throwable t) {
            shape = EnumShape.NONE;
        }
        cache.put(candidateInternalName, shape);
        return shape;
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

    // Every distinct return type of a non-static zero-arg getter in this class.
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

    // Rewrites every ARETURN in the getter to swap a DISABLED_BY_PROFILE return for ENABLED.
    private byte[] patchLegacyGetter(ClassReader reader, String enumInternalName, String className, ClassLoader loader) {
        String targetDescriptor = "()L" + enumInternalName + ";";
        ClassWriter writer = newClassWriter(reader, loader);
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
                if (verbose) {
                    System.out.println("[mcrl] patching (legacy) " + className + "#" + name + descriptor);
                }
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

    // Every distinct single-parameter type of a fluent setter returning its own declaring class.
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

    // No-ops the adder when called with DISABLED_BY_PROFILE, so that reason never gets recorded.
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
                if (verbose) {
                    System.out.println("[mcrl] patching (modern) " + className + "#" + name + descriptor);
                }
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

    // Uses the target class's own loader for COMPUTE_FRAMES type resolution when one is given.
    private ClassWriter newClassWriter(ClassReader reader, ClassLoader loader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }
        };
    }
}
