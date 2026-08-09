package io.github.jutil.columnarprojection.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a columnar {@code ProjectionStore} implementation for every
 * interface annotated with {@code ProjectionSchema}.
 *
 * <p>The processor is normally discovered by the Java compiler through its
 * service-provider configuration. Applications should use the generated
 * implementation through {@code ProjectionStores}, rather than referring to
 * its generated name directly.
 */
@SupportedAnnotationTypes(
        "io.github.jutil.columnarprojection.ProjectionSchema")
public final class ProjectionSchemaProcessor extends AbstractProcessor {

    private static final String GENERATED_CLASS_SUFFIX =
            "__ColumnarProjectionStore";

    private Elements elements;
    private Types types;
    private Filer filer;
    private Messager messager;
    private TypeMirror runtimeExceptionType;
    private TypeMirror errorType;
    private TypeElement projectionSchemaType;
    private TypeElement objectType;
    private final List<ExecutableElement> objectMethods =
            new ArrayList<ExecutableElement>();
    private final Set<String> generatedTypes = new LinkedHashSet<String>();

    /**
     * Creates a processor. Compiler service discovery uses this constructor.
     */
    public ProjectionSchemaProcessor() {
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        elements = processingEnv.getElementUtils();
        types = processingEnv.getTypeUtils();
        filer = processingEnv.getFiler();
        messager = processingEnv.getMessager();
        runtimeExceptionType = elements
                .getTypeElement(RuntimeException.class.getCanonicalName())
                .asType();
        errorType = elements.getTypeElement(Error.class.getCanonicalName())
                .asType();
        projectionSchemaType = elements.getTypeElement(
                "io.github.jutil.columnarprojection.ProjectionSchema");
        objectType = elements.getTypeElement(Object.class.getCanonicalName());
        for (Element member : objectType.getEnclosedElements()) {
            if (member.getKind() == ElementKind.METHOD
                    && !member.getModifiers().contains(Modifier.STATIC)) {
                objectMethods.add((ExecutableElement) member);
            }
        }
    }

    /**
     * Supports the latest source level understood by the compiler executing
     * this Java-8-compatible processor.
     *
     * @return the latest source version supported by the running compiler
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /** {@inheritDoc} */
    @Override
    public boolean process(
            Set<? extends TypeElement> annotations,
            RoundEnvironment roundEnvironment) {
        if (projectionSchemaType == null) {
            return false;
        }
        for (Element element : roundEnvironment
                .getElementsAnnotatedWith(projectionSchemaType)) {
            processSchema(element);
        }
        return true;
    }

    private void processSchema(Element element) {
        if (element.getKind() != ElementKind.INTERFACE) {
            error(element, "@ProjectionSchema may only annotate an interface");
            return;
        }

        TypeElement schema = (TypeElement) element;
        boolean valid = validateSchemaType(schema);
        List<Accessor> accessors = collectAccessors(schema);
        if (accessors == null) {
            valid = false;
        }
        if (!valid) {
            return;
        }

        String generatedName = generatedQualifiedName(schema);
        if (!generatedTypes.add(generatedName)) {
            return;
        }

        try {
            JavaFileObject sourceFile =
                    filer.createSourceFile(generatedName, schema);
            Writer writer = sourceFile.openWriter();
            try {
                writer.write(generateSource(schema, accessors));
            } finally {
                writer.close();
            }
        } catch (IOException exception) {
            error(schema,
                    "Could not generate projection store " + generatedName
                            + ": " + exception.getMessage());
        }
    }

