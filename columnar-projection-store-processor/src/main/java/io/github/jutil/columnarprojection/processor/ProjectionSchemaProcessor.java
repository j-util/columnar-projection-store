package io.github.jutil.columnarprojection.processor;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
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
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Generates a schema-specific {@code ProjectionStore} contract and columnar
 * implementation for every interface annotated with {@code ProjectionSchema}.
 *
 * <p>The processor is normally discovered by the Java compiler through its
 * service-provider configuration. The generated schema-specific store
 * interface is the recommended API and delegates construction to
 * {@code ProjectionStores}. Its ordinary top-level name is the schema's binary
 * simple name followed by {@code Store}; package and required-source-name
 * conflicts may add trailing underscores. The generated concrete store
 * constructor remains supported for direct construction; all other generated
 * implementation details are unsupported.
 */
@SupportedAnnotationTypes(
        "io.github.jutil.columnarprojection.ProjectionSchema")
public final class ProjectionSchemaProcessor extends AbstractProcessor {

    private static final String GENERATED_CLASS_SUFFIX =
            "__ColumnarProjectionStore";
    private static final String GENERATED_STORE_SUFFIX = "Store";
    private static final String PROVENANCE_GENERATOR =
            "io.github.jutil.columnarprojection.processor."
                    + "ProjectionSchemaProcessor:1";
    private static final String PROVENANCE_ROLE_CONTRACT = "contract";
    private static final String PROVENANCE_ROLE_IMPLEMENTATION =
            "implementation";
    private static final int BATCH_HELPER_COLUMN_LIMIT = 128;

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
    private final Map<String, TypeElement> generatedTypes =
            new LinkedHashMap<String, TypeElement>();
    private final Map<String, TypeElement> declaredTypes =
            new LinkedHashMap<String, TypeElement>();
    private final Set<String> declaredPackages =
            new LinkedHashSet<String>();

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
        for (Element root : roundEnvironment.getRootElements()) {
            declaredPackages.add(elements.getPackageOf(root)
                    .getQualifiedName().toString());
            collectDeclaredTypes(root);
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

        SchemaModel model = schemaModel(schema, accessors);
        if (model == null) {
            return;
        }
        if (!claimGeneratedTypes(model)) {
            return;
        }

        writeGeneratedSource(
                model,
                model.storeQualifiedName,
                "projection store contract",
                generateStoreInterfaceSource(model));
        writeGeneratedSource(
                model,
                model.implementationQualifiedName,
                "projection store implementation",
                generateImplementationSource(model));
    }

