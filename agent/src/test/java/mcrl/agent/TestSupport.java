package mcrl.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

// Shared helpers for feeding real compiled class bytes through a transformer and loading whatever
// comes back, without needing an in-memory compiler or reflection into ClassLoader internals.
final class TestSupport {

    private TestSupport() {
    }

    static byte[] classBytes(Class<?> clazz) throws IOException {
        String resource = clazz.getName().replace('.', '/') + ".class";
        try (InputStream in = clazz.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("resource not found: " + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    static String internalName(Class<?> clazz) {
        return clazz.getName().replace('.', '/');
    }

    // A ClassLoader that serves specific classes from caller-supplied bytes (e.g. a transformer's
    // patched output) and delegates everything else to the parent, so patched fixture classes can
    // still resolve their unpatched sibling/dependency classes normally.
    static final class ByteClassLoader extends ClassLoader {
        private final Map<String, byte[]> overrides = new HashMap<>();

        ByteClassLoader(ClassLoader parent) {
            super(parent);
        }

        void override(String internalName, byte[] bytes) {
            overrides.put(internalName.replace('/', '.'), bytes);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = overrides.get(name);
            if (bytes == null) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }
}