    private boolean validateSchemaType(TypeElement schema) {
        boolean valid = true;
        if (!schema.getTypeParameters().isEmpty()) {
            error(schema, "Projection schema interfaces must not be generic");
            valid = false;
        }
        TypeElement rawSupertype = findRawGenericSupertype(schema);
        if (rawSupertype != null) {
            error(schema,
                    "Projection schema interfaces must not extend raw generic "
                            + "interfaces: "
                            + rawSupertype.getQualifiedName());
            valid = false;
        }
        if (schema.getNestingKind() != NestingKind.TOP_LEVEL
                && schema.getNestingKind() != NestingKind.MEMBER) {
            error(schema,
                    "Projection schema interfaces must be top-level or member "
                            + "interfaces");
            valid = false;
        }

        Element current = schema;
        while (current instanceof TypeElement) {
            if (current.getModifiers().contains(Modifier.PRIVATE)) {
                error(schema,
                        "Projection schemas and their enclosing types must not "
                                + "be private");
                valid = false;
                break;
            }
            current = current.getEnclosingElement();
        }
        return valid;
    }

    private TypeElement findRawGenericSupertype(TypeElement schema) {
        Set<String> visited = new LinkedHashSet<String>();
        for (TypeMirror supertype : types.directSupertypes(schema.asType())) {
            TypeElement rawType = findRawGenericSupertype(supertype, visited);
            if (rawType != null) {
                return rawType;
            }
        }
        return null;
    }

    private TypeElement findRawGenericSupertype(
            TypeMirror type, Set<String> visited) {
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return null;
        }
        if (!visited.add(type.toString())) {
            return null;
        }