    private void collectDeclaredTypes(Element element) {
        if (element instanceof TypeElement) {
            TypeElement type = (TypeElement) element;
            declaredTypes.put(elements.getBinaryName(type).toString(), type);
        }
        for (Element enclosed : element.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                collectDeclaredTypes(enclosed);
            }
        }
    }

    private boolean claimGeneratedTypes(SchemaModel model) {
        TypeElement existingStoreOwner =
                generatedTypes.get(model.storeQualifiedName);
        TypeElement existingImplementationOwner =
                generatedTypes.get(model.implementationQualifiedName);
        if (model.schema.equals(existingStoreOwner)
                && model.schema.equals(existingImplementationOwner)) {
            return false;
        }

        boolean available = generatedNameAvailable(
                model, model.storeQualifiedName);
        available &= generatedNameAvailable(
                model, model.implementationQualifiedName);
        if (!available) {
            return false;
        }
        generatedTypes.put(model.storeQualifiedName, model.schema);
        generatedTypes.put(model.implementationQualifiedName, model.schema);
        return true;
    }

    private boolean generatedNameAvailable(
            SchemaModel model, String generatedName) {
        TypeElement owner = generatedTypes.get(generatedName);
        if (owner != null) {
            error(model.schema,
                    "Generated type name collision: " + generatedName
                            + " is already generated for projection schema "
                            + owner.getQualifiedName());
            return false;
        }

        TypeElement declaredType = declaredTypes.get(generatedName);
        if (declaredType != null) {
            generatedNameCollision(model.schema, generatedName, declaredType);
            return false;
        }

        TypeElement classpathType = typeByBinaryName(generatedName);
        if (classpathType != null
                && !isProcessorOwned(
                        classpathType,
                        model.schemaBinaryName,
                        model.storeQualifiedName,
                        model.implementationQualifiedName,
                        generatedName)) {
            generatedNameCollision(
                    model.schema, generatedName, classpathType);
            return false;
        }
        if (declaredPackages.contains(generatedName)
                || elements.getPackageElement(generatedName) != null) {
            error(model.schema,
                    "Generated type name collision: " + generatedName
                            + " is already declared as a package");
            return false;
        }
        return true;
    }

    private void generatedNameCollision(
            TypeElement schema,
            String generatedName,
            TypeElement declaredType) {
        error(schema,
                "Generated type name collision: " + generatedName
                        + " is already declared by "
                        + declaredType.getQualifiedName());
    }

    private void writeGeneratedSource(
            SchemaModel model,
            String generatedName,
            String description,
            String source) {
        try {
            JavaFileObject sourceFile =
                    filer.createSourceFile(generatedName, model.schema);
            Writer writer = sourceFile.openWriter();
            try {
                writer.write(source);
            } finally {
                writer.close();
            }
        } catch (FilerException exception) {
            error(model.schema,
                    "Generated type name collision while creating "
                            + generatedName + ": "
                            + exception.getMessage());
        } catch (IOException exception) {
            error(model.schema,
                    "Could not generate " + description + " "
                            + generatedName
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
                    returnType,
                    erasedReturnType,
                    isSourceNameable(returnType, generatedPackage)));
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
        if (type.getKind().isPrimitive()
                || type.getKind() == TypeKind.VOID) {
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
        return isAccessible(typeElement, generatedPackage);
    }

    private boolean isAccessible(
            TypeElement typeElement, PackageElement generatedPackage) {
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

    private boolean isSourceNameable(
            TypeMirror type, PackageElement generatedPackage) {
        if (type.getKind().isPrimitive()
                || type.getKind() == TypeKind.VOID) {
            return true;
        }
        if (type.getKind() == TypeKind.ARRAY) {
            return isSourceNameable(
                    ((ArrayType) type).getComponentType(), generatedPackage);
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            TypeMirror extendsBound = wildcard.getExtendsBound();
            TypeMirror superBound = wildcard.getSuperBound();
            return (extendsBound == null
                            || isSourceNameable(extendsBound, generatedPackage))
                    && (superBound == null
                            || isSourceNameable(superBound, generatedPackage));
        }
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return false;
        }

        DeclaredType declaredType = (DeclaredType) type;
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        if (!isAccessible(typeElement, generatedPackage)) {
            return false;
        }
        TypeMirror enclosingType = declaredType.getEnclosingType();
        if (enclosingType.getKind() != TypeKind.NONE
                && !isSourceNameable(enclosingType, generatedPackage)) {
            return false;
        }
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            if (!isSourceNameable(argument, generatedPackage)) {
                return false;
            }
        }
        return true;
    }

    private SchemaModel schemaModel(
            TypeElement schema, List<Accessor> accessors) {
        String binaryName = elements.getBinaryName(schema).toString();
        String packageName = elements.getPackageOf(schema)
                .getQualifiedName().toString();
        String binarySimpleName = packageName.length() == 0
                ? binaryName
                : binaryName.substring(packageName.length() + 1);
        String baseStoreSimpleName =
                binarySimpleName + GENERATED_STORE_SUFFIX;
        String implementationSimpleName =
                binarySimpleName + GENERATED_CLASS_SUFFIX;
        String namePrefix = packageName.length() == 0
                ? ""
                : packageName + ".";
        String baseStoreQualifiedName = namePrefix + baseStoreSimpleName;
        String implementationQualifiedName =
                namePrefix + implementationSimpleName;
        GenerationProvenance previousGeneration = previousGeneration(
                binaryName,
                baseStoreQualifiedName,
                implementationQualifiedName);
        Set<String> unavailableNames = sourceTypeLeadingIdentifiers(
                schema, accessors);
        addStaleShadowedStoreRoot(
                unavailableNames,
                accessors,
                packageName,
                previousGeneration);
        String storeSimpleName = storeContractSimpleName(
                baseStoreSimpleName,
                namePrefix,
                binaryName,
                implementationQualifiedName,
                unavailableNames);
        String storeQualifiedName = namePrefix + storeSimpleName;
        if (previousGeneration != null
                && !previousGeneration.storeQualifiedName.equals(
                        storeQualifiedName)
                && !generatedStoreNameCollides(
                        storeQualifiedName,
                        binaryName,
                        implementationQualifiedName)) {
            error(schema,
                    "Generated store contract name changed for projection "
                            + "schema " + schema.getQualifiedName()
                            + " from "
                            + previousGeneration.storeQualifiedName
                            + " to " + storeQualifiedName
                            + "; stale generated output cannot be removed "
                            + "safely. Clean the compilation output and "
                            + "recompile.");
            return null;
        }
        return new SchemaModel(
                schema,
                accessors,
                packageName,
                schema.getQualifiedName().toString(),
                binaryName,
                storeSimpleName,
                storeQualifiedName,
                implementationSimpleName,
                implementationQualifiedName,
                batchTypeName(schema, accessors),
                nestedTypeName("BatchImplementation", schema, accessors),
                nestedTypeName("GeneratedProvenance", schema, accessors));
    }

    private boolean generatedStoreNameCollides(
            String generatedName,
            String schemaBinaryName,
            String implementationQualifiedName) {
        if (generatedTypes.containsKey(generatedName)
                || declaredTypes.containsKey(generatedName)) {
            return true;
        }
        TypeElement existingType = typeByBinaryName(generatedName);
        return existingType != null
                && !isProcessorOwned(
                        existingType,
                        schemaBinaryName,
                        generatedName,
                        implementationQualifiedName,
                        generatedName);
    }

    private String storeContractSimpleName(
            String baseSimpleName,
            String namePrefix,
            String schemaBinaryName,
            String implementationQualifiedName,
            Set<String> unavailableNames) {
        String candidate = baseSimpleName;
        while (true) {
            String qualifiedName = namePrefix + candidate;
            if (generatedTypes.containsKey(qualifiedName)
                    || declaredTypes.containsKey(qualifiedName)) {
                return candidate;
            }

            TypeElement existingType = typeByBinaryName(qualifiedName);
            if (existingType != null
                    && !isProcessorOwned(
                            existingType,
                            schemaBinaryName,
                            qualifiedName,
                            implementationQualifiedName,
                            qualifiedName)) {
                return candidate;
            }
            if (declaredPackages.contains(qualifiedName)
                    || elements.getPackageElement(qualifiedName) != null
                    || unavailableNames.contains(candidate)) {
                candidate += "_";
                continue;
            }
            return candidate;
        }
    }

    private GenerationProvenance previousGeneration(
            String schemaBinaryName,
            String baseStoreQualifiedName,
            String implementationQualifiedName) {
        if (declaredTypes.containsKey(implementationQualifiedName)) {
            return null;
        }
        TypeElement implementation =
                typeByBinaryName(implementationQualifiedName);
        GenerationProvenance provenance =
                generationProvenance(implementation);
        if (provenance == null
                || !PROVENANCE_ROLE_IMPLEMENTATION.equals(provenance.role)
                || !schemaBinaryName.equals(provenance.schemaBinaryName)
                || !implementationQualifiedName.equals(
                        provenance.implementationQualifiedName)
                || !isStoreContractName(
                        baseStoreQualifiedName,
                        provenance.storeQualifiedName)) {
            return null;
        }
        return provenance;
    }

    private boolean isStoreContractName(
            String baseName, String candidate) {
        return hasOnlyUnderscoreSuffix(baseName, candidate);
    }

    private boolean hasOnlyUnderscoreSuffix(
            String baseName, String candidate) {
        if (!candidate.startsWith(baseName)) {
            return false;
        }
        for (int index = baseName.length(); index < candidate.length();
                index++) {
            if (candidate.charAt(index) != '_') {
                return false;
            }
        }
        return true;
    }

    private boolean isProcessorOwned(
            TypeElement generatedType,
            String schemaBinaryName,
            String storeQualifiedName,
            String implementationQualifiedName,
            String generatedName) {
        GenerationProvenance provenance =
                generationProvenance(generatedType);
        return provenance != null
                && schemaBinaryName.equals(provenance.schemaBinaryName)
                && storeQualifiedName.equals(
                        provenance.storeQualifiedName)
                && implementationQualifiedName.equals(
                        provenance.implementationQualifiedName)
                && (PROVENANCE_ROLE_CONTRACT.equals(provenance.role)
                        && generatedName.equals(
                                provenance.storeQualifiedName)
                    || PROVENANCE_ROLE_IMPLEMENTATION.equals(provenance.role)
                        && generatedName.equals(
                                provenance.implementationQualifiedName));
    }

    private GenerationProvenance generationProvenance(
            TypeElement generatedType) {
        if (generatedType == null) {
            return null;
        }
        String generatedBinaryName =
                elements.getBinaryName(generatedType).toString();
        for (AnnotationMirror annotation :
                generatedType.getAnnotationMirrors()) {
            Element annotationElement =
                    annotation.getAnnotationType().asElement();
            if (!(annotationElement instanceof TypeElement)
                    || annotationElement.getKind()
                            != ElementKind.ANNOTATION_TYPE
                    || annotationElement.getModifiers()
                            .contains(Modifier.PUBLIC)
                    || annotationElement.getModifiers()
                            .contains(Modifier.PROTECTED)
                    || annotationElement.getModifiers()
                            .contains(Modifier.PRIVATE)
                    || !hasOnlyUnderscoreSuffix(
                            "GeneratedProvenance",
                            annotationElement.getSimpleName().toString())) {
                continue;
            }
            Element enclosing = annotationElement.getEnclosingElement();
            if (!(enclosing instanceof TypeElement)) {
                continue;
            }

            Map<String, String> values = annotationStringValues(annotation);
            if (values.size() != 5
                    || !PROVENANCE_GENERATOR.equals(values.get("generator"))) {
                continue;
            }
            String schemaBinaryName = values.get("schema");
            String storeQualifiedName = values.get("store");
            String implementationQualifiedName =
                    values.get("implementation");
            String role = values.get("role");
            boolean roleMatchesGeneratedType =
                    (PROVENANCE_ROLE_CONTRACT.equals(role)
                            && storeQualifiedName != null
                            && storeQualifiedName.equals(generatedBinaryName))
                    || (PROVENANCE_ROLE_IMPLEMENTATION.equals(role)
                            && implementationQualifiedName != null
                            && implementationQualifiedName.equals(
                                    generatedBinaryName));
            if (schemaBinaryName == null
                    || storeQualifiedName == null
                    || implementationQualifiedName == null
                    || role == null
                    || !implementationQualifiedName.equals(
                            elements.getBinaryName((TypeElement) enclosing)
                                    .toString())
                    || !roleMatchesGeneratedType) {
                continue;
            }
            return new GenerationProvenance(
                    schemaBinaryName,
                    storeQualifiedName,
                    implementationQualifiedName,
                    role);
        }
        return null;
    }

    private Map<String, String> annotationStringValues(
            AnnotationMirror annotation) {
        Map<String, String> values = new LinkedHashMap<String, String>();
        for (Map.Entry<? extends ExecutableElement,
                ? extends AnnotationValue> entry
                : annotation.getElementValues().entrySet()) {
            Object value = entry.getValue().getValue();
            if (!(value instanceof String)) {
                return Collections.emptyMap();
            }
            values.put(entry.getKey().getSimpleName().toString(),
                    (String) value);
        }
        return values;
    }

    private TypeElement typeByBinaryName(String binaryName) {
        TypeElement declaredType = declaredTypes.get(binaryName);
        if (declaredType != null) {
            return declaredType;
        }
        TypeElement direct = elements.getTypeElement(binaryName);
        if (direct != null
                && elements.getBinaryName(direct).contentEquals(binaryName)) {
            return direct;
        }

        int packageEnd = binaryName.lastIndexOf('.');
        String packageName = packageEnd < 0
                ? ""
                : binaryName.substring(0, packageEnd);
        PackageElement packageElement =
                elements.getPackageElement(packageName);
        if (packageElement == null) {
            return null;
        }
        for (Element enclosed : packageElement.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                TypeElement match = typeByBinaryName(
                        (TypeElement) enclosed, binaryName);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private TypeElement typeByBinaryName(
            TypeElement type, String binaryName) {
        if (elements.getBinaryName(type).contentEquals(binaryName)) {
            return type;
        }
        for (Element enclosed : type.getEnclosedElements()) {
            if (enclosed instanceof TypeElement) {
                TypeElement match = typeByBinaryName(
                        (TypeElement) enclosed, binaryName);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private String generateStoreInterfaceSource(SchemaModel model) {
        StringBuilder source = new StringBuilder(8192);
        if (model.packageName.length() != 0) {
            line(source, "package " + model.packageName + ";");
            line(source, "");
        }
        line(source, "/**");
        line(source, " * Schema-specific public store contract for {@link "
                + model.schemaName + "}.");
        line(source, " *");
        line(source, " * <p>This interface exposes typed whole-array and "
                + "common-range batch appends in addition to the row-oriented "
                + "operations inherited from {@link io.github.jutil."
                + "columnarprojection.ProjectionStore}. Building and batch "
                + "mutation are not thread-safe. After sealing and safe "
                + "publication, reads follow the inherited store contract.");
        line(source, " */");
        appendProvenanceAnnotation(
                source, model, PROVENANCE_ROLE_CONTRACT);
        line(source, "public interface " + model.storeSimpleName);
        line(source, "        extends io.github.jutil.columnarprojection."
                + "ProjectionStore<" + model.schemaName + "> {");
        line(source, "");
        line(source, "    /**");
        line(source, "     * Creates an empty schema-specific store with the "
                + "requested initial capacity.");
        line(source, "     *");
        line(source, "     * <p>This method delegates construction to {@link "
                + "io.github.jutil.columnarprojection.ProjectionStores}. "
                + "The requested size is a capacity hint, not a row limit.");
        line(source, "     *");
        line(source, "     * @param expectedSize the expected number of rows, "
                + "or zero when unknown");
        line(source, "     * @return a new empty store in the building state");
        line(source, "     * @throws java.lang.IllegalArgumentException if "
                + "{@code expectedSize} is negative");
        line(source, "     * @throws java.lang.IllegalStateException if the "
                + "generated implementation is unavailable, malformed, "
                + "incompatible, stale, or cannot be instantiated");
        line(source, "     */");
        line(source, "    static " + model.storeSimpleName
                + " create(int expectedSize) {");
        line(source, "        io.github.jutil.columnarprojection."
                + "ProjectionStore<" + model.schemaName + "> store =");
        line(source, "                io.github.jutil.columnarprojection."
                + "ProjectionStores.create(");
        line(source, "                        " + model.schemaName
                + ".class, expectedSize);");
        line(source, "        if (!" + model.storeSimpleName
                + ".class.isInstance(store)) {");
        line(source, "            throw new java.lang.IllegalStateException(");
        line(source, "                    \"Generated store for "
                + model.schemaName + " does not implement \"");
        line(source, "                            + \""
                + model.storeQualifiedName
                + "; clean and recompile using the current \"");
        line(source, "                            + \"annotation processor\");");
        line(source, "        }");
        line(source, "        return " + model.storeSimpleName
                + ".class.cast(store);");
        line(source, "    }");
        line(source, "");
        appendBatchContract(source, model);
        line(source, "}");
        return source.toString();
    }

    private void appendBatchContract(
            StringBuilder source, SchemaModel model) {
        line(source, "    /**");
        line(source, "     * Starts a typed batch that appends whole source "
                + "arrays.");
        line(source, "     *");
        line(source, "     * <p>The first successfully supplied array sets "
                + "the row count. Every column must be supplied exactly once "
                + "with that length, including for an empty batch. Source "
                + "arrays are retained until a successful {@link "
                + model.batchTypeName + "#append()} and are never modified.");
        line(source, "     *");
        line(source, "     * @return a new unfinished batch");
        line(source, "     * @throws java.lang.IllegalStateException if this "
                + "store has been sealed");
        line(source, "     */");
        line(source, "    " + model.batchTypeName + " batch();");
        line(source, "");
        line(source, "    /**");
        line(source, "     * Starts a typed batch that copies a common "
                + "half-open source-array range.");
        line(source, "     *");
        line(source, "     * <p>The range {@code [sourceFromIndex, "
                + "sourceToIndex)} applies to every source array and never "
                + "addresses existing store rows. A successful append writes "
                + "at the store end. An empty range needs no columns.");
        line(source, "     *");
        line(source, "     * @param sourceFromIndex the inclusive source "
                + "index");
        line(source, "     * @param sourceToIndex the exclusive source "
                + "index");
        line(source, "     * @return a new unfinished batch");
        line(source, "     * @throws java.lang.IndexOutOfBoundsException if "
                + "{@code sourceFromIndex} is negative or greater than "
                + "{@code sourceToIndex}");
        line(source, "     * @throws java.lang.IllegalStateException if this "
                + "store has been sealed");
        line(source, "     */");
        line(source, "    " + model.batchTypeName
                + " batch(int sourceFromIndex, int sourceToIndex);");
        line(source, "");
        line(source, "    /**");
        line(source, "     * One-use typed column batch for "
                + "{@link " + model.schemaName + "} rows.");
        line(source, "     *");
        line(source, "     * <p>Column values are validated when supplied but "
                + "copied only by {@link #append()}. Validation failures are "
                + "correctable and leave logical store rows unchanged. A "
                + "successful append consumes the batch, releases retained "
                + "source arrays, and publishes size only after all copies. "
                + "Batch mutation is not thread-safe.");
        line(source, "     *");
        line(source, "     * <p>Generated signatures preserve source-nameable "
                + "type structure and generic arguments while intentionally "
                + "omitting type-use annotations; the projection interface "
                + "remains authoritative for those annotations.");
        line(source, "     */");
        line(source, "    interface " + model.batchTypeName + " {");
        for (Accessor accessor : model.accessors) {
            appendBatchContractColumn(source, accessor, model.batchTypeName);
        }
        line(source, "");
        line(source, "        /**");
        line(source, "         * Validates all required columns, reserves "
                + "capacity once, and appends the selected values.");
        line(source, "         *");
        line(source, "         * <p>Whole-array batches require every column, "
                + "including when empty. Empty common-range batches are "
                + "one-use no-ops. Positive batches perform one bulk array "
                + "copy per column, then publish the new logical size and "
                + "clear retained source references.");
        line(source, "         *");
        line(source, "         * @throws java.lang.IllegalStateException if "
                + "a required column is missing, this batch was already "
                + "appended, the store was sealed, or the maximum store size "
                + "would be exceeded");
        line(source, "         */");
        line(source, "        void append();");
        line(source, "    }");
        line(source, "");
    }

    private void appendBatchContractColumn(
            StringBuilder source,
            Accessor accessor,
            String batchTypeName) {
        line(source, "");
        line(source, "        /**");
        line(source, "         * Supplies the {@code " + accessor.name
                + "} column from a source array.");
        line(source, "         *");
        line(source, "         * <p>Whole-array mode requires the common row "
                + "count. Common-range mode requires the complete configured "
                + "range. Selected values are copied only when {@link "
                + "#append()} executes; the array is retained until then and "
                + "is never modified.");
        line(source, "         *");
        line(source, "         * @param source the non-null source column "
                + "array");
        line(source, "         * @return this batch");
        line(source, "         * @throws java.lang.NullPointerException if "
                + "{@code source} is null");
        line(source, "         * @throws java.lang.IllegalArgumentException "
                + "if a whole-array batch already has a different row count");
        line(source, "         * @throws java.lang.IndexOutOfBoundsException "
                + "if a common-range source does not contain the full range");
        line(source, "         * @throws java.lang.IllegalStateException if "
                + "this column was already supplied or the batch was "
                + "successfully appended");
        line(source, "         */");
        line(source, "        " + batchTypeName + " " + accessor.name + "("
                + batchSourceColumnType(accessor) + " source);");
    }

    private String generateImplementationSource(SchemaModel model) {
        String packageName = model.packageName;
        String schemaName = model.schemaName;
        String generatedSimpleName = model.implementationSimpleName;
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
                + "typed column arrays while the store is in its building "
                + "state.");
        line(source, " * Building and batch mutation are not thread-safe. "
                + "After sealing and safe publication, reads follow the "
                + "thread-safety contract of {@link io.github.jutil."
                + "columnarprojection.ProjectionStore}.");
        line(source, " *");
        line(source, " * <p>The public constructor remains supported for "
                + "direct construction. The recommended schema-specific "
                + "contract is {@link " + model.storeQualifiedName + "}.");
        line(source, " */");
        appendProvenanceAnnotation(
                source, model, PROVENANCE_ROLE_IMPLEMENTATION);
        line(source, "@java.lang.SuppressWarnings({\"unchecked\", \"rawtypes\"})");
        line(source, "public final class " + generatedSimpleName);
        line(source, "        implements " + model.storeQualifiedName + " {");
        line(source, "");
        appendProvenanceType(source, model.provenanceTypeName);
        line(source, "    private int size;");
        line(source, "    private int capacity;");
        line(source, "    private boolean sealed;");
        for (int index = 0; index < model.accessors.size(); index++) {
            line(source, "    private "
                    + storageColumnType(model.accessors.get(index))
                    + " column" + index + ";");
        }
        line(source, "");
        appendConstructor(source, generatedSimpleName, model.accessors);
        appendBatchFactories(
                source,
                model.storeQualifiedName + "." + model.batchTypeName,
                model.batchImplementationTypeName);
        appendAdd(source, schemaName, model.accessors);
        appendStoreMethods(source, schemaName);
        appendEnsureCapacity(source, model.accessors);
        appendBatch(
                source,
                model.accessors,
                model.storeQualifiedName + "." + model.batchTypeName,
                model.batchImplementationTypeName);
        appendProjectionView(source, schemaName, model.accessors);
        appendCursor(source, schemaName);
        line(source, "}");
        return source.toString();
    }

    private void appendProvenanceAnnotation(
            StringBuilder source, SchemaModel model, String role) {
        line(source, "@" + model.implementationQualifiedName + "."
                + model.provenanceTypeName + "(");
        line(source, "        generator = \"" + PROVENANCE_GENERATOR + "\",");
        line(source, "        schema = \"" + model.schemaBinaryName + "\",");
        line(source, "        store = \"" + model.storeQualifiedName + "\",");
        line(source, "        implementation = \""
                + model.implementationQualifiedName + "\",");
        line(source, "        role = \"" + role + "\")");
    }

    private void appendProvenanceType(
            StringBuilder source, String provenanceTypeName) {
        line(source, "    @java.lang.annotation.Retention(");
        line(source, "            java.lang.annotation.RetentionPolicy.CLASS)");
        line(source, "    @java.lang.annotation.Target(");
        line(source, "            java.lang.annotation.ElementType.TYPE)");
        line(source, "    @interface " + provenanceTypeName + " {");
        line(source, "        String generator();");
        line(source, "");
        line(source, "        String schema();");
        line(source, "");
        line(source, "        String store();");
        line(source, "");
        line(source, "        String implementation();");
        line(source, "");
        line(source, "        String role();");
        line(source, "    }");
        line(source, "");
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

    private void appendBatchFactories(
            StringBuilder source,
            String batchContractTypeName,
            String batchImplementationTypeName) {
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public " + batchContractTypeName
                + " batch() {");
        line(source, "        if (sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "        }");
        line(source, "        return new " + batchImplementationTypeName
                + "();");
        line(source, "    }");
        line(source, "");
        line(source, "    /** {@inheritDoc} */");
        line(source, "    @java.lang.Override");
        line(source, "    public " + batchContractTypeName
                + " batch(int sourceFromIndex, int sourceToIndex) {");
        line(source, "        if (sealed) {");
        line(source, "            throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "        }");
        line(source, "        if (sourceFromIndex < 0");
        line(source, "                || sourceFromIndex > sourceToIndex) {");
        line(source, "            throw new java.lang.IndexOutOfBoundsException(");
        line(source, "                    \"source range: [\" + "
                + "sourceFromIndex + \", \" + sourceToIndex + \")\");");
        line(source, "        }");
        line(source, "        return new " + batchImplementationTypeName
                + "(sourceFromIndex, sourceToIndex);");
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
            line(source, "        final " + storageComponentType(accessor)
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
            line(source, "        final "
                    + storageColumnType(accessors.get(index))
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
            StringBuilder source,
            List<Accessor> accessors,
            String batchContractTypeName,
            String batchImplementationTypeName) {
        line(source, "    /**");
        line(source, "     * A one-use, store-specific batch of typed column "
                + "arrays.");
        line(source, "     *");
        line(source, "     * <p>A whole-array batch requires every column "
                + "exactly once and requires equal source lengths. An "
                + "explicit-range batch requires every column exactly once "
                + "unless its range is empty. Source arrays are retained "
                + "until a successful {@link #append()} and are never "
                + "modified.");
        line(source, "     * Mutations to source-array elements before "
                + "appending are visible to the copy; mutations after a "
                + "successful append do not change stored values. "
                + "Reference-valued elements are copied as references.");
        line(source, "     * Generated batch signatures preserve source-"
                + "nameable Java type structure and generic arguments but "
                + "intentionally omit type-use annotations; the projection "
                + "interface remains authoritative for those annotations.");
        line(source, "     *");
        line(source, "     * <p>Validation failures leave logical store rows "
                + "unchanged. A missing column, unequal whole-array length, "
                + "or too-short range source may be corrected before "
                + "retrying. A successful append consumes this batch, "
                + "releases its source-array references, and places its rows "
                + "at the store size at execution time. Source indexes never "
                + "address existing store rows. Batch mutation is not "
                + "thread-safe.");
        line(source, "     */");
        line(source, "    private final class "
                + batchImplementationTypeName + " implements "
                + batchContractTypeName + " {");
        line(source, "        private final boolean wholeArray;");
        line(source, "        private final int sourceFromIndex;");
        line(source, "        private final int sourceToIndex;");
        line(source, "        private int rowCount;");
        line(source, "        private boolean consumed;");
        for (int index = 0; index < accessors.size(); index++) {
            line(source, "        private "
                    + batchSourceColumnType(accessors.get(index))
                    + " source" + index + ";");
            line(source, "        private boolean assigned" + index + ";");
        }
        line(source, "");
        line(source, "        private " + batchImplementationTypeName
                + "() {");
        line(source, "            this.wholeArray = true;");
        line(source, "            this.sourceFromIndex = 0;");
        line(source, "            this.sourceToIndex = 0;");
        line(source, "            this.rowCount = -1;");
        line(source, "        }");
        line(source, "");
        line(source, "        private " + batchImplementationTypeName
                + "(int sourceFromIndex, int sourceToIndex) {");
        line(source, "            this.wholeArray = false;");
        line(source, "            this.sourceFromIndex = sourceFromIndex;");
        line(source, "            this.sourceToIndex = sourceToIndex;");
        line(source, "            this.rowCount = sourceToIndex "
                + "- sourceFromIndex;");
        line(source, "        }");

        for (int index = 0; index < accessors.size(); index++) {
            appendBatchColumnMethod(source, accessors.get(index), index,
                    batchContractTypeName);
        }

        line(source, "");
        line(source, "        /**");
        line(source, "         * Copies this batch's values from every "
                + "supplied column and appends them as rows.");
        line(source, "         *");
        line(source, "         * <p>A whole-array batch requires every column, "
                + "including when all arrays are empty. An empty explicit "
                + "range is a valid no-op and does not require column "
                + "assignments. The destination position is the store size "
                + "when this method executes; source indexes never select "
                + "destination rows.");
        line(source, "         * For a positive batch, execution time is "
                + "{@code O(c * rowCount)} for {@code c} columns without "
                + "growth. If capacity grows to {@code newCapacity}, growth "
                + "also takes {@code O(c * newCapacity)} time and temporarily "
                + "allocates {@code O(c * newCapacity)} additional backing "
                + "array slots. At peak, the old {@code capacity} and new "
                + "arrays occupy {@code O(c * (capacity + newCapacity))} "
                + "backing slots.");
        line(source, "         *");
        line(source, "         * @throws java.lang.IllegalStateException if a "
                + "required column is missing, this batch has already been "
                + "appended, this store has been sealed, or the maximum store "
                + "size would be exceeded");
        line(source, "         */");
        line(source, "        @java.lang.Override");
        line(source, "        public void append() {");
        line(source, "            requireUnconsumed();");
        line(source, "            if (sealed) {");
        line(source, "                throw new java.lang.IllegalStateException("
                + "\"Store has been sealed\");");
        line(source, "            }");
        for (int chunkStart = 0; chunkStart < accessors.size();
                chunkStart += BATCH_HELPER_COLUMN_LIMIT) {
            line(source, "            requireColumns"
                    + batchHelperIndex(chunkStart) + "();");
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
        for (int chunkStart = 0; chunkStart < accessors.size();
                chunkStart += BATCH_HELPER_COLUMN_LIMIT) {
            line(source, "                copyColumns"
                    + batchHelperIndex(chunkStart)
                    + "(destinationOffset);");
        }
        line(source, "            }");
        line(source, "            size = requiredSize;");
        for (int chunkStart = 0; chunkStart < accessors.size();
                chunkStart += BATCH_HELPER_COLUMN_LIMIT) {
            line(source, "            clearSources"
                    + batchHelperIndex(chunkStart) + "();");
        }
        line(source, "            consumed = true;");
        line(source, "        }");
        appendBatchHelpers(source, accessors);
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

    private void appendBatchHelpers(
            StringBuilder source, List<Accessor> accessors) {
        for (int chunkStart = 0; chunkStart < accessors.size();
                chunkStart += BATCH_HELPER_COLUMN_LIMIT) {
            int chunkEnd = Math.min(
                    chunkStart + BATCH_HELPER_COLUMN_LIMIT, accessors.size());
            int helperIndex = batchHelperIndex(chunkStart);

            line(source, "");
            line(source, "        private void requireColumns" + helperIndex
                    + "() {");
            for (int index = chunkStart; index < chunkEnd; index++) {
                line(source, "            if ((wholeArray || rowCount != 0)"
                        + " && !assigned" + index + ") {");
                line(source, "                throw new java.lang."
                        + "IllegalStateException(\"Column "
                        + accessors.get(index).name
                        + " has not been supplied\");");
                line(source, "            }");
            }
            line(source, "        }");

            line(source, "");
            line(source, "        private void copyColumns" + helperIndex
                    + "(int destinationOffset) {");
            for (int index = chunkStart; index < chunkEnd; index++) {
                line(source, "            java.lang.System.arraycopy(source"
                        + index + ", sourceFromIndex, column" + index
                        + ", destinationOffset, rowCount);");
            }
            line(source, "        }");

            line(source, "");
            line(source, "        private void clearSources" + helperIndex
                    + "() {");
            for (int index = chunkStart; index < chunkEnd; index++) {
                line(source, "            source" + index + " = null;");
            }
            line(source, "        }");
        }
    }

    private static int batchHelperIndex(int chunkStart) {
        return chunkStart / BATCH_HELPER_COLUMN_LIMIT;
    }

    private void appendBatchColumnMethod(
            StringBuilder source,
            Accessor accessor,
            int index,
            String batchTypeName) {
        line(source, "");
        line(source, "        /**");
        line(source, "         * Supplies the {@code " + accessor.name
                + "} column from a source array.");
        line(source, "         *");
        line(source, "         * <p>In whole-array mode, the first accepted "
                + "column establishes the row count and every later column "
                + "must have exactly that length. In explicit-range mode, "
                + "the array must contain the complete common half-open "
                + "source range. The selected values are copied when "
                + "{@link #append()} executes; values outside an explicit "
                + "range are ignored. The source array is retained until "
                + "then and is never modified.");
        line(source, "         *");
        line(source, "         * @param source the source column array");
        line(source, "         * @return this batch");
        line(source, "         * @throws java.lang.NullPointerException if "
                + "{@code source} is null");
        line(source, "         * @throws java.lang.IllegalArgumentException "
                + "if this is a whole-array batch whose row count was already "
                + "established by an array of a different length");
        line(source, "         * @throws java.lang.IndexOutOfBoundsException "
                + "if this is an explicit-range batch and {@code source} does "
                + "not contain its complete range");
        line(source, "         * @throws java.lang.IllegalStateException if "
                + "this column was already supplied or this batch was "
                + "successfully appended");
        line(source, "         */");
        line(source, "        @java.lang.Override");
        line(source, "        public " + batchTypeName + " "
                + accessor.name + "("
                + batchSourceColumnType(accessor)
                + " source) {");
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
        line(source, "            if (wholeArray) {");
        line(source, "                if (rowCount < 0) {");
        line(source, "                    rowCount = source.length;");
        line(source, "                } else if (source.length != rowCount) {");
        line(source, "                    throw new java.lang."
                + "IllegalArgumentException(");
        line(source, "                            \"source length: \" + "
                + "source.length");
        line(source, "                            + \", required length: \" "
                + "+ rowCount);");
        line(source, "                }");
        line(source, "            } else if (source.length < sourceToIndex) {");
        line(source, "                throw new java.lang."
                + "IndexOutOfBoundsException(");
        line(source, "                        \"sourceToIndex: \" + "
                + "sourceToIndex");
        line(source, "                        + \", source length: \" + "
                + "source.length);");
        line(source, "            }");
        line(source, "            source" + index + " = source;");
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
            line(source, "        public " + storageComponentType(accessor)
                    + " "
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

    private String storageColumnType(Accessor accessor) {
        return storageComponentType(accessor) + "[]";
    }

    private String storageComponentType(Accessor accessor) {
        return sourceType(accessor.erasedReturnType);
    }

    private String batchSourceColumnType(Accessor accessor) {
        TypeMirror componentType = accessor.declaredReturnTypeNameable
                ? accessor.declaredReturnType
                : accessor.erasedReturnType;
        return sourceType(componentType) + "[]";
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
        StringBuilder source = new StringBuilder();
        appendSourceType(source, type, null);
        return source.toString();
    }

    private void appendSourceType(
            StringBuilder source,
            TypeMirror type,
            Set<String> leadingIdentifiers) {
        switch (type.getKind()) {
            case BOOLEAN:
                source.append("boolean");
                return;
            case BYTE:
                source.append("byte");
                return;
            case SHORT:
                source.append("short");
                return;
            case INT:
                source.append("int");
                return;
            case LONG:
                source.append("long");
                return;
            case CHAR:
                source.append("char");
                return;
            case FLOAT:
                source.append("float");
                return;
            case DOUBLE:
                source.append("double");
                return;
            case VOID:
                source.append("void");
                return;
            case ARRAY:
                appendSourceType(
                        source,
                        ((ArrayType) type).getComponentType(),
                        leadingIdentifiers);
                source.append("[]");
                return;
            case WILDCARD:
                appendWildcardSourceType(
                        source,
                        (WildcardType) type,
                        leadingIdentifiers);
                return;
            case DECLARED:
            case ERROR:
                appendDeclaredSourceType(
                        source,
                        (DeclaredType) type,
                        leadingIdentifiers);
                return;
            default:
                throw new IllegalArgumentException(
                        "Unsupported source type kind: " + type.getKind());
        }
    }

    private void appendWildcardSourceType(
            StringBuilder source,
            WildcardType wildcard,
            Set<String> leadingIdentifiers) {
        source.append('?');
        if (wildcard.getExtendsBound() != null) {
            source.append(" extends ");
            appendSourceType(
                    source,
                    wildcard.getExtendsBound(),
                    leadingIdentifiers);
        } else if (wildcard.getSuperBound() != null) {
            source.append(" super ");
            appendSourceType(
                    source,
                    wildcard.getSuperBound(),
                    leadingIdentifiers);
        }
    }

    private void appendDeclaredSourceType(
            StringBuilder source,
            DeclaredType declaredType,
            Set<String> leadingIdentifiers) {
        TypeElement typeElement = (TypeElement) declaredType.asElement();
        TypeMirror enclosingType = declaredType.getEnclosingType();
        if (enclosingType.getKind() == TypeKind.NONE) {
            String qualifiedName = typeElement.getQualifiedName().toString();
            source.append(qualifiedName);
            if (leadingIdentifiers != null) {
                addLeadingIdentifier(qualifiedName, leadingIdentifiers);
            }
        } else {
            appendSourceType(source, enclosingType, leadingIdentifiers);
            source.append('.').append(typeElement.getSimpleName());
        }

        List<? extends TypeMirror> arguments = declaredType.getTypeArguments();
        if (!arguments.isEmpty()) {
            source.append('<');
            for (int index = 0; index < arguments.size(); index++) {
                if (index != 0) {
                    source.append(", ");
                }
                appendSourceType(
                        source,
                        arguments.get(index),
                        leadingIdentifiers);
            }
            source.append('>');
        }
    }

    private String batchTypeName(
            TypeElement schema, List<Accessor> accessors) {
        return nestedTypeName("Batch", schema, accessors);
    }

    private String nestedTypeName(
            String baseName,
            TypeElement schema,
            List<Accessor> accessors) {
        Set<String> unavailableNames =
                sourceTypeLeadingIdentifiers(schema, accessors);

        String name = baseName;
        while (unavailableNames.contains(name)) {
            name += "_";
        }
        return name;
    }

    private Set<String> sourceTypeLeadingIdentifiers(
            TypeElement schema, List<Accessor> accessors) {
        Set<String> unavailableNames = new LinkedHashSet<String>();
        addLeadingIdentifier(
                schema.getQualifiedName().toString(), unavailableNames);
        for (Accessor accessor : accessors) {
            collectSourceTypeLeadingIdentifiers(
                    accessor.erasedReturnType, unavailableNames);
            if (accessor.declaredReturnTypeNameable) {
                collectSourceTypeLeadingIdentifiers(
                        accessor.declaredReturnType, unavailableNames);
            }
        }
        return unavailableNames;
    }

    private void addStaleShadowedStoreRoot(
            Set<String> unavailableNames,
            List<Accessor> accessors,
            String packageName,
            GenerationProvenance previousGeneration) {
        if (previousGeneration == null) {
            return;
        }
        String namePrefix = packageName.length() == 0
                ? ""
                : packageName + ".";
        if (!previousGeneration.storeQualifiedName.startsWith(namePrefix)) {
            return;
        }
        String previousStoreSimpleName =
                previousGeneration.storeQualifiedName.substring(
                        namePrefix.length());
        for (Accessor accessor : accessors) {
            addStaleShadowedStoreRoot(
                    unavailableNames,
                    accessor.declaredReturnType,
                    previousGeneration.storeQualifiedName,
                    previousStoreSimpleName);
        }
    }

    private void addStaleShadowedStoreRoot(
            Set<String> unavailableNames,
            TypeMirror type,
            String previousStoreQualifiedName,
            String previousStoreSimpleName) {
        if (type.getKind() == TypeKind.ARRAY) {
            addStaleShadowedStoreRoot(
                    unavailableNames,
                    ((ArrayType) type).getComponentType(),
                    previousStoreQualifiedName,
                    previousStoreSimpleName);
            return;
        }
        if (type.getKind() == TypeKind.WILDCARD) {
            WildcardType wildcard = (WildcardType) type;
            if (wildcard.getExtendsBound() != null) {
                addStaleShadowedStoreRoot(
                        unavailableNames,
                        wildcard.getExtendsBound(),
                        previousStoreQualifiedName,
                        previousStoreSimpleName);
            }
            if (wildcard.getSuperBound() != null) {
                addStaleShadowedStoreRoot(
                        unavailableNames,
                        wildcard.getSuperBound(),
                        previousStoreQualifiedName,
                        previousStoreSimpleName);
            }
            return;
        }
        if (type.getKind() != TypeKind.DECLARED
                && type.getKind() != TypeKind.ERROR) {
            return;
        }

        DeclaredType declaredType = (DeclaredType) type;
        if (type.getKind() == TypeKind.ERROR) {
            TypeElement typeElement =
                    (TypeElement) declaredType.asElement();
            String qualifiedName =
                    typeElement.getQualifiedName().toString();
            String stalePrefix = previousStoreQualifiedName + ".";
            if (qualifiedName.startsWith(stalePrefix)) {
                String suffix = qualifiedName.substring(stalePrefix.length());
                String alternateName =
                        previousStoreSimpleName + "." + suffix;
                if (sourceTypeExists(qualifiedName)
                        || sourceTypeExists(alternateName)) {
                    unavailableNames.add(previousStoreSimpleName);
                }
            }
        }
        TypeMirror enclosingType = declaredType.getEnclosingType();
        if (enclosingType.getKind() != TypeKind.NONE) {
            addStaleShadowedStoreRoot(
                    unavailableNames,
                    enclosingType,
                    previousStoreQualifiedName,
                    previousStoreSimpleName);
        }
        for (TypeMirror argument : declaredType.getTypeArguments()) {
            addStaleShadowedStoreRoot(
                    unavailableNames,
                    argument,
                    previousStoreQualifiedName,
                    previousStoreSimpleName);
        }
    }

    private boolean sourceTypeExists(String canonicalName) {
        return declaredTypes.containsKey(canonicalName)
                || elements.getTypeElement(canonicalName) != null;
    }

    private void collectSourceTypeLeadingIdentifiers(
            TypeMirror type, Set<String> names) {
        appendSourceType(new StringBuilder(), type, names);
    }

    private void addLeadingIdentifier(
            String sourceReference, Set<String> names) {
        int length = sourceReference.length();
        if (length == 0
                || !Character.isJavaIdentifierStart(
                        sourceReference.charAt(0))) {
            return;
        }
        int end = 1;
        while (end < length
                && Character.isJavaIdentifierPart(
                        sourceReference.charAt(end))) {
            end++;
        }
        names.add(sourceReference.substring(0, end));
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static void line(StringBuilder source, String value) {
        source.append(value).append('\n');
    }

    private static final class SchemaModel {
        private final TypeElement schema;
        private final List<Accessor> accessors;
        private final String packageName;
        private final String schemaName;
        private final String schemaBinaryName;
        private final String storeSimpleName;
        private final String storeQualifiedName;
        private final String implementationSimpleName;
        private final String implementationQualifiedName;
        private final String batchTypeName;
        private final String batchImplementationTypeName;
        private final String provenanceTypeName;

        private SchemaModel(
                TypeElement schema,
                List<Accessor> accessors,
                String packageName,
                String schemaName,
                String schemaBinaryName,
                String storeSimpleName,
                String storeQualifiedName,
                String implementationSimpleName,
                String implementationQualifiedName,
                String batchTypeName,
                String batchImplementationTypeName,
                String provenanceTypeName) {
            this.schema = schema;
            this.accessors = accessors;
            this.packageName = packageName;
            this.schemaName = schemaName;
            this.schemaBinaryName = schemaBinaryName;
            this.storeSimpleName = storeSimpleName;
            this.storeQualifiedName = storeQualifiedName;
            this.implementationSimpleName = implementationSimpleName;
            this.implementationQualifiedName = implementationQualifiedName;
            this.batchTypeName = batchTypeName;
            this.batchImplementationTypeName = batchImplementationTypeName;
            this.provenanceTypeName = provenanceTypeName;
        }
    }

    private static final class GenerationProvenance {
        private final String schemaBinaryName;
        private final String storeQualifiedName;
        private final String implementationQualifiedName;
        private final String role;

        private GenerationProvenance(
                String schemaBinaryName,
                String storeQualifiedName,
                String implementationQualifiedName,
                String role) {
            this.schemaBinaryName = schemaBinaryName;
            this.storeQualifiedName = storeQualifiedName;
            this.implementationQualifiedName = implementationQualifiedName;
            this.role = role;
        }
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
        private final TypeMirror declaredReturnType;
        private final TypeMirror erasedReturnType;
        private final boolean declaredReturnTypeNameable;

        private Accessor(
                String name,
                TypeMirror declaredReturnType,
                TypeMirror erasedReturnType,
                boolean declaredReturnTypeNameable) {
            this.name = name;
            this.declaredReturnType = declaredReturnType;
            this.erasedReturnType = erasedReturnType;
            this.declaredReturnTypeNameable = declaredReturnTypeNameable;
        }
    }
}
