package dev.braintrust.agent.muzzle;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

import static java.util.Arrays.asList;

/**
 * An immutable reference to a JVM class, describing the expected shape (fields, methods, flags)
 * that an instrumentation requires from a target library.
 *
 * <p>Adapted from Datadog's muzzle implementation (originally authored by the same developer).
 */
public class Reference {
    public final String[] sources;
    public final int flags;
    public final String className;
    public final String superName;
    public final String[] interfaces;
    public final Field[] fields;
    public final Method[] methods;

    public Reference(
            String[] sources,
            int flags,
            String className,
            String superName,
            String[] interfaces,
            Field[] fields,
            Method[] methods) {
        this.sources = sources;
        this.flags = flags;
        this.className = className;
        this.superName = superName;
        this.interfaces = interfaces;
        this.fields = fields;
        this.methods = methods;
    }

    /** Merge this reference with another reference to the same class. */
    public Reference merge(Reference other) {
        if (!other.className.equals(className)) {
            throw new IllegalStateException("illegal merge " + this + " != " + other);
        }
        return new Reference(
                mergeArrays(sources, other.sources),
                mergeFlags(flags, other.flags),
                className,
                this.superName != null ? this.superName : other.superName,
                mergeArrays(interfaces, other.interfaces),
                mergeFields(fields, other.fields),
                mergeMethods(methods, other.methods));
    }

    @Override
    public String toString() {
        return "Reference<" + className + ">";
    }

    // --- Flag constants (bitmask) ---

    public static final int EXPECTS_PUBLIC = 1;
    public static final int EXPECTS_PUBLIC_OR_PROTECTED = 2;
    public static final int EXPECTS_NON_PRIVATE = 4;
    public static final int EXPECTS_STATIC = 8;
    public static final int EXPECTS_NON_STATIC = 16;
    public static final int EXPECTS_INTERFACE = 32;
    public static final int EXPECTS_NON_INTERFACE = 64;
    public static final int EXPECTS_NON_FINAL = 128;

