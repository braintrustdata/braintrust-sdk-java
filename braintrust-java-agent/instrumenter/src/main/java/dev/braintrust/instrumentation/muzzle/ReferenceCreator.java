package dev.braintrust.instrumentation.muzzle;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.FieldVisitor;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * ASM ClassVisitor that scans instrumentation advice bytecode and extracts all outgoing references
 * to external classes, methods, and fields.
 */
public class ReferenceCreator extends ClassVisitor {

    private static final int UNDEFINED_LINE = -1;

    private static final Set<String> OBJECT_METHODS = new HashSet<>();

    static {
        for (Method m : Object.class.getMethods()) {
            OBJECT_METHODS.add(m.getName() + Type.getMethodDescriptor(m));
        }
    }

    /**
     * Generate all references reachable from a given class.
     *
     * @param entryPointClassName starting point for generating references (dotted name)
     * @param loader classloader used to read class bytes
     * @return map of [className -> Reference]
     */
    public static Map<String, Reference> createReferencesFrom(
            String entryPointClassName, ClassLoader loader) {
        return createReferencesFrom(entryPointClassName, loader, Set.of());
    }

    /**
     * Generate all references reachable from a given class, also following into helper classes.
     *
     * <p>Helper classes are agent classes that get injected into the target classloader at runtime.
     * Their outgoing references must also be checked by muzzle, since a missing dependency in a
     * helper will cause a runtime failure.
     *
     * @param entryPointClassName starting point for generating references (dotted name)
     * @param loader classloader used to read class bytes
     * @param helperClassNames helper class names whose references should also be followed
     * @return map of [className -> Reference]
     */
    public static Map<String, Reference> createReferencesFrom(
            String entryPointClassName, ClassLoader loader, Set<String> helperClassNames) {
        Set<String> visitedSources = new HashSet<>();
        Map<String, Reference> references = new LinkedHashMap<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(entryPointClassName);
        // Always scan all declared helpers unconditionally — don't rely on bytecode reachability.
        // A helper may be dispatched to via reflection (e.g. Class.forName + Method.invoke) which
        // is invisible to the static scanner, but its references still need to be muzzle-checked.
        for (String helperClassName : helperClassNames) {
            if (!helperClassName.equals(entryPointClassName)) {
                queue.add(helperClassName);
            }
        }

        while (!queue.isEmpty()) {
            String className = queue.remove();
            if (!visitedSources.add(className)) {
                continue;
            }
            String resourceName = className.replace('.', '/') + ".class";
            try (InputStream in = loader.getResourceAsStream(resourceName)) {
                if (in == null) {
                    continue;
                }
                ReferenceCreator cv = new ReferenceCreator(null);
                ClassReader reader = new ClassReader(in);
                reader.accept(cv, ClassReader.SKIP_FRAMES);

                for (Map.Entry<String, Reference> entry : cv.getReferences().entrySet()) {
                    if (!visitedSources.contains(entry.getKey())
                            && helperClassNames.contains(entry.getKey())) {
                        queue.add(entry.getKey());
                    }
                    Reference existing = references.get(entry.getKey());
                    if (existing == null) {
                        references.put(entry.getKey(), entry.getValue());
                    } else {
                        references.put(entry.getKey(), existing.merge(entry.getValue()));
                    }
                }
            } catch (Throwable t) {
                throw new IllegalStateException("Error reading class " + className, t);
            }
        }
        return references;
    }

    private static boolean samePackage(String from, String to) {
        int fromLength = from.lastIndexOf('/');
        int toLength = to.lastIndexOf('/');
        return fromLength == toLength && from.regionMatches(0, to, 0, fromLength + 1);
    }

