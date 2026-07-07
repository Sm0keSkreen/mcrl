package mcrl.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Unlocks Realms/servers/friends by patching authlib's UserApiService$UserProperties.properties(); unobfuscated, so matches by real name.
public class AccountFlagsTransformer implements ClassFileTransformer {

    private static final String USER_PROPERTIES = "com/mojang/authlib/minecraft/UserApiService$UserProperties";
    private static final String USER_FLAG = "com/mojang/authlib/minecraft/UserApiService$UserFlag";
    private static final String TARGET_DESCRIPTOR = "()L" + USER_PROPERTIES + ";";
    private static final List<String> DESIRED_FLAGS =
            Arrays.asList("SERVERS_ALLOWED", "REALMS_ALLOWED", "FRIENDS_ENABLED");
    // UserProperties gained a second (bannedScopes) constructor param sometime after MC 1.19; both
    // shapes are still in use across the versions this agent targets, so both must be handled.
    private static final String CTOR_FLAGS_ONLY = "(Ljava/util/Set;)V";
    private static final String CTOR_FLAGS_AND_BANS = "(Ljava/util/Set;Ljava/util/Map;)V";

    private final ConcurrentHashMap<ClassLoader, AccountApiShape> shapeCache = new ConcurrentHashMap<>();
    private final boolean verbose;

    public AccountFlagsTransformer(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                             ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || classfileBuffer == null) {
            return null;
        }
        if (loader == null || className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("jdk/") || className.startsWith("sun/") || className.startsWith("mcrl/")) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            if (!hasPropertiesGetter(reader)) {
                return null;
            }
            AccountApiShape shape = detectShape(loader);
            if (shape == null || shape.flagsToForce.isEmpty()) {
                return null;
            }
            return patchPropertiesGetter(reader, className, loader, shape);
        } catch (Throwable t) {
            if (verbose) {
                System.err.println("[mcrl] failed to inspect " + className);
                t.printStackTrace();
            }
            return null;
        }
    }

    private boolean hasPropertiesGetter(ClassReader reader) {
        boolean[] found = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                if (TARGET_DESCRIPTOR.equals(descriptor) && (access & Opcodes.ACC_STATIC) == 0) {
                    found[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static final class AccountApiShape {
        final List<String> flagsToForce;
        final String constructorDescriptor;

        AccountApiShape(List<String> flagsToForce, String constructorDescriptor) {
            this.flagsToForce = flagsToForce;
            this.constructorDescriptor = constructorDescriptor;
        }
    }

    // Reads real UserFlag/UserProperties bytes (never a real class load) to see which of
    // DESIRED_FLAGS exist and which constructor shape is present; both drift across MC versions.
    private AccountApiShape detectShape(ClassLoader loader) {
        return shapeCache.computeIfAbsent(loader, l -> {
            List<String> flagsToForce = detectAvailableFlags(l);
            String constructorDescriptor = detectConstructorDescriptor(l);
            if (constructorDescriptor == null) {
                return new AccountApiShape(java.util.Collections.emptyList(), null);
            }
            return new AccountApiShape(flagsToForce, constructorDescriptor);
        });
    }

    private List<String> detectAvailableFlags(ClassLoader loader) {
        Set<String> presentFields = new LinkedHashSet<>();
        try (InputStream in = loader.getResourceAsStream(USER_FLAG + ".class")) {
            if (in == null) {
                return java.util.Collections.emptyList();
            }
            new ClassReader(readAllBytes(in)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                                String signature, Object value) {
                    if ((access & Opcodes.ACC_ENUM) != 0) {
                        presentFields.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
        List<String> matched = new ArrayList<>();
        for (String desired : DESIRED_FLAGS) {
            if (presentFields.contains(desired)) {
                matched.add(desired);
            }
        }
        return matched;
    }

    // Returns CTOR_FLAGS_ONLY or CTOR_FLAGS_AND_BANS depending on which is actually present, or
    // null if neither matches, so an unrecognized future shape fails closed instead of guessing.
    private String detectConstructorDescriptor(ClassLoader loader) {
        String[] found = {null};
        try (InputStream in = loader.getResourceAsStream(USER_PROPERTIES + ".class")) {
            if (in == null) {
                return null;
            }
            new ClassReader(readAllBytes(in)).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    if ("<init>".equals(name)
                            && (CTOR_FLAGS_ONLY.equals(descriptor) || CTOR_FLAGS_AND_BANS.equals(descriptor))) {
                        found[0] = descriptor;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Throwable t) {
            return null;
        }
        return found[0];
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

    // Rebuilds UserProperties with shape.flagsToForce added to the flags set, using whichever
    // constructor shape was actually detected; bannedScopes (when present) is left untouched.
    private byte[] patchPropertiesGetter(ClassReader reader, String className, ClassLoader loader,
                                          AccountApiShape shape) {
        ClassWriter writer = newClassWriter(reader, loader);
        boolean[] patched = {false};
        boolean includeBans = CTOR_FLAGS_AND_BANS.equals(shape.constructorDescriptor);

        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!TARGET_DESCRIPTOR.equals(descriptor) || (access & Opcodes.ACC_STATIC) != 0) {
                    return mv;
                }
                patched[0] = true;
                if (verbose) {
                    System.out.println("[mcrl] patching (account flags) " + className + "#" + name + descriptor
                            + " with " + shape.flagsToForce);
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(maxStack, maxLocals + 2);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            // properties() is zero-arg, so only slot 0 (this) is in use; 1 and 2 are free.
                            int origSlot = 1;
                            int newFlagsSlot = 2;

                            super.visitVarInsn(Opcodes.ASTORE, origSlot);

                            super.visitTypeInsn(Opcodes.NEW, "java/util/HashSet");
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, origSlot);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, USER_PROPERTIES, "flags",
                                    "()Ljava/util/Set;", false);
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>",
                                    "(Ljava/util/Collection;)V", false);

                            for (String flagName : shape.flagsToForce) {
                                super.visitInsn(Opcodes.DUP);
                                super.visitFieldInsn(Opcodes.GETSTATIC, USER_FLAG, flagName, "L" + USER_FLAG + ";");
                                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Set", "add",
                                        "(Ljava/lang/Object;)Z", true);
                                super.visitInsn(Opcodes.POP);
                            }

                            super.visitVarInsn(Opcodes.ASTORE, newFlagsSlot);

                            super.visitTypeInsn(Opcodes.NEW, USER_PROPERTIES);
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, newFlagsSlot);
                            if (includeBans) {
                                super.visitVarInsn(Opcodes.ALOAD, origSlot);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, USER_PROPERTIES, "bannedScopes",
                                        "()Ljava/util/Map;", false);
                            }
                            super.visitMethodInsn(Opcodes.INVOKESPECIAL, USER_PROPERTIES, "<init>",
                                    shape.constructorDescriptor, false);
                        }
                        super.visitInsn(opcode);
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