    /** Check whether the given ASM modifiers satisfy the expected flags. */
    public static boolean matches(int flags, int modifiers) {
        if ((flags & EXPECTS_PUBLIC) != 0 && (modifiers & Opcodes.ACC_PUBLIC) == 0) {
            return false;
        } else if ((flags & EXPECTS_PUBLIC_OR_PROTECTED) != 0
                && (modifiers & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0) {
            return false;
        } else if ((flags & EXPECTS_NON_PRIVATE) != 0 && (modifiers & Opcodes.ACC_PRIVATE) != 0) {
            return false;
        } else if ((flags & EXPECTS_STATIC) != 0 && (modifiers & Opcodes.ACC_STATIC) == 0) {
            return false;
        } else if ((flags & EXPECTS_NON_STATIC) != 0 && (modifiers & Opcodes.ACC_STATIC) != 0) {
            return false;
        } else if ((flags & EXPECTS_INTERFACE) != 0 && (modifiers & Opcodes.ACC_INTERFACE) == 0) {
            return false;
        } else if ((flags & EXPECTS_NON_INTERFACE) != 0 && (modifiers & Opcodes.ACC_INTERFACE) != 0) {
            return false;
        } else if ((flags & EXPECTS_NON_FINAL) != 0 && (modifiers & Opcodes.ACC_FINAL) != 0) {
            return false;
        }
        return true;
    }

    // --- Nested types ---

    public static class Field {
        public final String[] sources;
        public final int flags;
        public final String name;
        public final String fieldType;

        public Field(String[] sources, int flags, String name, String fieldType) {
            this.sources = sources;
            this.flags = flags;
            this.name = name;
            this.fieldType = fieldType;
        }

        public Field merge(Field other) {
            if (!name.equals(other.name) || !fieldType.equals(other.fieldType)) {
                throw new IllegalStateException("illegal merge " + this + " != " + other);
            }
            return new Field(
                    mergeArrays(sources, other.sources),
                    mergeFlags(flags, other.flags),
                    name,
                    fieldType);
        }

        @Override
        public String toString() {
            return name + fieldType;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Field) {
                return name.equals(((Field) o).name);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static class Method {
        public final String[] sources;
        public final int flags;
        public final String name;
        public final String methodType;

        public Method(String[] sources, int flags, String name, String methodType) {
            this.sources = sources;
            this.flags = flags;
            this.name = name;
            this.methodType = methodType;
        }

        public Method merge(Method other) {
            if (!equals(other)) {
                throw new IllegalStateException("illegal merge " + this + " != " + other);
            }
            return new Method(
                    mergeArrays(sources, other.sources),
                    mergeFlags(flags, other.flags),
                    name,
                    methodType);
        }

        @Override
        public String toString() {
            return name + methodType;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Method) {
                Method m = (Method) o;
                return name.equals(m.name) && methodType.equals(m.methodType);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return toString().hashCode();
        }
    }

    // --- Mismatch types ---

    public abstract static class Mismatch {
        private final String[] mismatchSources;

        Mismatch(String[] mismatchSources) {
            this.mismatchSources = mismatchSources;
        }

        @Override
        public String toString() {
            if (mismatchSources.length > 0) {
                return mismatchSources[0] + " " + getMismatchDetails();
            } else {
                return "<no-source> " + getMismatchDetails();
            }
        }

        abstract String getMismatchDetails();

        public static class MissingClass extends Mismatch {
            private final String className;

            public MissingClass(String[] sources, String className) {
                super(sources);
                this.className = className;
            }

            @Override
            String getMismatchDetails() {
                return "Missing class " + className;
            }
        }

        public static class MissingFlag extends Mismatch {
            private final int expectedFlag;
            private final String classMethodOrFieldDesc;
            private final int foundAccess;

            public MissingFlag(
                    String[] sources, String classMethodOrFieldDesc, int expectedFlag, int foundAccess) {
                super(sources);
                this.classMethodOrFieldDesc = classMethodOrFieldDesc;
                this.expectedFlag = expectedFlag;
                this.foundAccess = foundAccess;
            }

            @Override
            String getMismatchDetails() {
                return classMethodOrFieldDesc
                        + " expected "
                        + prettyPrint(expectedFlag)
                        + " found "
                        + Modifier.toString(foundAccess);
            }
        }

        public static class MissingField extends Mismatch {
            private final String className;
            private final String fieldName;
            private final String fieldDesc;

            public MissingField(String[] sources, String className, String fieldName, String fieldDesc) {
                super(sources);
                this.className = className;
                this.fieldName = fieldName;
                this.fieldDesc = fieldDesc;
            }

            @Override
            String getMismatchDetails() {
                return "Missing field " + className + "#" + fieldName + fieldDesc;
            }
        }

        public static class MissingMethod extends Mismatch {
            private final String className;
            private final String method;
            private final String methodType;

            public MissingMethod(String[] sources, String className, String method, String methodType) {
                super(sources);
                this.className = className;
                this.method = method;
                this.methodType = methodType;
            }

            @Override
            String getMismatchDetails() {
                return "Missing method " + className + "#" + method + methodType;
            }
        }

        public static class ReferenceCheckError extends Mismatch {
            private final Exception referenceCheckException;
            private final Reference referenceBeingChecked;
            private final String location;

            public ReferenceCheckError(
                    Exception referenceCheckException, Reference referenceBeingChecked, String location) {
                super(new String[0]);
                this.referenceCheckException = referenceCheckException;
                this.referenceBeingChecked = referenceBeingChecked;
                this.location = location;
            }

            @Override
            String getMismatchDetails() {
                StringWriter sw = new StringWriter();
                sw.write("Failed to generate reference check for: ");
                sw.write(referenceBeingChecked.toString());
                sw.write(" at ");
                sw.write(location);
                sw.write("\n");
                referenceCheckException.printStackTrace(new PrintWriter(sw));
                return sw.toString();
            }
        }
    }

    // --- Builder ---

    public static class Builder {
        private final Set<String> sources = new LinkedHashSet<>();
        private int flags = 0;
        private final String className;
        private String superName = null;
        private final Set<String> interfaces = new LinkedHashSet<>();
        private final List<Field> fields = new ArrayList<>();
        private final List<Method> methods = new ArrayList<>();

        public Builder(String className) {
            this.className = className;
        }

        public Builder withSuperName(String superName) {
            this.superName = superName;
            return this;
        }

        public Builder withInterface(String interfaceName) {
            interfaces.add(interfaceName);
            return this;
        }

        public Builder withSource(String sourceName, int line) {
            sources.add(sourceName + ":" + line);
            return this;
        }

        public Builder withFlag(int flag) {
            flags |= flag;
            return this;
        }

        public Builder withField(
                String[] sources, int fieldFlags, String fieldName, Type fieldType) {
            return withField(sources, fieldFlags, fieldName, fieldType.getDescriptor());
        }

        public Builder withField(
                String[] sources, int fieldFlags, String fieldName, String fieldType) {
            Field field = new Field(sources, fieldFlags, fieldName, fieldType);
            int existingIndex = fields.indexOf(field);
            if (existingIndex == -1) {
                fields.add(field);
            } else {
                fields.set(existingIndex, field.merge(fields.get(existingIndex)));
            }
            return this;
        }

        public Builder withMethod(
                String[] sources,
                int methodFlags,
                String methodName,
                Type returnType,
                Type... parameterTypes) {
            String[] paramDescs = new String[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                paramDescs[i] = parameterTypes[i].getDescriptor();
            }
            return withMethod(sources, methodFlags, methodName, returnType.getDescriptor(), paramDescs);
        }

        public Builder withMethod(
                String[] sources,
                int methodFlags,
                String methodName,
                String returnType,
                String... parameterTypes) {
            StringBuilder methodType = new StringBuilder().append('(');
            for (String parameterType : parameterTypes) {
                methodType.append(parameterType);
            }
            methodType.append(')').append(returnType);
            Method method = new Method(sources, methodFlags, methodName, methodType.toString());
            int existingIndex = methods.indexOf(method);
            if (existingIndex == -1) {
                methods.add(method);
            } else {
                methods.set(existingIndex, method.merge(methods.get(existingIndex)));
            }
            return this;
        }

        public Reference build() {
            return new Reference(
                    sources.toArray(new String[0]),
                    flags,
                    toClassName(className),
                    superName != null ? toClassName(superName) : null,
                    interfaces.toArray(new String[0]),
                    fields.toArray(new Field[0]),
                    methods.toArray(new Method[0]));
        }
    }

    // --- Utility methods ---

    @SuppressWarnings("unchecked")
    static <E> E[] mergeArrays(E[] array1, E[] array2) {
        Set<E> set = new LinkedHashSet<>((array1.length + array2.length) * 4 / 3);
        set.addAll(asList(array1));
        set.addAll(asList(array2));
        return set.toArray((E[]) Array.newInstance(array1.getClass().getComponentType(), set.size()));
    }

    static int mergeFlags(int flags1, int flags2) {
        return flags1 | flags2;
    }

    private static Field[] mergeFields(Field[] fields1, Field[] fields2) {
        List<Field> merged = new ArrayList<>(asList(fields1));
        for (Field field : fields2) {
            int i = merged.indexOf(field);
            if (i == -1) {
                merged.add(field);
            } else {
                merged.set(i, merged.get(i).merge(field));
            }
        }
        return merged.toArray(new Field[0]);
    }

    private static Method[] mergeMethods(Method[] methods1, Method[] methods2) {
        List<Method> merged = new ArrayList<>(asList(methods1));
        for (Method method : methods2) {
            int i = merged.indexOf(method);
            if (i == -1) {
                merged.add(method);
            } else {
                merged.set(i, merged.get(i).merge(method));
            }
        }
        return merged.toArray(new Method[0]);
    }

    /** Convert an internal name (with /) to a class name (with .). */
    static String toClassName(String internalName) {
        return internalName.replace('/', '.');
    }

    /** Convert a class name (with .) to a resource name (with / and .class suffix). */
    static String toResourceName(String className) {
        return className.replace('.', '/') + ".class";
    }

    static String prettyPrint(int flags) {
        StringBuilder buf = new StringBuilder();
        if ((flags & EXPECTS_PUBLIC) != 0) buf.append("public ");
        if ((flags & EXPECTS_PUBLIC_OR_PROTECTED) != 0) buf.append("public_or_protected ");
        if ((flags & EXPECTS_NON_PRIVATE) != 0) buf.append("non_private ");
        if ((flags & EXPECTS_STATIC) != 0) buf.append("static ");
        if ((flags & EXPECTS_NON_STATIC) != 0) buf.append("non_static ");
        if ((flags & EXPECTS_INTERFACE) != 0) buf.append("interface ");
        if ((flags & EXPECTS_NON_INTERFACE) != 0) buf.append("non_interface ");
        if ((flags & EXPECTS_NON_FINAL) != 0) buf.append("non_final ");
        return buf.toString();
    }
}