    private static int computeMinimumClassAccess(String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return 0;
        } else if (samePackage(from, to)) {
            return Reference.EXPECTS_NON_PRIVATE;
        } else {
            return Reference.EXPECTS_PUBLIC;
        }
    }

    private static int computeMinimumFieldAccess(String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return 0;
        } else if (samePackage(from, to)) {
            return Reference.EXPECTS_NON_PRIVATE;
        } else {
            return Reference.EXPECTS_PUBLIC_OR_PROTECTED;
        }
    }

    private static int computeMinimumMethodAccess(String from, String to) {
        if (from.equalsIgnoreCase(to)) {
            return 0;
        } else if (samePackage(from, to)) {
            return Reference.EXPECTS_NON_PRIVATE;
        } else {
            return Reference.EXPECTS_PUBLIC_OR_PROTECTED;
        }
    }

    private static Type underlyingType(Type type) {
        while (type.getSort() == Type.ARRAY) {
            type = type.getElementType();
        }
        return type;
    }

    private final Map<String, Reference> references = new LinkedHashMap<>();
    private String refSourceClassName;
    private String refSourceTypeInternalName;

    private ReferenceCreator(ClassVisitor classVisitor) {
        super(Opcodes.ASM9, classVisitor);
    }

    public Map<String, Reference> getReferences() {
        return references;
    }

    private void addReference(Reference ref) {
        if (!ref.className.startsWith("java.")) {
            Reference existing = references.get(ref.className);
            if (existing == null) {
                references.put(ref.className, ref);
            } else {
                references.put(ref.className, existing.merge(ref));
            }
        }
    }

    @Override
    public void visit(
            int version,
            int access,
            String name,
            String signature,
            String superName,
            String[] interfaces) {
        refSourceClassName = Reference.toClassName(name);
        refSourceTypeInternalName = Type.getType("L" + name + ";").getInternalName();

        for (String iface : interfaces) {
            if (!ignoreReference(iface)) {
                addReference(
                        new Reference.Builder(iface)
                                .withSource(refSourceClassName, UNDEFINED_LINE)
                                .withFlag(Reference.EXPECTS_PUBLIC)
                                .build());
            }
        }
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(
            int access, String name, String descriptor, String signature, Object value) {
        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(
            int access, String name, String descriptor, String signature, String[] exceptions) {
        return new AdviceReferenceMethodVisitor(
                super.visitMethod(access, name, descriptor, signature, exceptions));
    }

    private class AdviceReferenceMethodVisitor extends MethodVisitor {
        private int currentLineNumber = UNDEFINED_LINE;

        AdviceReferenceMethodVisitor(MethodVisitor methodVisitor) {
            super(Opcodes.ASM9, methodVisitor);
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            currentLineNumber = line;
            super.visitLineNumber(line, start);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (ignoreReference(owner)) {
                return;
            }

            Type ownerType =
                    owner.startsWith("[")
                            ? underlyingType(Type.getType(owner))
                            : Type.getType("L" + owner + ";");
            Type fieldType = Type.getType(descriptor);
            String ownerInternal = ownerType.getInternalName();

            int fieldFlags = 0;
            fieldFlags |= computeMinimumFieldAccess(refSourceTypeInternalName, ownerInternal);
            fieldFlags |=
                    opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC
                            ? Reference.EXPECTS_STATIC
                            : Reference.EXPECTS_NON_STATIC;

            addReference(
                    new Reference.Builder(ownerInternal)
                            .withSource(refSourceClassName, currentLineNumber)
                            .withFlag(
                                    computeMinimumClassAccess(
                                            refSourceTypeInternalName, ownerInternal))
                            .withField(
                                    new String[] {refSourceClassName + ":" + currentLineNumber},
                                    fieldFlags,
                                    name,
                                    fieldType)
                            .build());

            Type underlying = underlyingType(fieldType);
            String underlyingInternal = underlying.getInternalName();
            if (underlying.getSort() == Type.OBJECT && !ignoreReference(underlyingInternal)) {
                addReference(
                        new Reference.Builder(underlyingInternal)
                                .withSource(refSourceClassName, currentLineNumber)
                                .withFlag(
                                        computeMinimumClassAccess(
                                                refSourceTypeInternalName, underlyingInternal))
                                .build());
            }
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (ignoreReference(owner) || ignoreObjectMethod(name, descriptor)) {
                return;
            }

            Type methodType = Type.getMethodType(descriptor);

            // Reference return type
            Type returnType = underlyingType(methodType.getReturnType());
            String returnInternal = returnType.getInternalName();
            if (returnType.getSort() == Type.OBJECT && !ignoreReference(returnInternal)) {
                addReference(
                        new Reference.Builder(returnInternal)
                                .withSource(refSourceClassName, currentLineNumber)
                                .withFlag(
                                        computeMinimumClassAccess(
                                                refSourceTypeInternalName, returnInternal))
                                .build());
            }

            // Reference param types
            for (Type paramType : methodType.getArgumentTypes()) {
                paramType = underlyingType(paramType);
                String paramInternal = paramType.getInternalName();
                if (paramType.getSort() == Type.OBJECT && !ignoreReference(paramInternal)) {
                    addReference(
                            new Reference.Builder(paramInternal)
                                    .withSource(refSourceClassName, currentLineNumber)
                                    .withFlag(
                                            computeMinimumClassAccess(
                                                    refSourceTypeInternalName, paramInternal))
                                    .build());
                }
            }

            Type ownerType =
                    owner.startsWith("[")
                            ? underlyingType(Type.getType(owner))
                            : Type.getType("L" + owner + ";");
            String ownerInternal = ownerType.getInternalName();

            int methodFlags = 0;
            methodFlags |=
                    opcode == Opcodes.INVOKESTATIC
                            ? Reference.EXPECTS_STATIC
                            : Reference.EXPECTS_NON_STATIC;
            methodFlags |= computeMinimumMethodAccess(refSourceTypeInternalName, ownerInternal);

            addReference(
                    new Reference.Builder(ownerInternal)
                            .withSource(refSourceClassName, currentLineNumber)
                            .withFlag(
                                    isInterface
                                            ? Reference.EXPECTS_INTERFACE
                                            : Reference.EXPECTS_NON_INTERFACE)
                            .withFlag(
                                    computeMinimumClassAccess(
                                            refSourceTypeInternalName, ownerInternal))
                            .withMethod(
                                    new String[] {refSourceClassName + ":" + currentLineNumber},
                                    methodFlags,
                                    name,
                                    methodType.getReturnType(),
                                    methodType.getArgumentTypes())
                            .build());
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitTypeInsn(int opcode, String stype) {
            if (ignoreReference(stype)) {
                return;
            }
            Type type = underlyingType(Type.getObjectType(stype));
            if (ignoreReference(type.getInternalName())) {
                return;
            }
            addReference(
                    new Reference.Builder(type.getInternalName())
                            .withSource(refSourceClassName, currentLineNumber)
                            .withFlag(
                                    computeMinimumClassAccess(
                                            refSourceTypeInternalName, type.getInternalName()))
                            .build());
            super.visitTypeInsn(opcode, stype);
        }

        @Override
        public void visitInvokeDynamicInsn(
                String name,
                String descriptor,
                Handle bootstrapMethodHandle,
                Object... bootstrapMethodArguments) {
            addReference(
                    new Reference.Builder(bootstrapMethodHandle.getOwner())
                            .withSource(refSourceClassName, currentLineNumber)
                            .withFlag(
                                    computeMinimumClassAccess(
                                            refSourceTypeInternalName,
                                            Type.getObjectType(bootstrapMethodHandle.getOwner())
                                                    .getInternalName()))
                            .build());
            for (Object arg : bootstrapMethodArguments) {
                if (arg instanceof Handle) {
                    Handle handle = (Handle) arg;
                    addReference(
                            new Reference.Builder(handle.getOwner())
                                    .withSource(refSourceClassName, currentLineNumber)
                                    .withFlag(
                                            computeMinimumClassAccess(
                                                    refSourceTypeInternalName,
                                                    Type.getObjectType(handle.getOwner())
                                                            .getInternalName()))
                                    .build());
                }
            }
            super.visitInvokeDynamicInsn(
                    name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type) {
                Type type = underlyingType((Type) value);
                String typeInternal = type.getInternalName();
                if (type.getSort() == Type.OBJECT && !ignoreReference(typeInternal)) {
                    addReference(
                            new Reference.Builder(typeInternal)
                                    .withSource(refSourceClassName, currentLineNumber)
                                    .withFlag(
                                            computeMinimumClassAccess(
                                                    refSourceTypeInternalName, typeInternal))
                                    .build());
                }
            }
            super.visitLdcInsn(value);
        }
    }

    /** Returns true if this reference should be ignored (always available at runtime). */
    private static boolean ignoreReference(String name) {
        String dottedName = name.replace('/', '.');
        if (dottedName.startsWith("[")) {
            int componentMarker = dottedName.lastIndexOf("[L");
            if (componentMarker < 0) {
                return true; // primitive array
            } else {
                dottedName = dottedName.substring(componentMarker + 2);
            }
        }
        // Core JDK types are always available
        if (dottedName.startsWith("java.")) {
            return true;
        }
        return false;
    }

    private static boolean ignoreObjectMethod(String methodName, String methodDescriptor) {
        return OBJECT_METHODS.contains(methodName + methodDescriptor);
    }
}