        DeclaredType declaredType = (DeclaredType) type;
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        if (!typeElement.getTypeParameters().isEmpty()
                && declaredType.getTypeArguments().isEmpty()) {
            return typeElement;
        }
        for (TypeMirror supertype : types.directSupertypes(declaredType)) {
            TypeElement rawType = findRawGenericSupertype(supertype, visited);
            if (rawType != null) {
                return rawType;
            }
        }
        return null;
    }

    private List<Accessor> collectAccessors(TypeElement schema) {
        DeclaredType schemaType = (DeclaredType) schema.asType();
        List<MethodMember> methods = new ArrayList<MethodMember>();
        for (Element member : elements.getAllMembers(schema)) {
            if (member.getKind() != ElementKind.METHOD
                    || member.getModifiers().contains(Modifier.STATIC)
                    || member.getModifiers().contains(Modifier.PRIVATE)) {
                continue;
            }
            ExecutableElement method = (ExecutableElement) member;
            if (method.getEnclosingElement().equals(objectType)) {
                continue;
            }
            ExecutableType resolvedType;
            try {
                resolvedType = (ExecutableType) types.asMemberOf(
                        schemaType, method);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            methods.add(new MethodMember(method, resolvedType));
        }

        List<MethodMember> effective = new ArrayList<MethodMember>();
        for (MethodMember method : methods) {
            boolean overridden = false;
            for (MethodMember possibleOverride : methods) {
                if (method == possibleOverride) {
                    continue;
                }
                if (elements.overrides(
                        possibleOverride.element, method.element, schema)) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) {
                effective.add(method);
            }
        }

        List<MethodGroup> groups = new ArrayList<MethodGroup>();
        for (MethodMember method : effective) {
            MethodGroup matchingGroup = null;
            for (MethodGroup group : groups) {
                MethodMember first = group.methods.get(0);
                if (first.element.getSimpleName().contentEquals(
                            method.element.getSimpleName())
                        && sameSignature(first.type, method.type)) {
                    matchingGroup = group;
                    break;
                }
            }
            if (matchingGroup == null) {
                matchingGroup = new MethodGroup();
                groups.add(matchingGroup);
            }
            matchingGroup.methods.add(method);
        }

        boolean valid = true;
        boolean hasEffectiveAbstractAccessor = false;
        List<Accessor> accessors = new ArrayList<Accessor>();
        PackageElement generatedPackage = elements.getPackageOf(schema);
        for (MethodGroup group : groups) {
            boolean hasAbstract = false;
            boolean hasConcrete = false;
            for (MethodMember method : group.methods) {
                if (method.element.getModifiers().contains(Modifier.ABSTRACT)) {
                    hasAbstract = true;
                } else {
                    hasConcrete = true;
                }
            }
            if (!hasAbstract) {
                continue;
            }
            hasEffectiveAbstractAccessor = true;
            MethodMember objectConflict = objectMethodConflict(group);
            if (objectConflict != null) {
                error(objectConflict.element,
                        "Projection schema accessors must not conflict with "
                                + "java.lang.Object methods: "
                                + methodSignature(objectConflict));
                valid = false;
                continue;
            }
            if (hasConcrete) {
                error(schema,
                        "Projection schema inherits conflicting abstract and "
                                + "default methods");
                valid = false;
                continue;
            }

            MethodMember representative = null;
            boolean groupValid = true;
            for (MethodMember method : group.methods) {
                if (!validateAccessor(method)) {
                    valid = false;
                    groupValid = false;
                }
                if (representative == null
                        || isMoreSpecific(method.type.getReturnType(),
                                representative.type.getReturnType())) {
                    representative = method;
                }
            }

            if (!groupValid) {
                continue;
            }

            if (representative == null
                    || !hasCompatibleReturnType(representative, group)) {
                error(group.methods.get(0).element,
                        "Inherited projection accessors have incompatible "
                                + "return types");
                valid = false;
                continue;
            }

            TypeMirror returnType = representative.type.getReturnType();
            TypeMirror erasedReturnType = types.erasure(returnType);
            if (!isAccessible(erasedReturnType, generatedPackage)) {
                error(representative.element,
                        "Projection accessor return type is not accessible "
                                + "from a generated top-level class: "
                                + returnType);
                valid = false;
                continue;
            }
            accessors.add(new Accessor(
                    representative.element.getSimpleName().toString(),
                    erasedReturnType,
                    sourceType(erasedReturnType)));
        }

        if (!hasEffectiveAbstractAccessor) {
            error(schema,
                    "Projection schema must declare or inherit at least one "
                            + "effective abstract accessor");
            valid = false;
        }

        if (!valid) {
            return null;
        }
        Collections.sort(accessors, new Comparator<Accessor>() {
            @Override
            public int compare(Accessor first, Accessor second) {
                return first.name.compareTo(second.name);
            }
        });
        return accessors;
    }

    private boolean sameSignature(
            ExecutableType first, ExecutableType second) {
        return types.isSubsignature(first, second)
                || types.isSubsignature(second, first);
    }

    private MethodMember objectMethodConflict(MethodGroup group) {
        for (MethodMember method : group.methods) {
            if (!method.element.getModifiers().contains(Modifier.ABSTRACT)) {
                continue;
            }
            for (ExecutableElement objectMethod : objectMethods) {
                if (objectMethod.getSimpleName().contentEquals(
                            method.element.getSimpleName())
                        && sameSignature(method.type,
                                (ExecutableType) objectMethod.asType())) {
                    return method;
                }
            }
        }
        return null;
    }

    private String methodSignature(MethodMember method) {
        StringBuilder signature = new StringBuilder();
        signature.append(method.element.getSimpleName()).append('(');
        List<? extends TypeMirror> parameterTypes =
                method.type.getParameterTypes();
        for (int index = 0; index < parameterTypes.size(); index++) {
            if (index != 0) {
                signature.append(", ");
            }
            signature.append(types.erasure(parameterTypes.get(index)));
        }
        return signature.append(')').toString();
    }

    private boolean validateAccessor(MethodMember method) {
        boolean valid = true;
        if (!method.element.getTypeParameters().isEmpty()) {
            error(method.element,
                    "Projection accessors must not declare type parameters");
            valid = false;
        }
        if (!method.type.getParameterTypes().isEmpty()) {
            error(method.element,
                    "Projection accessors must not declare parameters");
            valid = false;
        }
        if (method.type.getReturnType().getKind() == TypeKind.VOID) {
            error(method.element,
                    "Projection accessors must return a value");
            valid = false;
        }
        for (TypeMirror thrownType : method.type.getThrownTypes()) {
            if (!types.isSubtype(thrownType, runtimeExceptionType)
                    && !types.isSubtype(thrownType, errorType)) {
                error(method.element,
                        "Projection accessors must not declare checked "
                                + "exceptions: " + thrownType);
                valid = false;
            }
        }
        return valid;
    }

    private boolean isMoreSpecific(TypeMirror candidate, TypeMirror current) {
        return !types.isSameType(candidate, current)
                && types.isSubtype(candidate, current);
    }

    private boolean hasCompatibleReturnType(
            MethodMember representative, MethodGroup group) {
        TypeMirror returnType = representative.type.getReturnType();
        for (MethodMember method : group.methods) {
            TypeMirror otherReturnType = method.type.getReturnType();
            if (!types.isSameType(returnType, otherReturnType)
                    && !types.isSubtype(returnType, otherReturnType)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAccessible(
            TypeMirror type, PackageElement generatedPackage) {
        if (type.getKind().isPrimitive()) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return isAccessible(
                    ((ArrayType) type).getComponentType(), generatedPackage);
        }
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return false;
        }

        TypeElement typeElement =
                (TypeElement) ((DeclaredType) type).asElement();
        boolean samePackage = elements.getPackageOf(typeElement)
                .getQualifiedName()
                .contentEquals(generatedPackage.getQualifiedName());
        Element current = typeElement;
        while (current instanceof TypeElement) {
            Set<Modifier> modifiers = current.getModifiers();
            if (modifiers.contains(Modifier.PRIVATE)) {
                return false;
            }
            if (!samePackage && !modifiers.contains(Modifier.PUBLIC)) {
                return false;
            }
            current = current.getEnclosingElement();
        }
        return true;
    }

    private String generatedQualifiedName(TypeElement schema) {
        return elements.getBinaryName(schema).toString()
                + GENERATED_CLASS_SUFFIX;
    }

    private String generatedSimpleName(TypeElement schema) {
        String binaryName = elements.getBinaryName(schema).toString();
        String packageName = elements.getPackageOf(schema)
                .getQualifiedName().toString();
        if (packageName.length() == 0) {
            return binaryName + GENERATED_CLASS_SUFFIX;
        }
        return binaryName.substring(packageName.length() + 1)
                + GENERATED_CLASS_SUFFIX;
    }

    private String generateSource(
            TypeElement schema, List<Accessor> accessors) {
        String packageName = elements.getPackageOf(schema)
                .getQualifiedName().toString();
        String schemaName = schema.getQualifiedName().toString();
        String generatedSimpleName = generatedSimpleName(schema);
        StringBuilder source = new StringBuilder(8192);
        if (packageName.length() != 0) {
            line(source, "package " + packageName + ";");
            line(source, "");
        }
        line(source, "/**");
        line(source, " * Generated columnar store for {@link " + schemaName
                + "}.");
        line(source, " *");
        line(source, " * <p>Rows may be added individually or appended from "
                + "typed column slices while the store is in its building "
                + "state.");
        line(source, " * Building and batch mutation are not thread-safe. "
                + "After sealing and safe publication, reads follow the "
                + "thread-safety contract of {@link io.github.jutil."
                + "columnarprojection.ProjectionStore}.");
        line(source, " */");
        line(source, "@java.lang.SuppressWarnings({\"unchecked\", \"rawtypes\"})");
        line(source, "public final class " + generatedSimpleName);
        line(source, "        implements io.github.jutil.columnarprojection."
                + "ProjectionStore<" + schemaName + "> {");
        line(source, "");
        line(source, "    private int size;");
        line(source, "    private int capacity;");
        line(source, "    private boolean sealed;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "    private " + columnType(accessors.get(index))
                    + " column" + index + ";");
        }
        line(source, "");
        appendConstructor(source, generatedSimpleName, accessors);
        appendBatchFactory(source);
        appendAdd(source, schemaName, accessors);
        appendStoreMethods(source, schemaName);
        appendEnsureCapacity(source, accessors);
        appendBatch(source, accessors);
        appendProjectionView(source, schemaName, accessors);
        appendCursor(source, schemaName);
        line(source, "}");
        return source.toString();
    }

    private void appendConstructor(
            StringBuilder source,
            String generatedSimpleName,
            List<Accessor> accessors) {
        line(source, "    /**");
        line(source, "     * Creates an empty store with the requested "
                + "initial capacity.");
        line(source, "     * The requested size is a capacity hint, not a row "
                + "limit.");
        line(source, "     *");
        line(source, "     * @param expectedSize the expected number of rows, "
                + "or zero when unknown");
        line(source, "     * @throws java.lang.IllegalArgumentException if "
                + "{@code expectedSize} is negative");
        line(source, "     */");
        line(source, "    public " + generatedSimpleName
                + "(int expectedSize) {");
        line(source, "        if (expectedSize < 0) {");
        line(source, "            throw new java.lang.IllegalArgumentException(");
        line(source, "                    \"expectedSize must be greater than or "
                + "equal to zero\");");
        line(source, "        }");
        line(source, "        this.capacity = expectedSize;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        this.column" + index + " = "
                    + newColumnArray(accessors.get(index), "expectedSize")
                    + ";");
        }
        line(source, "    }");
        line(source, "");
    }

    private void appendBatchFactory(StringBuilder source) {
        line(source, "    /**");
        line(source, "     * Starts a typed column batch for {@code rowCount} "
                + "rows.");
        line(source, "     *");
        line(source, "     * <p>The returned batch retains each supplied "
                + "source array until its {@link Batch#append()} method "
                + "successfully copies the selected slices.");
        line(source, "     * Batch mutation is not thread-safe.");
        line(source, "     *");
        line(source, "     * @param rowCount the number of values to copy from "
                + "every column");
        line(source, "     * @return a new unfinished batch");
        line(source, "     * @throws java.lang.IllegalArgumentException if "
                + "{@code rowCount} is negative");
        line(source, "     * @throws java.lang.IllegalStateException if this "
                + "store has been sealed");
        line(source, "     */");
        line(source, "    public Batch batch(int rowCount) {");
        line(source, "        if (sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "        }");
        line(source, "        if (rowCount < 0) {");
        line(source, "            throw new java.lang.IllegalArgumentException(");
        line(source, "                    \"rowCount must be greater than or "
                + "equal to zero\");");
        line(source, "        }");
        line(source, "        return new Batch(rowCount);");
        line(source, "    }");
        line(source, "");
    }

    private void appendAdd(
            StringBuilder source,
            String schemaName,
            List<Accessor> accessors) {
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public void add(" + schemaName + " projection) {");
        line(source, "        if (sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "        }");
        line(source, "        if (projection == null) {");
        line(source, "            throw new java.lang.NullPointerException("
                + "\"projection\");");
        line(source, "        }");
        line(source, "        if (size == java.lang.Integer.MAX_VALUE) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Maximum store size reached\");");
        line(source, "        }");
        for (int index = 0; index < accessors.size(); index++) {
            Accessor accessor = accessors.get(index);
            line(source, "        final " + accessor.sourceReturnType
                    + " value" + index
                    + " = projection." + accessor.name + "();");
        }
        line(source, "        if (sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store was sealed while reading the projection\");");
        line(source, "        }");
        line(source, "        ensureCapacity(size + 1);");
        line(source, "        final int rowIndex = size;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        column" + index + "[rowIndex] = value"
                    + index + ";");
        }
        line(source, "        size = rowIndex + 1;");
        line(source, "    }");
        line(source, "");
    }

    private void appendStoreMethods(
            StringBuilder source, String schemaName) {
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public int size() {");
        line(source, "        return size;");
        line(source, "    }");
        line(source, "");
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public void seal() {");
        line(source, "        sealed = true;");
        line(source, "    }");
        line(source, "");
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public io.github.jutil.columnarprojection."
                + "ProjectionCursor<" + schemaName + "> cursor() {");
        line(source, "        requireSealed();");
        line(source, "        return new StoreCursor();");
        line(source, "    }");
        line(source, "");
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public " + schemaName + " viewAt(int index) {");
        line(source, "        requireSealed();");
        line(source, "        if (index < 0 || index >= size) {");
        line(source, "            throw new java.lang.IndexOutOfBoundsException("
                + "\"index: \" + index + \", size: \" + size);");
        line(source, "        }");
        line(source, "        return new ProjectionView(index);");
        line(source, "    }");
        line(source, "");
        line(source, "    private void requireSealed() {");
        line(source, "        if (!sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store has not been sealed\");");
        line(source, "        }");
        line(source, "    }");
        line(source, "");
    }

    private void appendEnsureCapacity(
            StringBuilder source, List<Accessor> accessors) {
        line(source, "    private void ensureCapacity(int minimumCapacity) {");
        line(source, "        if (minimumCapacity <= capacity) {");
        line(source, "            return;");
        line(source, "        }");
        line(source, "        int newCapacity = capacity + (capacity >> 1) + 1;");
        line(source, "        if (newCapacity < 0) {");
        line(source, "            newCapacity = java.lang.Integer.MAX_VALUE;");
        line(source, "        } else if (newCapacity < minimumCapacity) {");
        line(source, "            newCapacity = minimumCapacity;");
        line(source, "        }");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        final " + columnType(accessors.get(index))
                    + " grownColumn" + index
                    + " = java.util.Arrays.copyOf(column" + index
                    + ", newCapacity);");
        }
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        column" + index + " = grownColumn" + index
                    + ";");
        }
        line(source, "        capacity = newCapacity;");
        line(source, "    }");
        line(source, "");
    }

    private void appendBatch(
            StringBuilder source, List<Accessor> accessors) {
        line(source, "    /**");
        line(source, "     * A one-use, store-specific batch of typed column "
                + "slices.");
        line(source, "     *");
        line(source, "     * <p>For a positive row count, every column must be "
                + "supplied exactly once. Source arrays are retained until a "
                + "successful {@link #append()} and are never modified.");
        line(source, "     * Mutations to source-array elements before "
                + "appending are visible to the copy; mutations after a "
                + "successful append do not change stored values. "
                + "Reference-valued elements are copied as references.");
        line(source, "     *");
        line(source, "     * <p>Validation failures leave logical store rows "
                + "unchanged. A missing or invalid column may be corrected "
                + "before retrying. A successful append consumes this batch, "
                + "releases its source-array references, and places its rows "
                + "at the store size at execution time. Batch mutation is not "
                + "thread-safe.");
        line(source, "     */");
        line(source, "    public final class Batch {");
        line(source, "        private final int rowCount;");
        line(source, "        private boolean consumed;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        private " + columnType(accessors.get(index))
                    + " source" + index + ";");
            line(source, "        private int sourceOffset" + index + ";");
            line(source, "        private boolean assigned" + index + ";");
        }
        line(source, "");
        line(source, "        private Batch(int rowCount) {");
        line(source, "            this.rowCount = rowCount;");
        line(source, "        }");

        for (int index = 0; index < accessors.size(); index++) {
            appendBatchColumnMethod(source, accessors.get(index), index);
        }

        line(source, "");
        line(source, "        /**");
        line(source, "         * Copies {@code rowCount} values from every "
                + "supplied column and appends them as rows.");
        line(source, "         *");
        line(source, "         * <p>A zero-row batch is a valid no-op and does "
                + "not require column assignments. The destination position "
                + "is the store size when this method executes.");
        line(source, "         * For a positive batch, execution time is "
                + "linear in the total number of copied values, plus any "
                + "required column-capacity growth.");
        line(source, "         *");
        line(source, "         * @throws java.lang.IllegalStateException if a "
                + "positive batch is missing a column, this batch has already "
                + "been appended, this store has been sealed, or the maximum "
                + "store size would be exceeded");
        line(source, "         */");
        line(source, "        public void append() {");
        line(source, "            requireUnconsumed();");
        line(source, "            if (sealed) {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "            }");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "            if (rowCount != 0 && !assigned" + index
                    + ") {");
            line(source, "                throw new java.lang."
                    + "IllegalStateException(\"Column "
                    + accessors.get(index).name
                    + " has not been supplied\");");
            line(source, "            }");
        }
        line(source, "            if (rowCount > java.lang.Integer.MAX_VALUE "
                + "- size) {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Maximum store size reached\");");
        line(source, "            }");
        line(source, "            final int destinationOffset = size;");
        line(source, "            final int requiredSize = destinationOffset "
                + "+ rowCount;");
        line(source, "            ensureCapacity(requiredSize);");
        line(source, "            if (rowCount != 0) {");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "                java.lang.System.arraycopy(source"
                    + index + ", sourceOffset" + index + ", column" + index
                    + ", destinationOffset, rowCount);");
        }
        line(source, "            }");
        line(source, "            size = requiredSize;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "            source" + index + " = null;");
        }
        line(source, "            consumed = true;");
        line(source, "        }");
        line(source, "");
        line(source, "        private void requireUnconsumed() {");
        line(source, "            if (consumed) {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Batch has already been appended\");");
        line(source, "            }");
        line(source, "        }");
        line(source, "    }");
        line(source, "");
    }

    private void appendBatchColumnMethod(
            StringBuilder source, Accessor accessor, int index) {
        line(source, "");
        line(source, "        /**");
        line(source, "         * Supplies the {@code " + accessor.name
                + "} column from a source-array slice.");
        line(source, "         *");
        line(source, "         * <p>The source array is retained until "
                + "{@link #append()} and is never modified.");
        line(source, "         *");
        line(source, "         * @param source the source column array");
        line(source, "         * @param sourceOffset the first source index to "
                + "copy");
        line(source, "         * @return this batch");
        line(source, "         * @throws java.lang.NullPointerException if "
                + "{@code source} is null");
        line(source, "         * @throws java.lang.IndexOutOfBoundsException "
                + "if the selected slice is outside {@code source}");
        line(source, "         * @throws java.lang.IllegalStateException if "
                + "this column was already supplied or this batch was "
                + "successfully appended");
        line(source, "         */");
        line(source, "        public Batch " + accessor.name + "("
                + columnType(accessor)
                + " source, int sourceOffset) {");
        line(source, "            requireUnconsumed();");
        line(source, "            if (assigned" + index + ") {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Column " + accessor.name
                + " has already been supplied\");");
        line(source, "            }");
        line(source, "            if (source == null) {");
        line(source, "                throw new java.lang.NullPointerException("
                + "\"source\");");
        line(source, "            }");
        line(source, "            if (sourceOffset < 0");
        line(source, "                    || rowCount > source.length "
                + "- sourceOffset) {");
        line(source, "                throw new java.lang."
                + "IndexOutOfBoundsException(");
        line(source, "                        \"sourceOffset: \" + "
                + "sourceOffset");
        line(source, "                        + \", rowCount: \" + rowCount");
        line(source, "                        + \", source length: \" + "
                + "source.length);");
        line(source, "            }");
        line(source, "            source" + index + " = source;");
        line(source, "            sourceOffset" + index + " = sourceOffset;");
        line(source, "            assigned" + index + " = true;");
        line(source, "            return this;");
        line(source, "        }");
    }

    private void appendProjectionView(
            StringBuilder source,
            String schemaName,
            List<Accessor> accessors) {
        line(source, "    private final class ProjectionView implements "
                + schemaName + " {");
        line(source, "        private int rowIndex;");
        line(source, "");
        line(source, "        private ProjectionView(int rowIndex) {");
        line(source, "            this.rowIndex = rowIndex;");
        line(source, "        }");
        for (int index = 0; index < accessors.size(); index++) {
            Accessor accessor = accessors.get(index);
            line(source, "");
            line(source, "        @java.lang.Override");
            line(source, "        public " + accessor.sourceReturnType + " "
                    + accessor.name + "() {");
            line(source, "            return column" + index
                    + "[rowIndex];");
            line(source, "        }");
        }
        line(source, "    }");
        line(source, "");
    }

    private void appendCursor(StringBuilder source, String schemaName) {
        line(source, "    private final class StoreCursor implements "
                + "io.github.jutil.columnarprojection.ProjectionCursor<"
                + schemaName + "> {");
        line(source, "        private int rowIndex = -1;");
        line(source, "        private boolean positioned;");
        line(source, "        private final ProjectionView projectionView = "
                + "new ProjectionView(-1);");
        line(source, "");
        line(source, "        @java.lang.Override");
        line(source, "        public boolean moveNext() {");
        line(source, "            if (rowIndex < size - 1) {");
        line(source, "                rowIndex++;");
        line(source, "                positioned = true;");
        line(source, "                projectionView.rowIndex = rowIndex;");
        line(source, "                return true;");
        line(source, "            }");
        line(source, "            rowIndex = size;");
        line(source, "            positioned = false;");
        line(source, "            projectionView.rowIndex = -1;");
        line(source, "            return false;");
        line(source, "        }");
        line(source, "");
        line(source, "        @java.lang.Override");
        line(source, "        public " + schemaName + " current() {");
        line(source, "            if (!positioned) {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Cursor is not positioned on a row\");");
        line(source, "            }");
        line(source, "            return projectionView;");
        line(source, "        }");
        line(source, "");
        line(source, "        @java.lang.Override");
        line(source, "        public void rewind() {");
        line(source, "            rowIndex = -1;");
        line(source, "            positioned = false;");
        line(source, "            projectionView.rowIndex = -1;");
        line(source, "        }");
        line(source, "    }");
    }

    private String columnType(Accessor accessor) {
        return columnComponentType(accessor) + "[]";
    }

    private String columnComponentType(Accessor accessor) {
        return accessor.sourceReturnType;
    }

    private String newColumnArray(Accessor accessor, String lengthExpression) {
        TypeMirror baseType = accessor.erasedReturnType;
        int trailingDimensions = 0;
        while (baseType.getKind() == TypeKind.ARRAY) {
            baseType = ((ArrayType) baseType).getComponentType();
            trailingDimensions++;
        }
        StringBuilder expression = new StringBuilder();
        expression.append("new ").append(sourceType(baseType))
                .append('[').append(lengthExpression).append(']');
        for (int dimension = 0; dimension < trailingDimensions; dimension++) {
            expression.append("[]");
        }
        return expression.toString();
    }

    private String sourceType(TypeMirror type) {
        return type.toString();
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static void line(StringBuilder source, String value) {
        source.append(value).append('\n');
    }

    private static final class MethodMember {
        private final ExecutableElement element;
        private final ExecutableType type;

        private MethodMember(
                ExecutableElement element, ExecutableType type) {
            this.element = element;
            this.type = type;
        }
    }

    private static final class MethodGroup {
        private final List<MethodMember> methods =
                new ArrayList<MethodMember>();
    }

    private static final class Accessor {
        private final String name;
        private final TypeMirror erasedReturnType;
        private final String sourceReturnType;

        private Accessor(
                String name,
                TypeMirror erasedReturnType,
                String sourceReturnType) {
            this.name = name;
            this.erasedReturnType = erasedReturnType;
            this.sourceReturnType = sourceReturnType;
        }
    }
}
