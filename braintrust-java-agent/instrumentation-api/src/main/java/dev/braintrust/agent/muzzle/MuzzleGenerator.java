package dev.braintrust.agent.muzzle;

import dev.braintrust.agent.instrumentation.InstrumentationModule;
import dev.braintrust.agent.instrumentation.TypeInstrumentation;
import dev.braintrust.agent.instrumentation.TypeTransformer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Generates a {@code $Muzzle} side-class for an {@link InstrumentationModule} at compile time.
 *
 * <p>The generated class has a single static method:
 * <pre>
 *   public static ReferenceMatcher create() { ... }
 * </pre>
 * which constructs a {@link ReferenceMatcher} containing all the {@link Reference}s extracted
 * from the module's advice bytecode by {@link ReferenceCreator}. This eliminates the need to
 * run ASM scanning at agent startup.
 *
 * <p>Adapted from Datadog's MuzzleGenerator (originally authored by the same developer).
 */
public class MuzzleGenerator {

    private static final String REFERENCE_INTERNAL = Type.getInternalName(Reference.class);
    private static final String REFERENCE_FIELD_INTERNAL = REFERENCE_INTERNAL + "$Field";
    private static final String REFERENCE_METHOD_INTERNAL = REFERENCE_INTERNAL + "$Method";
    private static final String REFERENCE_MATCHER_INTERNAL = Type.getInternalName(ReferenceMatcher.class);

    /**
     * CLI entry point for Gradle integration. Discovers all {@link InstrumentationModule}s via
     * ServiceLoader on the current classpath and generates {@code $Muzzle} classes in the given
     * output directory.
     *
     * @param args {@code args[0]} = output directory for generated .class files
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: MuzzleGenerator <classesDir>");
            System.exit(1);
        }
        File targetDir = new File(args[0]);
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        int count = 0;
        for (InstrumentationModule module : ServiceLoader.load(InstrumentationModule.class, cl)) {
            System.out.println("[muzzle] Generating $Muzzle for: " + module.name()
                    + " (" + module.getClass().getName() + ")");
            generate(module, targetDir, cl);
            count++;
        }
        System.out.println("[muzzle] Generated " + count + " $Muzzle class(es).");
    }

    /**
     * Generate the $Muzzle side-class for the given module and write it to the target directory.
     *
     * @param module the instrumentation module to generate references for
     * @param targetDir the output directory (typically the classes directory)
     * @param classLoader classloader to read advice class bytes from
     */
    public static void generate(InstrumentationModule module, File targetDir, ClassLoader classLoader) {
        String moduleInternalName = module.getClass().getName().replace('.', '/');
        File muzzleClassFile = new File(targetDir, moduleInternalName + "$Muzzle.class");

        try {
            muzzleClassFile.getParentFile().mkdirs();
            byte[] classBytes = generateMuzzleClass(module, moduleInternalName, classLoader);
            Files.write(muzzleClassFile.toPath(), classBytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write $Muzzle class for " + module.name(), e);
        }
    }

    /**
     * Generate the $Muzzle class bytecode.
     */
    static byte[] generateMuzzleClass(
            InstrumentationModule module, String moduleInternalName, ClassLoader classLoader) {

        Reference[] references = collectReferences(module, classLoader);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                moduleInternalName + "$Muzzle",
                null,
                "java/lang/Object",
                null);

        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create",
                "()L" + REFERENCE_MATCHER_INTERNAL + ";",
                null,
                null);

        mv.visitCode();

        // new ReferenceMatcher(new Reference[]{...})
        mv.visitTypeInsn(Opcodes.NEW, REFERENCE_MATCHER_INTERNAL);
        mv.visitInsn(Opcodes.DUP);

        // Create the Reference[] array
        pushInt(mv, references.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, REFERENCE_INTERNAL);

        for (int i = 0; i < references.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            writeReference(mv, references[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }

        // ReferenceMatcher(Reference...)
        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                REFERENCE_MATCHER_INTERNAL,
                "<init>",
                "([L" + REFERENCE_INTERNAL + ";)V",
                false);

        mv.visitInsn(Opcodes.ARETURN);

        mv.visitMaxs(0, 0); // COMPUTE_FRAMES handles this
        mv.visitEnd();
        cw.visitEnd();

        return cw.toByteArray();
    }

