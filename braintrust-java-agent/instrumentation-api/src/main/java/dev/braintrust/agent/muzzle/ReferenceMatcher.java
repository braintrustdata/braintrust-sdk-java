package dev.braintrust.agent.muzzle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.pool.TypePool;

/**
 * Matches a set of {@link Reference}s against a classloader's classpath using ByteBuddy's
 * {@link TypePool} (which parses .class bytes without loading classes into the JVM).
 *
 * <p>Adapted from Datadog's muzzle implementation.
 */
public class ReferenceMatcher {

    private final Reference[] references;

    public ReferenceMatcher(Reference... references) {
        this.references = references;
    }

    public Reference[] getReferences() {
        return references;
    }

    /**
     * Fail-fast check: returns false at first mismatch found.
     *
     * @param loader classloader to validate against (null for bootstrap)
     * @return true if all references match
     */
    public boolean matches(ClassLoader loader) {
        List<Reference.Mismatch> mismatches = new ArrayList<>();
        TypePool typePool = createTypePool(loader);
        for (Reference reference : references) {
            if (!checkMatch(typePool, reference, loader, mismatches)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns all mismatches (for debug logging).
     *
     * @param loader classloader to validate against (null for bootstrap)
     * @return list of all mismatches
     */
    public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
        List<Reference.Mismatch> mismatches = new ArrayList<>();
        TypePool typePool = createTypePool(loader);
        for (Reference reference : references) {
            checkMatch(typePool, reference, loader, mismatches);
        }
        return mismatches;
    }

    private static TypePool createTypePool(ClassLoader loader) {
        return TypePool.Default.WithLazyResolution.of(
                loader != null
                        ? ClassFileLocator.ForClassLoader.of(loader)
                        : ClassFileLocator.ForClassLoader.ofBootLoader());
    }

    private static boolean checkMatch(
            TypePool typePool,
            Reference reference,
            ClassLoader loader,
            List<Reference.Mismatch> mismatches) {
        try {
            TypePool.Resolution resolution = typePool.describe(reference.className);
            if (!resolution.isResolved()) {
                mismatches.add(
                        new Reference.Mismatch.MissingClass(reference.sources, reference.className));
                return false;
            }
            return checkMatch(reference, resolution.resolve(), mismatches);
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.startsWith("Cannot resolve type description for ")) {
                String className = message.replace("Cannot resolve type description for ", "");
                mismatches.add(new Reference.Mismatch.MissingClass(reference.sources, className));
            } else {
                mismatches.add(
                        new Reference.Mismatch.ReferenceCheckError(
                                e, reference, loader != null ? loader.toString() : "<bootstrap>"));
            }
            return false;
        }
    }

    static boolean checkMatch(
            Reference reference,
            TypeDescription typeOnClasspath,
            List<Reference.Mismatch> mismatches) {
        int previousMismatchCount = mismatches.size();

        if (!Reference.matches(reference.flags, typeOnClasspath.getModifiers())) {
            mismatches.add(
                    new Reference.Mismatch.MissingFlag(
                            reference.sources,
                            reference.className,
                            reference.flags,
                            typeOnClasspath.getModifiers()));
        }

        Map<FieldKey, Reference.Field> indexedFields = indexFields(reference.fields);
        Map<MethodKey, Reference.Method> indexedMethods = indexMethods(reference.methods);
        traverseHierarchy(reference, typeOnClasspath, indexedMethods, indexedFields, mismatches);
        if (!indexedMethods.isEmpty()) {
            findInterfaceMethods(
                    reference, typeOnClasspath, indexedMethods, mismatches, new HashSet<>());
        }

        for (Reference.Field missingField : indexedFields.values()) {
            mismatches.add(
                    new Reference.Mismatch.MissingField(
                            missingField.sources,
                            reference.className,
                            missingField.name,
                            missingField.fieldType));
        }
        for (Reference.Method missingMethod : indexedMethods.values()) {
            mismatches.add(
                    new Reference.Mismatch.MissingMethod(
                            missingMethod.sources,
                            reference.className,
                            missingMethod.name,
                            missingMethod.methodType));
        }

        return previousMismatchCount == mismatches.size();
    }

    // --- Hierarchy traversal ---

    private static void traverseHierarchy(
            Reference reference,
            TypeDescription typeOnClasspath,
            Map<MethodKey, Reference.Method> methodsToFind,
            Map<FieldKey, Reference.Field> fieldsToFind,
            List<Reference.Mismatch> flagMismatches) {
        findFieldsForType(reference, typeOnClasspath, fieldsToFind, flagMismatches);
        findMethodsForType(reference, typeOnClasspath, methodsToFind, flagMismatches);
        if (!fieldsToFind.isEmpty() || !methodsToFind.isEmpty()) {
            TypeDescription.Generic superClass = typeOnClasspath.getSuperClass();
            if (superClass != null) {
                traverseHierarchy(
                        reference,
                        superClass.asErasure(),
                        methodsToFind,
                        fieldsToFind,
                        flagMismatches);
            }
        }
    }

    private static void findFieldsForType(
            Reference reference,
            TypeDescription typeOnClasspath,
            Map<FieldKey, Reference.Field> fieldsToFind,
            List<Reference.Mismatch> flagMismatches) {
        if (!fieldsToFind.isEmpty()) {
            for (FieldDescription.InDefinedShape fieldType : typeOnClasspath.getDeclaredFields()) {
                String descriptor = fieldType.getType().asErasure().getDescriptor();
                FieldKey key = new FieldKey(fieldType.getInternalName(), descriptor);
                Reference.Field found = fieldsToFind.remove(key);
                if (found != null) {
                    if (!Reference.matches(found.flags, fieldType.getModifiers())) {
                        String desc = reference.className + "#" + found.name + found.fieldType;
                        flagMismatches.add(
                                new Reference.Mismatch.MissingFlag(
                                        found.sources, desc, found.flags, fieldType.getModifiers()));
                        break;
                    }
                }
                if (fieldsToFind.isEmpty()) break;
            }
        }
    }

    private static void findMethodsForType(
            Reference reference,
            TypeDescription typeOnClasspath,
            Map<MethodKey, Reference.Method> methodsToFind,
            List<Reference.Mismatch> flagMismatches) {
        if (!methodsToFind.isEmpty()) {
            for (MethodDescription.InDefinedShape methodDesc :
                    typeOnClasspath.getDeclaredMethods()) {
                MethodKey key = new MethodKey(methodDesc.getInternalName(), methodDesc.getDescriptor());
                Reference.Method found = methodsToFind.remove(key);
                if (found != null) {
                    if (!Reference.matches(found.flags, methodDesc.getModifiers())) {
                        String desc = reference.className + "#" + found.name + found.methodType;
                        flagMismatches.add(
                                new Reference.Mismatch.MissingFlag(
                                        found.sources, desc, found.flags, methodDesc.getModifiers()));
                        break;
                    }
                }
                if (methodsToFind.isEmpty()) break;
            }
        }
    }

    private static void findInterfaceMethods(
            Reference reference,
            TypeDescription typeOnClasspath,
            Map<MethodKey, Reference.Method> methodsToFind,
            List<Reference.Mismatch> flagMismatches,
            Set<TypeDescription> visitedInterfaces) {
        if (!methodsToFind.isEmpty()) {
            for (TypeDescription.Generic interfaceType : typeOnClasspath.getInterfaces()) {
                TypeDescription erasureType = interfaceType.asErasure();
                findMethodsForType(reference, erasureType, methodsToFind, flagMismatches);
                if (methodsToFind.isEmpty()) break;
                if (visitedInterfaces.add(erasureType)) {
                    findInterfaceMethods(
                            reference, erasureType, methodsToFind, flagMismatches, visitedInterfaces);
                }
            }
        }
    }

    // --- Index key types (replacing DD's Pair<String,String>) ---

    private static Map<FieldKey, Reference.Field> indexFields(Reference.Field[] fields) {
        Map<FieldKey, Reference.Field> map = new HashMap<>(fields.length * 4 / 3 + 1);
        for (Reference.Field field : fields) {
            map.put(new FieldKey(field.name, field.fieldType), field);
        }
        return map;
    }

    private static Map<MethodKey, Reference.Method> indexMethods(Reference.Method[] methods) {
        Map<MethodKey, Reference.Method> map = new HashMap<>(methods.length * 4 / 3 + 1);
        for (Reference.Method method : methods) {
            map.put(new MethodKey(method.name, method.methodType), method);
        }
        return map;
    }

    private record FieldKey(String name, String descriptor) {}

    private record MethodKey(String name, String descriptor) {}
}
