package mcrl.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds Minecraft's client-side chat restriction gate by shape, not by name.
 *
 * Forge/NeoForge (official Mojang mappings) call it "Minecraft.getChatStatus()"
 * returning "Minecraft.ChatStatus". Fabric/Quilt (Yarn mappings) call the same
 * feature "MinecraftClient.getChatRestriction()" returning
 * "MinecraftClient.ChatRestriction". Different class and method names per loader,
 * same four enum constants underneath. So instead of hardcoding either name, this
 * scans loaded classes for an enum whose constants are exactly those four names,
 * then patches whatever zero-arg method returns that enum type - wherever it is,
 * whatever it's called.
 */
public class ChatRestrictionTransformer implements ClassFileTransformer {

    private static final Set<String> REQUIRED_CONSTANTS = Set.of(
            "ENABLED", "DISABLED_BY_OPTIONS", "DISABLED_BY_PROFILE", "DISABLED_BY_LAUNCHER");
    private static final String FIX_OWNER = "mcrl/agent/ChatRestrictionFix";

    private final Instrumentation instrumentation;
    private final AtomicReference<String> chatEnumInternalName = new AtomicReference<>();

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

            if (chatEnumInternalName.get() == null && looksLikeChatRestrictionEnum(reader)) {
                if (chatEnumInternalName.compareAndSet(null, reader.getClassName())) {
                    String enumInternalName = reader.getClassName();
                    System.out.println("[mcrl] found chat restriction enum: " + enumInternalName);
                    // Run off-thread: we're currently inside the JVM's resolution of this very
                    // class, so calling retransformClasses() synchronously here would be reentrant.
                    Thread worker = new Thread(
                            () -> retransformAlreadyLoadedGetters(enumInternalName, loader), "mcrl-retransform");
                    worker.setDaemon(true);
                    worker.start();
                }
                return null;
            }

            String enumInternalName = chatEnumInternalName.get();
            if (enumInternalName != null && hasMatchingGetter(reader, enumInternalName)) {
                return patchGetter(reader, enumInternalName, className);
            }
            return null;
        } catch (Throwable t) {
            System.err.println("[mcrl] failed to inspect " + className);
            t.printStackTrace();
            return null;
        }
    }

    private boolean looksLikeChatRestrictionEnum(ClassReader reader) {
        if (!"java/lang/Enum".equals(reader.getSuperName())) {
            return false;
        }
        String selfDescriptor = "L" + reader.getClassName() + ";";
        Set<String> constants = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                            String signature, Object value) {
                boolean isEnumConstant = (access & Opcodes.ACC_ENUM) != 0 && selfDescriptor.equals(descriptor);
                if (isEnumConstant) {
                    constants.add(name);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return constants.containsAll(REQUIRED_CONSTANTS);
    }

    private boolean hasMatchingGetter(ClassReader reader, String enumInternalName) {
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

    private byte[] patchGetter(ClassReader reader, String enumInternalName, String className) {
        String targetDescriptor = "()L" + enumInternalName + ";";
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
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
                System.out.println("[mcrl] patching " + className + "#" + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, FIX_OWNER, "fix",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, enumInternalName);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };

        reader.accept(classVisitor, 0);
        return patched[0] ? writer.toByteArray() : null;
    }

    /**
     * The enum is usually only resolved (and thus first loaded) the moment the
     * player opens chat for the first time - which can easily be after the class
     * holding the getter has already loaded and been passed through untouched.
     * Once we learn the enum's real name, retransform any already-loaded class
     * that has a zero-arg method returning it, so we don't miss that case.
     */
    private void retransformAlreadyLoadedGetters(String enumInternalName, ClassLoader loader) {
        try {
            ClassLoader lookupLoader = loader != null ? loader : ClassLoader.getSystemClassLoader();
            Class<?> enumClass = Class.forName(enumInternalName.replace('/', '.'), false, lookupLoader);

            for (Class<?> loadedClass : instrumentation.getAllLoadedClasses()) {
                if (!instrumentation.isModifiableClass(loadedClass)) {
                    continue;
                }
                try {
                    for (Method method : loadedClass.getDeclaredMethods()) {
                        if (method.getParameterCount() == 0 && method.getReturnType() == enumClass) {
                            System.out.println("[mcrl] retransforming already-loaded " + loadedClass.getName());
                            instrumentation.retransformClasses(loadedClass);
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                    // Not introspectable right now - if it loads again fresh it's still covered by transform().
                }
            }
        } catch (Throwable t) {
            System.err.println("[mcrl] could not scan already-loaded classes for the chat getter");
            t.printStackTrace();
        }
    }
}