    /**
     * Collect all references from the module's advice bytecode, filtering out helper classes.
     */
    static Reference[] collectReferences(InstrumentationModule module, ClassLoader classLoader) {
        Map<String, Reference> references = new LinkedHashMap<>();
        Set<String> helperClasses = new HashSet<>(module.getHelperClassNames());

        for (TypeInstrumentation typeInst : module.typeInstrumentations()) {
            AdviceCollector collector = new AdviceCollector();
            typeInst.transform(collector);

            for (String adviceClass : collector.adviceClasses) {
                for (Map.Entry<String, Reference> entry :
                        ReferenceCreator.createReferencesFrom(adviceClass, classLoader).entrySet()) {
                    if (helperClasses.contains(entry.getKey())) {
                        continue;
                    }
                    Reference existing = references.get(entry.getKey());
                    if (existing == null) {
                        references.put(entry.getKey(), entry.getValue());
                    } else {
                        references.put(entry.getKey(), existing.merge(entry.getValue()));
                    }
                }
            }
        }

        return references.values().toArray(new Reference[0]);
    }

    // --- ASM bytecode writing helpers ---

    private static void writeReference(MethodVisitor mv, Reference ref) {
        mv.visitTypeInsn(Opcodes.NEW, REFERENCE_INTERNAL);
        mv.visitInsn(Opcodes.DUP);

        writeStringArray(mv, ref.sources);
        pushInt(mv, ref.flags);
        mv.visitLdcInsn(ref.className);
        if (ref.superName != null) {
            mv.visitLdcInsn(ref.superName);
        } else {
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        writeStringArray(mv, ref.interfaces);
        writeFields(mv, ref.fields);
        writeMethods(mv, ref.methods);

        mv.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                REFERENCE_INTERNAL,
                "<init>",
                "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
                        + "[L" + REFERENCE_FIELD_INTERNAL + ";"
                        + "[L" + REFERENCE_METHOD_INTERNAL + ";)V",
                false);
    }

    private static void writeStringArray(MethodVisitor mv, String[] strings) {
        pushInt(mv, strings.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/String");
        for (int i = 0; i < strings.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);
            mv.visitLdcInsn(strings[i]);
            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    private static void writeFields(MethodVisitor mv, Reference.Field[] fields) {
        pushInt(mv, fields.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, REFERENCE_FIELD_INTERNAL);
        for (int i = 0; i < fields.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);

            mv.visitTypeInsn(Opcodes.NEW, REFERENCE_FIELD_INTERNAL);
            mv.visitInsn(Opcodes.DUP);
            writeStringArray(mv, fields[i].sources);
            pushInt(mv, fields[i].flags);
            mv.visitLdcInsn(fields[i].name);
            mv.visitLdcInsn(fields[i].fieldType);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    REFERENCE_FIELD_INTERNAL,
                    "<init>",
                    "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
                    false);

            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    private static void writeMethods(MethodVisitor mv, Reference.Method[] methods) {
        pushInt(mv, methods.length);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, REFERENCE_METHOD_INTERNAL);
        for (int i = 0; i < methods.length; i++) {
            mv.visitInsn(Opcodes.DUP);
            pushInt(mv, i);

            mv.visitTypeInsn(Opcodes.NEW, REFERENCE_METHOD_INTERNAL);
            mv.visitInsn(Opcodes.DUP);
            writeStringArray(mv, methods[i].sources);
            pushInt(mv, methods[i].flags);
            mv.visitLdcInsn(methods[i].name);
            mv.visitLdcInsn(methods[i].methodType);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL,
                    REFERENCE_METHOD_INTERNAL,
                    "<init>",
                    "([Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)V",
                    false);

            mv.visitInsn(Opcodes.AASTORE);
        }
    }

    /**
     * Push an integer constant using the most compact bytecode instruction.
     */
    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    /**
     * A TypeTransformer that just collects advice class names without doing anything.
     */
    private static class AdviceCollector implements TypeTransformer {
        final Set<String> adviceClasses = new HashSet<>();

        @Override
        public void applyAdviceToMethod(
                ElementMatcher<? super MethodDescription> methodMatcher,
                String adviceClassName) {
            adviceClasses.add(adviceClassName);
        }
    }
}
