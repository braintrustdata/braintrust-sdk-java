package dev.braintrust.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.CodeSource;
import java.security.SecureClassLoader;
import java.security.cert.Certificate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * A classloader that loads agent-internal classes from {@code .classdata} entries inside the agent
 * JAR.
 *
 * <p>Classes stored under the {@code inst/} prefix with a {@code .classdata} extension are
 * invisible to the JVM's default classloading mechanism. This classloader knows how to find them,
 * providing full classloader isolation between the agent's internals and the application's
 * classpath.
 *
 * <p>The delegation model is: parent-first (standard), falling back to reading {@code .classdata}
 * entries from the agent JAR. This means bootstrap classes (like this class itself and {@link
 * BraintrustAgent}) are loaded by the parent (system classloader), while agent internals (ByteBuddy,
 * OTel SDK, instrumentation code) are loaded here and invisible to the application.
 */
public class BraintrustClassLoader extends SecureClassLoader {

    private static final String ENTRY_PREFIX = "inst/";
    private static final String CLASS_DATA_SUFFIX = ".classdata";

    private final JarFile agentJarFile;
    private final CodeSource agentCodeSource;
    private final String agentResourcePrefix;

    static {
        registerAsParallelCapable();
    }

    /**
     * Creates a new BraintrustClassLoader.
     *
     * @param agentJarURL the URL of the agent JAR file (from the -javaagent path)
     * @param parent the parent classloader (typically the system/platform classloader)
     */
    public BraintrustClassLoader(URL agentJarURL, ClassLoader parent) throws Exception {
        super(parent);
        this.agentJarFile = new JarFile(new java.io.File(agentJarURL.toURI()), false);
        this.agentCodeSource = new CodeSource(agentJarURL, (Certificate[]) null);
        this.agentResourcePrefix = "jar:file:" + agentJarFile.getName() + "!/";
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // Convert "dev.braintrust.agent.internal.AgentInstaller"
        //      -> "inst/dev/braintrust/agent/internal/AgentInstaller.classdata"
        String entryName = ENTRY_PREFIX + name.replace('.', '/') + CLASS_DATA_SUFFIX;
        JarEntry entry = agentJarFile.getJarEntry(entryName);
        if (entry == null) {
            throw new ClassNotFoundException(name);
        }

        byte[] classBytes = readEntry(entry, name);
        return defineClass(name, classBytes, 0, classBytes.length, agentCodeSource);
    }

    @Override
    protected URL findResource(String name) {
        // For .class resource lookups, map to .classdata
        String entryName;
        if (name.endsWith(".class")) {
            entryName = ENTRY_PREFIX + name.substring(0, name.length() - ".class".length())
                    + CLASS_DATA_SUFFIX;
        } else {
            entryName = ENTRY_PREFIX + name;
        }

        JarEntry entry = agentJarFile.getJarEntry(entryName);
        if (entry != null) {
            try {
                return new URL(agentResourcePrefix + entryName);
            } catch (java.net.MalformedURLException e) {
                // fall through
            }
        }
        return null;
    }

    private byte[] readEntry(JarEntry entry, String className) throws ClassNotFoundException {
        int size = (int) entry.getSize();
        byte[] buf = new byte[size];
        try (InputStream in = agentJarFile.getInputStream(entry)) {
            int offset = 0;
            while (offset < size) {
                int bytesRead = in.read(buf, offset, size - offset);
                if (bytesRead < 0) {
                    break;
                }
                offset += bytesRead;
            }
            if (offset != size) {
                throw new ClassNotFoundException(
                        className + " (incomplete read: " + offset + "/" + size + " bytes)");
            }
            return buf;
        } catch (IOException e) {
            throw new ClassNotFoundException(className, e);
        }
    }
}
