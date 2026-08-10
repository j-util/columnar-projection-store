package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectionSchemaProcessorModernSourceCompilationTest {

    private static final String GENERATED_IMPLEMENTATION_SUFFIX =
            "__ColumnarProjectionStore";
    private static final SourceLevel RELEASE_9 = new SourceLevel(
            "release-9", Arrays.asList("--release", "9"));
    private static final SourceLevel CURRENT_SOURCE = new SourceLevel(
            "current-source", Collections.<String>emptyList());
    private static final List<SourceLevel> MODERN_SOURCE_LEVELS =
            Arrays.asList(RELEASE_9, CURRENT_SOURCE);

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void requireJava9OrNewer() {
        assumeFalse(isJava8Runtime(),
                "Modern-source compiler regressions require JDK 9 or newer");
    }

    @Test
    void currentSourceDescendantPrefixIsOrderIndependentAndRunsBothBatches()
            throws Exception {
        StringSource schema = schemaWithBatchColumns();
        StringSource marker = new StringSource(
                "example.SchemaStore.sub.Marker",
                "package example.SchemaStore.sub;\n"
                        + "public final class Marker { }\n");
        StringSource consumer = batchConsumer();

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation forward = compileWithProcessor(
                    sourceLevel,
                    "current-descendant-forward",
                    Collections.<Path>emptyList(),
                    schema,
                    marker,
                    consumer);
            Compilation reverse = compileWithProcessor(
                    sourceLevel,
                    "current-descendant-reverse",
                    Collections.<Path>emptyList(),
                    consumer,
                    marker,
                    schema);

            assertSucceeded(sourceLevel, forward);
            assertSucceeded(sourceLevel, reverse);
            String forwardContract = generatedSource(
                    forward, "example.SchemaStore_");
            String reverseContract = generatedSource(
                    reverse, "example.SchemaStore_");
            assertEquals(forwardContract, reverseContract,
                    sourceLevel.toString());
            assertFalse(generatedSourceExists(
                    forward, "example.SchemaStore"), sourceLevel.toString());
            assertFalse(generatedSourceExists(
                    reverse, "example.SchemaStore"), sourceLevel.toString());
            assertTrue(generatedImplementationSource(forward).contains(
                    "implements example.SchemaStore_"), forwardContract);
            assertTrue(generatedImplementationSource(reverse).contains(
                    "implements example.SchemaStore_"), reverseContract);
            invokeVerificationMethod(
                    forward.classOutput, "example.SchemaConsumer");
            invokeVerificationMethod(
                    reverse.classOutput, "example.SchemaConsumer");
        }
    }

    @Test
    void externalAccessorTypeReservesParentPackagePrefix()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "external-accessor-marker",
                Collections.<Path>emptyList(),
                externalMarker());
        assertSucceeded(RELEASE_9, external);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "external-accessor",
                    Collections.singletonList(external.classOutput),
                    new StringSource(
                            "example.Schema",
                            "package example;\n"
                                    + "@io.github.jutil.columnarprojection."
                                    + "ProjectionSchema\n"
                                    + "public interface Schema {\n"
                                    + "    example.SchemaStore.sub.Marker "
                                    + "marker();\n"
                                    + "}\n"));

            assertSucceeded(sourceLevel, compilation);
            String contract = generatedSource(
                    compilation, "example.SchemaStore_");
            assertTrue(contract.contains(
                    "Batch marker(example.SchemaStore.sub.Marker[] source)"),
                    contract);
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore"),
                    sourceLevel.toString());
        }
    }

    @Test
    void inheritedExternalAccessorTypeReservesParentPackagePrefix()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "inherited-external-accessor-types",
                Collections.<Path>emptyList(),
                externalMarker(),
                new StringSource(
                        "other.MarkerProjection",
                        "package other;\n"
                                + "public interface MarkerProjection {\n"
                                + "    example.SchemaStore.sub.Marker "
                                + "marker();\n"
                                + "}\n"));
        assertSucceeded(RELEASE_9, external);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "inherited-external-accessor",
                    Collections.singletonList(external.classOutput),
                    new StringSource(
                            "example.Schema",
                            "package example;\n"
                                    + "@io.github.jutil.columnarprojection."
                                    + "ProjectionSchema\n"
                                    + "public interface Schema extends "
                                    + "other.MarkerProjection { }\n"));

            assertSucceeded(sourceLevel, compilation);
            String contract = generatedSource(
                    compilation, "example.SchemaStore_");
            assertTrue(contract.contains(
                    "Batch marker(example.SchemaStore.sub.Marker[] source)"),
                    contract);
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore"),
                    sourceLevel.toString());
        }
    }

    @Test
    void unrelatedDeclarationSignatureReservesParentPackagePrefix()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "external-signature-marker",
                Collections.<Path>emptyList(),
                externalMarker());
        assertSucceeded(RELEASE_9, external);
        StringSource schema = basicSchema();
        StringSource consumer = new StringSource(
                "example.DescendantPackageConsumer",
                "package example;\n"
                        + "public final class DescendantPackageConsumer {\n"
                        + "    private final SchemaStore_ store;\n"
                        + "    private final example.SchemaStore.sub.Marker "
                        + "marker;\n"
                        + "    public DescendantPackageConsumer(\n"
                        + "            SchemaStore_ store,\n"
                        + "            example.SchemaStore.sub.Marker marker) {\n"
                        + "        this.store = store;\n"
                        + "        this.marker = marker;\n"
                        + "    }\n"
                        + "    public example.SchemaStore.sub.Marker marker(\n"
                        + "            example.SchemaStore.sub.Marker fallback) {\n"
                        + "        return marker == null ? fallback : marker;\n"
                        + "    }\n"
                        + "}\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "unrelated-signature",
                    Collections.singletonList(external.classOutput),
                    schema,
                    consumer);

            assertSucceeded(sourceLevel, compilation);
            assertTrue(generatedSource(
                    compilation, "example.SchemaStore_").contains(
                            "public interface SchemaStore_"));
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore"),
                    sourceLevel.toString());
        }
    }

    @Test
    void occupiedPrefixChainSelectsDoubleUnderscoreRegardlessOfOrder()
            throws IOException {
        StringSource schema = basicSchema();
        StringSource firstMarker = new StringSource(
                "example.SchemaStore.sub.Marker",
                "package example.SchemaStore.sub;\n"
                        + "public final class Marker { }\n");
        StringSource secondMarker = new StringSource(
                "example.SchemaStore_.sub.Marker",
                "package example.SchemaStore_.sub;\n"
                        + "public final class Marker { }\n");
        StringSource consumer = new StringSource(
                "example.DoubleUnderscoreConsumer",
                "package example;\n"
                        + "public final class DoubleUnderscoreConsumer {\n"
                        + "    private SchemaStore__ store;\n"
                        + "}\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation forward = compileWithProcessor(
                    sourceLevel,
                    "double-underscore-forward",
                    Collections.<Path>emptyList(),
                    schema,
                    firstMarker,
                    secondMarker,
                    consumer);
            Compilation reverse = compileWithProcessor(
                    sourceLevel,
                    "double-underscore-reverse",
                    Collections.<Path>emptyList(),
                    consumer,
                    secondMarker,
                    firstMarker,
                    schema);

            assertSucceeded(sourceLevel, forward);
            assertSucceeded(sourceLevel, reverse);
            String forwardContract = generatedSource(
                    forward, "example.SchemaStore__");
            String reverseContract = generatedSource(
                    reverse, "example.SchemaStore__");
            assertEquals(forwardContract, reverseContract,
                    sourceLevel.toString());
            assertFalse(generatedSourceExists(
                    forward, "example.SchemaStore"), sourceLevel.toString());
            assertFalse(generatedSourceExists(
                    forward, "example.SchemaStore_"), sourceLevel.toString());
            assertTrue(generatedImplementationSource(forward).contains(
                    "implements example.SchemaStore__"));
            assertTrue(generatedImplementationSource(reverse).contains(
                    "implements example.SchemaStore__"));
        }
    }

    @Test
    void parentPackageInfoClassReservesOtherwiseHiddenPrefix()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "parent-package-info",
                Collections.<Path>emptyList(),
                externalMarker(),
                new StringSource(
                        "example.SchemaStore.package-info",
                        "@java.lang.Deprecated\n"
                                + "package example.SchemaStore;\n"));
        assertSucceeded(RELEASE_9, external);
        assertTrue(Files.isRegularFile(external.classOutput.resolve(
                "example/SchemaStore/package-info.class")));

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "package-info-workaround",
                    Collections.singletonList(external.classOutput),
                    basicSchema());

            assertSucceeded(sourceLevel, compilation);
            assertTrue(generatedSource(
                    compilation, "example.SchemaStore_").contains(
                            "public interface SchemaStore_"));
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore"),
                    sourceLevel.toString());
        }
    }

    @Test
    void unreferencedDescendantOnlyClasspathPackageRetainsBaseName()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "unreferenced-descendant-marker",
                Collections.<Path>emptyList(),
                externalMarker());
        assertSucceeded(RELEASE_9, external);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "unreferenced-descendant",
                    Collections.singletonList(external.classOutput),
                    basicSchema());

            assertSucceeded(sourceLevel, compilation);
            assertTrue(generatedSource(
                    compilation, "example.SchemaStore").contains(
                            "public interface SchemaStore"));
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore_"),
                    sourceLevel.toString());
        }
    }

    @Test
    void implementationPackagePrefixProducesFixedNameCollisionDiagnostic()
            throws IOException {
        StringSource implementationDescendant = new StringSource(
                "example.Schema__ColumnarProjectionStore.sub.Marker",
                "package example.Schema__ColumnarProjectionStore.sub;\n"
                        + "public final class Marker { }\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "implementation-package-collision",
                    Collections.<Path>emptyList(),
                    basicSchema(),
                    implementationDescendant);

            assertFailedWith(
                    sourceLevel,
                    compilation,
                    "Generated type name collision: example."
                            + "Schema__ColumnarProjectionStore is already "
                            + "declared as a package");
            assertFalse(generatedSourceExists(
                    compilation,
                    "example.Schema__ColumnarProjectionStore_"),
                    sourceLevel.toString());
        }
    }

    @Test
    void everyCurrentDeclarationSignatureShapeReservesItsOwnPrefix()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "signature-shape-types",
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.SuperSchemaStore.sub.Base",
                        "package example.SuperSchemaStore.sub;\n"
                                + "public class Base { }\n"),
                new StringSource(
                        "example.ImplementsSchemaStore.sub.Contract",
                        "package example.ImplementsSchemaStore.sub;\n"
                                + "public interface Contract { }\n"),
                new StringSource(
                        "example.ExtendsSchemaStore.sub.Contract",
                        "package example.ExtendsSchemaStore.sub;\n"
                                + "public interface Contract { }\n"),
                externalMarker("FieldSchemaStore"),
                externalMarker("ConstructorSchemaStore"),
                externalMarker("ParameterSchemaStore"),
                externalMarker("ReturnSchemaStore"),
                new StringSource(
                        "example.ThrownSchemaStore.sub.Failure",
                        "package example.ThrownSchemaStore.sub;\n"
                                + "public final class Failure "
                                + "extends Exception { }\n"),
                new StringSource(
                        "example.BoundSchemaStore.sub.Tag",
                        "package example.BoundSchemaStore.sub;\n"
                                + "public interface Tag<T> { }\n"),
                externalMarker("ArraySchemaStore"),
                externalMarker("GenericSchemaStore"),
                externalMarker("WildcardSchemaStore"),
                externalMarker("EnclosingSchemaStore"),
                externalMarker("NestedSchemaStore"),
                new StringSource(
                        "other.Outer",
                        "package other;\n"
                                + "public class Outer<T> {\n"
                                + "    public class Inner { }\n"
                                + "}\n"));
        assertSucceeded(RELEASE_9, external);

        String[] expectedContracts = new String[] {
            "SuperSchemaStore_",
            "ImplementsSchemaStore_",
            "ExtendsSchemaStore_",
            "FieldSchemaStore_",
            "ConstructorSchemaStore_",
            "ParameterSchemaStore_",
            "ReturnSchemaStore_",
            "ThrownSchemaStore_",
            "BoundSchemaStore_",
            "ArraySchemaStore_",
            "GenericSchemaStore_",
            "WildcardSchemaStore_",
            "EnclosingSchemaStore_",
            "NestedSchemaStore_"
        };
        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "signature-shapes",
                    Collections.singletonList(external.classOutput),
                    signatureSchemas(),
                    signatureConsumer());

            assertSucceeded(sourceLevel, compilation);
            for (String contract : expectedContracts) {
                assertTrue(generatedSourceExists(
                        compilation, "example." + contract),
                        sourceLevel + " did not generate " + contract
                                + ":\n" + compilation.diagnosticsText());
                assertFalse(generatedSourceExists(
                        compilation,
                        "example." + contract.substring(
                                0, contract.length() - 1)),
                        sourceLevel + " generated the unescaped name for "
                                + contract);
            }
        }
    }

    @Test
    void externalTypeMembersAreNotRecursivelyInspected()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "external-carrier",
                Collections.<Path>emptyList(),
                externalMarker(),
                new StringSource(
                        "other.Carrier",
                        "package other;\n"
                                + "public final class Carrier {\n"
                                + "    public example.SchemaStore.sub.Marker "
                                + "marker;\n"
                                + "}\n"));
        assertSucceeded(RELEASE_9, external);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "external-member-control",
                    Collections.singletonList(external.classOutput),
                    basicSchema(),
                    new StringSource(
                            "example.CarrierConsumer",
                            "package example;\n"
                                    + "final class CarrierConsumer {\n"
                                    + "    other.Carrier carrier;\n"
                                    + "    SchemaStore store;\n"
                                    + "}\n"));

            assertSucceeded(sourceLevel, compilation);
            assertTrue(generatedSourceExists(
                    compilation, "example.SchemaStore"),
                    sourceLevel.toString());
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore_"),
                    sourceLevel.toString());
        }
    }

    @Test
    void nestedTypeComponentsRemainUserTypeCollisionComponents()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                RELEASE_9,
                "nested-type-components",
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.SchemaStore",
                        "package example;\n"
                                + "public final class SchemaStore {\n"
                                + "    public static final class sub {\n"
                                + "        public static final class Marker { }\n"
                                + "    }\n"
                                + "}\n"));
        assertSucceeded(RELEASE_9, external);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "nested-type-control",
                    Collections.singletonList(external.classOutput),
                    new StringSource(
                            "example.Schema",
                            "package example;\n"
                                    + "@io.github.jutil.columnarprojection."
                                    + "ProjectionSchema\n"
                                    + "public interface Schema {\n"
                                    + "    example.SchemaStore.sub.Marker "
                                    + "marker();\n"
                                    + "}\n"));

            assertFailedWith(
                    sourceLevel,
                    compilation,
                    "Generated type name collision: example.SchemaStore "
                            + "is already declared by example.SchemaStore");
            assertFalse(generatedSourceExists(
                    compilation, "example.SchemaStore_"),
                    sourceLevel.toString());
        }
    }

    private String generatedImplementationSource(Compilation compilation)
            throws IOException {
        return generatedSource(
                compilation,
                "example.Schema" + GENERATED_IMPLEMENTATION_SUFFIX);
    }

    private Compilation compileWithProcessor(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            StringSource... sources) throws IOException {
        return compile(
                sourceLevel,
                directoryName,
                additionalClassPath,
                true,
                sources);
    }

    private Compilation compileWithoutProcessor(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            StringSource... sources) throws IOException {
        return compile(
                sourceLevel,
                directoryName,
                additionalClassPath,
                false,
                sources);
    }

    private Compilation compile(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            boolean processorEnabled,
            StringSource... sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "Tests must run on a JDK with the system Java compiler");

        Path compilationDirectory = Files.createDirectories(
                temporaryDirectory.resolve(
                        directoryName + "-" + sourceLevel.directoryName));
        Path classOutput = Files.createDirectories(
                compilationDirectory.resolve("classes"));
        Path generatedSourceOutput = Files.createDirectories(
                compilationDirectory.resolve("generated-sources"));
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        boolean succeeded;
        try {
            List<String> options = new ArrayList<String>(Arrays.asList(
                    "-classpath", classPath(additionalClassPath),
                    "-d", classOutput.toString(),
                    "-s", generatedSourceOutput.toString(),
                    "-Xlint:-options"));
            options.addAll(sourceLevel.compilerOptions);
            if (!processorEnabled) {
                options.add("-proc:none");
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    Arrays.asList(sources));
            if (processorEnabled) {
                task.setProcessors(Collections.singletonList(
                        new ProjectionSchemaProcessor()));
            }
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }
        return new Compilation(
                succeeded,
                classOutput,
                generatedSourceOutput,
                diagnostics.getDiagnostics());
    }

    private static StringSource basicSchema() {
        return new StringSource(
                "example.Schema",
                "package example;\n"
                        + "@io.github.jutil.columnarprojection."
                        + "ProjectionSchema\n"
                        + "public interface Schema {\n"
                        + "    int value();\n"
                        + "}\n");
    }

    private static StringSource schemaWithBatchColumns() {
        return new StringSource(
                "example.Schema",
                "package example;\n"
                        + "@io.github.jutil.columnarprojection."
                        + "ProjectionSchema\n"
                        + "public interface Schema {\n"
                        + "    int quantity();\n"
                        + "    String symbol();\n"
                        + "}\n");
    }

    private static StringSource batchConsumer() {
        return new StringSource(
                "example.SchemaConsumer",
                "package example;\n"
                        + "public final class SchemaConsumer {\n"
                        + "    private SchemaConsumer() { }\n"
                        + "    public static void verify() {\n"
                        + "        SchemaStore_ store = SchemaStore_.create(1);\n"
                        + "        store.batch()\n"
                        + "                .quantity(new int[]{10, 20})\n"
                        + "                .symbol(new String[]{\"A\", \"B\"})\n"
                        + "                .append();\n"
                        + "        store.batch(1, 3)\n"
                        + "                .quantity(new int[]{0, 30, 40})\n"
                        + "                .symbol(new String[]{\"ignored\", "
                        + "\"C\", \"D\", \"unused\"})\n"
                        + "                .append();\n"
                        + "        if (store.size() != 4) {\n"
                        + "            throw new AssertionError(\"size\");\n"
                        + "        }\n"
                        + "        store.seal();\n"
                        + "        if (store.viewAt(0).quantity() != 10\n"
                        + "                || !\"A\".equals("
                        + "store.viewAt(0).symbol())\n"
                        + "                || store.viewAt(3).quantity() != 40\n"
                        + "                || !\"D\".equals("
                        + "store.viewAt(3).symbol())) {\n"
                        + "            throw new AssertionError(\"rows\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    private static StringSource externalMarker() {
        return externalMarker("SchemaStore");
    }

    private static StringSource externalMarker(String packagePrefix) {
        return new StringSource(
                "example." + packagePrefix + ".sub.Marker",
                "package example." + packagePrefix + ".sub;\n"
                        + "public final class Marker { }\n");
    }

    private static StringSource signatureSchemas() {
        String[] names = new String[] {
            "SuperSchema",
            "ImplementsSchema",
            "ExtendsSchema",
            "FieldSchema",
            "ConstructorSchema",
            "ParameterSchema",
            "ReturnSchema",
            "ThrownSchema",
            "BoundSchema",
            "ArraySchema",
            "GenericSchema",
            "WildcardSchema",
            "EnclosingSchema",
            "NestedSchema"
        };
        StringBuilder source = new StringBuilder("package example;\n");
        for (String name : names) {
            source.append("@io.github.jutil.columnarprojection.")
                    .append("ProjectionSchema\n")
                    .append("interface ").append(name).append(" {\n")
                    .append("    int value();\n")
                    .append("}\n");
        }
        return new StringSource("example.SignatureSchemas", source.toString());
    }

    private static StringSource signatureConsumer() {
        return new StringSource(
                "example.SignatureConsumer",
                "package example;\n"
                        + "final class SignatureConsumer\n"
                        + "        extends example.SuperSchemaStore.sub.Base\n"
                        + "        implements example.ImplementsSchemaStore.sub."
                        + "Contract {\n"
                        + "    example.FieldSchemaStore.sub.Marker field;\n"
                        + "    example.ArraySchemaStore.sub.Marker[] array;\n"
                        + "    java.util.List<example.GenericSchemaStore.sub."
                        + "Marker> generic;\n"
                        + "    java.util.List<? super example.WildcardSchemaStore."
                        + "sub.Marker> wildcard;\n"
                        + "    other.Outer<example.EnclosingSchemaStore.sub."
                        + "Marker>.Inner enclosing;\n"
                        + "    SignatureConsumer(example.ConstructorSchemaStore."
                        + "sub.Marker marker) { }\n"
                        + "    void accept(example.ParameterSchemaStore.sub."
                        + "Marker marker) { }\n"
                        + "    example.ReturnSchemaStore.sub.Marker value() {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "    void fail() throws example.ThrownSchemaStore.sub."
                        + "Failure { }\n"
                        + "    static final class Bound<T extends "
                        + "java.lang.Number &\n"
                        + "            example.BoundSchemaStore.sub.Tag<T>> { }\n"
                        + "    static final class Nested {\n"
                        + "        example.NestedSchemaStore.sub.Marker marker;\n"
                        + "    }\n"
                        + "}\n"
                        + "interface ExtendedSignature extends\n"
                        + "        example.ExtendsSchemaStore.sub.Contract { }\n");
    }

    private static String generatedSource(
            Compilation compilation, String generatedClassName)
            throws IOException {
        Path source = generatedSourcePath(compilation, generatedClassName);
        assertTrue(Files.isRegularFile(source),
                "Expected generated source " + source + " but diagnostics were:\n"
                        + compilation.diagnosticsText());
        return new String(
                Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static boolean generatedSourceExists(
            Compilation compilation, String generatedClassName) {
        return Files.isRegularFile(
                generatedSourcePath(compilation, generatedClassName));
    }

    private static Path generatedSourcePath(
            Compilation compilation, String generatedClassName) {
        return compilation.generatedSourceOutput.resolve(
                generatedClassName.replace('.', '/')
                        + JavaFileObject.Kind.SOURCE.extension);
    }

    private static String classPath(List<Path> additionalClassPath) {
        StringBuilder classPath = new StringBuilder(
                System.getProperty("java.class.path"));
        for (Path path : additionalClassPath) {
            classPath.append(File.pathSeparatorChar).append(path);
        }
        return classPath.toString();
    }

    private static void invokeVerificationMethod(
            Path classOutput, String className) throws Exception {
        URL[] classPath = new URL[] {classOutput.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(
                classPath,
                ProjectionSchemaProcessorModernSourceCompilationTest.class
                        .getClassLoader())) {
            Class<?> verificationClass = Class.forName(
                    className, true, loader);
            Method verificationMethod = verificationClass.getMethod("verify");
            verificationMethod.invoke(null);
        }
    }

    private static void assertSucceeded(
            SourceLevel sourceLevel, Compilation compilation) {
        assertTrue(compilation.succeeded,
                sourceLevel + " compilation failed:\n"
                        + compilation.diagnosticsText());
    }

    private static void assertFailedWith(
            SourceLevel sourceLevel,
            Compilation compilation,
            String expectedMessage) {
        assertFalse(compilation.succeeded,
                sourceLevel + " compilation unexpectedly succeeded");
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : compilation.diagnostics) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR
                    && diagnostic.getMessage(Locale.ROOT)
                            .contains(expectedMessage)) {
                return;
            }
        }
        fail(sourceLevel + " expected error containing <" + expectedMessage
                + "> but received:\n" + compilation.diagnosticsText());
    }

    private static boolean isJava8Runtime() {
        String version = System.getProperty("java.specification.version");
        return "1.8".equals(version) || "8".equals(version);
    }

    private static final class SourceLevel {
        private final String directoryName;
        private final List<String> compilerOptions;

        private SourceLevel(
                String directoryName, List<String> compilerOptions) {
            this.directoryName = directoryName;
            this.compilerOptions = compilerOptions;
        }

        @Override
        public String toString() {
            return directoryName;
        }
    }

    private static final class Compilation {
        private final boolean succeeded;
        private final Path classOutput;
        private final Path generatedSourceOutput;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        private Compilation(
                boolean succeeded,
                Path classOutput,
                Path generatedSourceOutput,
                List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            this.succeeded = succeeded;
            this.classOutput = classOutput;
            this.generatedSourceOutput = generatedSourceOutput;
            this.diagnostics = diagnostics;
        }

        private String diagnosticsText() {
            StringBuilder text = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> diagnostic
                    : diagnostics) {
                text.append(diagnostic.getKind())
                        .append(": ")
                        .append(diagnostic.getMessage(Locale.ROOT))
                        .append('\n');
            }
            return text.toString();
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        private StringSource(String className, String source) {
            super(URI.create("string:///"
                    + className.replace('.', '/')
                    + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
