package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

final class ProjectionSchemaProcessorAnnotationAndModuleCompilationTest {

    private static final String MULTI_MODULE_DIAGNOSTIC =
            "ProjectionSchemaProcessor supports exactly one source module "
                    + "per compiler invocation; multiple source modules were "
                    + "found. Compile each module separately.";
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
    void declarationAnnotationsOnCurrentElementsReservePrefixesBeforeNaming()
            throws Exception {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "declaration-annotation-types",
                Collections.<Path>emptyList(),
                markerAnnotation("Schema", "TYPE"),
                markerAnnotation("NestedDeclarationSchema", "TYPE"),
                markerAnnotation("FieldDeclarationSchema", "FIELD"),
                markerAnnotation("EnumDeclarationSchema", "FIELD"),
                markerAnnotation("ConstructorDeclarationSchema",
                        "CONSTRUCTOR"),
                markerAnnotation("MethodDeclarationSchema", "METHOD"),
                markerAnnotation("ParameterDeclarationSchema", "PARAMETER"),
                markerAnnotation("TypeParameterDeclarationSchema",
                        "TYPE_PARAMETER"));
        assertSucceeded(RELEASE_9, metadata);

        StringSource schemas = schemas(
                "Schema",
                "NestedDeclarationSchema",
                "FieldDeclarationSchema",
                "EnumDeclarationSchema",
                "ConstructorDeclarationSchema",
                "MethodDeclarationSchema",
                "ParameterDeclarationSchema",
                "TypeParameterDeclarationSchema");
        StringSource carriers = declarationAnnotationCarriers();
        StringSource consumer = batchConsumer();
        List<String> schemaNames = Arrays.asList(
                "Schema",
                "NestedDeclarationSchema",
                "FieldDeclarationSchema",
                "EnumDeclarationSchema",
                "ConstructorDeclarationSchema",
                "MethodDeclarationSchema",
                "ParameterDeclarationSchema",
                "TypeParameterDeclarationSchema");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation forward = compileWithProcessor(
                    sourceLevel,
                    "declaration-annotations-forward",
                    Collections.singletonList(metadata.classOutput),
                    schemas,
                    consumer,
                    carriers);
            Compilation reverse = compileWithProcessor(
                    sourceLevel,
                    "declaration-annotations-reverse",
                    Collections.singletonList(metadata.classOutput),
                    carriers,
                    consumer,
                    schemas);

            assertSucceeded(sourceLevel, forward);
            assertSucceeded(sourceLevel, reverse);
            for (String schemaName : schemaNames) {
                assertEscapedStoreGenerated(
                        sourceLevel, forward, schemaName);
                assertEscapedStoreGenerated(
                        sourceLevel, reverse, schemaName);
            }
            assertTrue(generatedSource(
                    forward,
                    "example.Schema__ColumnarProjectionStore").contains(
                            "implements example.SchemaStore_"));
            assertTrue(generatedSource(
                    reverse,
                    "example.Schema__ColumnarProjectionStore").contains(
                            "implements example.SchemaStore_"));
            invokeVerificationMethod(
                    classOutputRoot(forward, "example.SchemaConsumer"),
                    "example.SchemaConsumer");
            invokeVerificationMethod(
                    classOutputRoot(reverse, "example.SchemaConsumer"),
                    "example.SchemaConsumer");
        }
    }

    @Test
    void explicitAnnotationValuesReservePrefixes() throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "explicit-annotation-values",
                Collections.<Path>emptyList(),
                new StringSource(
                        "metadata.values.ClassLiteralValue",
                        "package metadata.values;\n"
                                + "@java.lang.annotation.Target("
                                + "java.lang.annotation.ElementType.TYPE)\n"
                                + "public @interface ClassLiteralValue {\n"
                                + "    Class<?> value();\n"
                                + "}\n"),
                new StringSource(
                        "metadata.values.EnumValue",
                        "package metadata.values;\n"
                                + "@java.lang.annotation.Target("
                                + "java.lang.annotation.ElementType.TYPE)\n"
                                + "public @interface EnumValue {\n"
                                + "    example.EnumValueSchemaStore.sub.Mode "
                                + "value();\n"
                                + "}\n"),
                new StringSource(
                        "metadata.values.NestedValue",
                        "package metadata.values;\n"
                                + "@java.lang.annotation.Target("
                                + "java.lang.annotation.ElementType.TYPE)\n"
                                + "public @interface NestedValue {\n"
                                + "    Nested value();\n"
                                + "}\n"),
                new StringSource(
                        "metadata.values.Nested",
                        "package metadata.values;\n"
                                + "public @interface Nested {\n"
                                + "    Class<?> value();\n"
                                + "}\n"),
                new StringSource(
                        "metadata.values.ArrayValue",
                        "package metadata.values;\n"
                                + "@java.lang.annotation.Target("
                                + "java.lang.annotation.ElementType.TYPE)\n"
                                + "public @interface ArrayValue {\n"
                                + "    Class<?>[] value();\n"
                                + "}\n"),
                publicMarker("example.ClassLiteralSchemaStore.sub.Marker"),
                new StringSource(
                        "example.EnumValueSchemaStore.sub.Mode",
                        "package example.EnumValueSchemaStore.sub;\n"
                                + "public enum Mode { VALUE }\n"),
                publicMarker("example.NestedValueSchemaStore.sub.Marker"),
                publicMarker("example.ArrayValueSchemaStore.sub.First"),
                publicMarker("example.ArrayValueSchemaStore.sub.Second"));
        assertSucceeded(RELEASE_9, metadata);

        StringSource schemas = new StringSource(
                "example.ExplicitValueSchemas",
                "package example;\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.values.ClassLiteralValue("
                        + "example.ClassLiteralSchemaStore.sub.Marker.class)\n"
                        + "interface ClassLiteralSchema { int value(); }\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.values.EnumValue("
                        + "example.EnumValueSchemaStore.sub.Mode.VALUE)\n"
                        + "interface EnumValueSchema { int value(); }\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.values.NestedValue("
                        + "@metadata.values.Nested("
                        + "example.NestedValueSchemaStore.sub.Marker.class))\n"
                        + "interface NestedValueSchema { int value(); }\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.values.ArrayValue({"
                        + "example.ArrayValueSchemaStore.sub.First.class, "
                        + "example.ArrayValueSchemaStore.sub.Second.class})\n"
                        + "interface ArrayValueSchema { int value(); }\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "explicit-values",
                    Collections.singletonList(metadata.classOutput),
                    schemas);

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ClassLiteralSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "EnumValueSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "NestedValueSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ArrayValueSchema");
        }
    }

    @Test
    void currentAnnotationMemberDefaultReservesPrefix() throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "current-default-marker",
                Collections.<Path>emptyList(),
                publicMarker(
                        "example.CurrentDefaultSchemaStore.sub.Marker"));
        assertSucceeded(RELEASE_9, metadata);

        StringSource annotation = new StringSource(
                "metadata.defaults.CurrentDefault",
                "package metadata.defaults;\n"
                        + "@java.lang.annotation.Target("
                        + "java.lang.annotation.ElementType.TYPE)\n"
                        + "public @interface CurrentDefault {\n"
                        + "    Class<?> value() default "
                        + "example.CurrentDefaultSchemaStore.sub.Marker.class;\n"
                        + "}\n");
        StringSource schema = new StringSource(
                "example.CurrentDefaultSchema",
                "package example;\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.defaults.CurrentDefault\n"
                        + "public interface CurrentDefaultSchema {\n"
                        + "    int value();\n"
                        + "}\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "current-annotation-default",
                    Collections.singletonList(metadata.classOutput),
                    schema,
                    annotation);

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "CurrentDefaultSchema");
        }
    }

    @Test
    void typeUseAnnotationsOnDeclarationTypesReservePrefixes()
            throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "type-use-annotation-types",
                Collections.<Path>emptyList(),
                markerAnnotation("FieldTypeUseSchema", "TYPE_USE"),
                markerAnnotation("ParameterTypeUseSchema", "TYPE_USE"),
                markerAnnotation("ReturnTypeUseSchema", "TYPE_USE"),
                markerAnnotation("ReceiverTypeUseSchema", "TYPE_USE"),
                markerAnnotation(
                        "ConstructorReceiverTypeUseSchema", "TYPE_USE"),
                markerAnnotation("GenericTypeUseSchema", "TYPE_USE"),
                markerAnnotation("ArrayTypeUseSchema", "TYPE_USE"),
                markerAnnotation("BoundTypeUseSchema", "TYPE_USE"));
        assertSucceeded(RELEASE_9, metadata);

        StringSource schemas = schemas(
                "FieldTypeUseSchema",
                "ParameterTypeUseSchema",
                "ReturnTypeUseSchema",
                "ReceiverTypeUseSchema",
                "ConstructorReceiverTypeUseSchema",
                "GenericTypeUseSchema",
                "ArrayTypeUseSchema",
                "BoundTypeUseSchema");
        StringSource carrier = new StringSource(
                "other.TypeUseCarrier",
                "package other;\n"
                        + "final class TypeUseCarrier<T extends "
                        + "java.lang.Comparable<"
                        + "@example.BoundTypeUseSchemaStore.sub.Marker T>> {\n"
                        + "    @example.FieldTypeUseSchemaStore.sub.Marker\n"
                        + "    String field;\n"
                        + "    String parameter("
                        + "@example.ParameterTypeUseSchemaStore.sub.Marker\n"
                        + "            String value) { return value; }\n"
                        + "    @example.ReturnTypeUseSchemaStore.sub.Marker\n"
                        + "    String returns() { return field; }\n"
                        + "    void receiver("
                        + "@example.ReceiverTypeUseSchemaStore.sub.Marker\n"
                        + "            TypeUseCarrier<T> this) { }\n"
                        + "    java.util.List<"
                        + "@example.GenericTypeUseSchemaStore.sub.Marker "
                        + "String> generic;\n"
                        + "    String "
                        + "@example.ArrayTypeUseSchemaStore.sub.Marker [] "
                        + "array;\n"
                        + "}\n"
                        + "final class ReceiverOuter {\n"
                        + "    final class Inner {\n"
                        + "        Inner("
                        + "@example.ConstructorReceiverTypeUseSchemaStore.sub."
                        + "Marker ReceiverOuter ReceiverOuter.this) { }\n"
                        + "    }\n"
                        + "}\n");

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "type-use-declarations",
                    Collections.singletonList(metadata.classOutput),
                    schemas,
                    carrier);

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "FieldTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ParameterTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ReturnTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ReceiverTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel,
                    compilation,
                    "ConstructorReceiverTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "GenericTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ArrayTypeUseSchema");
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "BoundTypeUseSchema");
        }
    }

    @Test
    void currentSourceRecordComponentAnnotationsReservePrefixes()
            throws IOException {
        assumeTrue(runtimeFeatureVersion() >= 16,
                "Record-component regressions require JDK 16 or newer");
        Compilation metadata = compileWithoutProcessor(
                CURRENT_SOURCE,
                "record-component-annotation-types",
                Collections.<Path>emptyList(),
                markerAnnotation(
                        "RecordDeclarationSchema", "RECORD_COMPONENT"),
                markerAnnotation("RecordTypeUseSchema", "TYPE_USE"));
        assertSucceeded(CURRENT_SOURCE, metadata);

        Compilation compilation = compileWithProcessor(
                CURRENT_SOURCE,
                "record-component-annotations",
                Collections.singletonList(metadata.classOutput),
                schemas("RecordDeclarationSchema", "RecordTypeUseSchema"),
                new StringSource(
                        "other.RecordCarrier",
                        "package other;\n"
                                + "record RecordCarrier(\n"
                                + "        @example.RecordDeclarationSchemaStore."
                                + "sub.Marker String declarationValue,\n"
                                + "        @example.RecordTypeUseSchemaStore.sub."
                                + "Marker String typeValue) { }\n"));

        assertSucceeded(CURRENT_SOURCE, compilation);
        assertEscapedStoreGenerated(
                CURRENT_SOURCE, compilation, "RecordDeclarationSchema");
        assertEscapedStoreGenerated(
                CURRENT_SOURCE, compilation, "RecordTypeUseSchema");
    }

    @Test
    void annotatedPackageInfoReservesPrefix() throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "package-annotation-type",
                Collections.<Path>emptyList(),
                markerAnnotation("PackageAnnotationSchema", "PACKAGE"));
        assertSucceeded(RELEASE_9, metadata);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "annotated-package-info",
                    Collections.singletonList(metadata.classOutput),
                    new StringSource(
                            "example.PackageAnnotationSchema",
                            "package example;\n"
                                    + "@io.github.jutil.columnarprojection."
                                    + "ProjectionSchema\n"
                                    + "public interface "
                                    + "PackageAnnotationSchema {\n"
                                    + "    int value();\n"
                                    + "}\n"),
                    new StringSource(
                            "example.package-info",
                            "@example.PackageAnnotationSchemaStore.sub.Marker\n"
                                    + "package example;\n"));

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "PackageAnnotationSchema");
        }
    }

    @Test
    void annotatedModuleRootReservesPrefix() throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "module-annotation-type",
                Collections.<Path>emptyList(),
                markerAnnotation("ModuleAnnotationSchema", "MODULE"));
        assertSucceeded(RELEASE_9, metadata);

        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            String moduleName = "metadata.app";
            Compilation compilation = compileNamedModuleSources(
                    sourceLevel,
                    "annotated-module-root",
                    Collections.singletonList(metadata.classOutput),
                    moduleName,
                    new StringSource(
                            "module-info",
                            "@example.ModuleAnnotationSchemaStore.sub.Marker\n"
                                    + "module " + moduleName + " {\n"
                                    + "    exports example;\n"
                                    + "}\n"),
                    new StringSource(
                            "example.ModuleAnnotationSchema",
                            "package example;\n"
                                    + "@io.github.jutil.columnarprojection."
                                    + "ProjectionSchema\n"
                                    + "public interface "
                                    + "ModuleAnnotationSchema {\n"
                                    + "    int value();\n"
                                    + "}\n"));

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(
                    sourceLevel, compilation, "ModuleAnnotationSchema");
        }
    }

    @Test
    void externalAnnotationMembersAndDefaultsAreNotRecursivelyInspected()
            throws IOException {
        Compilation metadata = compileWithoutProcessor(
                RELEASE_9,
                "external-annotation-defaults",
                Collections.<Path>emptyList(),
                publicMarker(
                        "example.ExternalControlSchemaStore.sub.Marker"),
                new StringSource(
                        "example.ExternalControlSchemaStore.sub.Mode",
                        "package example.ExternalControlSchemaStore.sub;\n"
                                + "public enum Mode { VALUE }\n"),
                new StringSource(
                        "metadata.external.ExternalNested",
                        "package metadata.external;\n"
                                + "public @interface ExternalNested {\n"
                                + "    Class<?> value() default "
                                + "example.ExternalControlSchemaStore.sub."
                                + "Marker.class;\n"
                                + "}\n"),
                new StringSource(
                        "metadata.external.ExternalDefaults",
                        "package metadata.external;\n"
                                + "@java.lang.annotation.Target("
                                + "java.lang.annotation.ElementType.TYPE)\n"
                                + "public @interface ExternalDefaults {\n"
                                + "    Class<? extends example."
                                + "ExternalControlSchemaStore.sub.Marker> "
                                + "type() default example."
                                + "ExternalControlSchemaStore.sub.Marker.class;\n"
                                + "    example.ExternalControlSchemaStore.sub."
                                + "Mode mode() default example."
                                + "ExternalControlSchemaStore.sub.Mode.VALUE;\n"
                                + "    ExternalNested nested() default "
                                + "@ExternalNested;\n"
                                + "}\n"));
        assertSucceeded(RELEASE_9, metadata);

        StringSource schema = new StringSource(
                "example.ExternalControlSchema",
                "package example;\n"
                        + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                        + "@metadata.external.ExternalDefaults\n"
                        + "public interface ExternalControlSchema {\n"
                        + "    int value();\n"
                        + "}\n");
        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation compilation = compileWithProcessor(
                    sourceLevel,
                    "external-default-control",
                    Collections.singletonList(metadata.classOutput),
                    schema);

            assertFailedWith(
                    sourceLevel,
                    compilation,
                    "clashes with package of same name");
            assertTrue(generatedSourceExists(
                    compilation, "example.ExternalControlSchemaStore"),
                    sourceLevel + " should retain the base name when the "
                            + "only descendant references are hidden in "
                            + "external annotation members and defaults");
            assertFalse(generatedSourceExists(
                    compilation, "example.ExternalControlSchemaStore_"),
                    sourceLevel.toString());
        }
    }

    @Test
    void multipleSourceModulesAreRejectedBeforeGenerationRegardlessOfOrder()
            throws IOException {
        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            Compilation forward = compileModuleSources(
                    sourceLevel,
                    "multiple-modules-forward",
                    Collections.<Path>emptyList(),
                    Collections.singletonList("a.schema"),
                    multiModuleSources("a.schema", "z.support", true));
            Compilation reverse = compileModuleSources(
                    sourceLevel,
                    "multiple-modules-reverse",
                    Collections.<Path>emptyList(),
                    Collections.singletonList("z.schema"),
                    multiModuleSources("z.schema", "a.support", false));

            assertFailedWith(sourceLevel, forward, MULTI_MODULE_DIAGNOSTIC);
            assertFailedWith(sourceLevel, reverse, MULTI_MODULE_DIAGNOSTIC);
            assertDiagnosticCount(
                    sourceLevel, forward, MULTI_MODULE_DIAGNOSTIC, 1);
            assertDiagnosticCount(
                    sourceLevel, reverse, MULTI_MODULE_DIAGNOSTIC, 1);
            assertNoGeneratedJavaSources(sourceLevel, forward);
            assertNoGeneratedJavaSources(sourceLevel, reverse);
        }
    }

    @Test
    void singleNamedSourceModuleUsesDirectPackageRootsAndRunsBothBatches()
            throws Exception {
        for (SourceLevel sourceLevel : MODERN_SOURCE_LEVELS) {
            String moduleName = "consumer.app";
            Compilation compilation = compileNamedModuleSources(
                    sourceLevel,
                    "single-named-module",
                    Collections.<Path>emptyList(),
                    moduleName,
                    new StringSource(
                            "module-info",
                            "module " + moduleName + " {\n"
                                    + "    exports example;\n"
                                    + "}\n"),
                    new StringSource("example.Schema", schemaSource("Schema")),
                    new StringSource(
                            "example.SchemaStore.sub.Marker",
                            "package example.SchemaStore.sub;\n"
                                    + "public final class Marker { }\n"),
                    batchConsumer());

            assertSucceeded(sourceLevel, compilation);
            assertEscapedStoreGenerated(sourceLevel, compilation, "Schema");
            assertTrue(generatedSource(
                    compilation,
                    "example.Schema__ColumnarProjectionStore").contains(
                            "implements example.SchemaStore_"));
            invokeVerificationMethod(
                    classOutputRoot(compilation, "example.SchemaConsumer"),
                    "example.SchemaConsumer");
        }
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
                Arrays.asList(sources),
                Collections.<String>emptyList());
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
                Arrays.asList(sources),
                Collections.<String>emptyList());
    }

    private Compilation compileNamedModuleSources(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            String moduleName,
            StringSource... sources) throws IOException {
        return compile(
                sourceLevel,
                directoryName,
                additionalClassPath,
                true,
                Arrays.asList(sources),
                Arrays.asList(
                        "--add-reads", moduleName + "=ALL-UNNAMED"));
    }

    private Compilation compile(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            boolean processorEnabled,
            Iterable<? extends JavaFileObject> sources,
            List<String> additionalOptions) throws IOException {
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
            options.addAll(additionalOptions);
            if (!processorEnabled) {
                options.add("-proc:none");
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    sources);
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

    private Compilation compileModuleSources(
            SourceLevel sourceLevel,
            String directoryName,
            List<Path> additionalClassPath,
            List<String> modulesReadingUnnamed,
            List<ModuleSource> sources) throws IOException {
        Path compilationDirectory = Files.createDirectories(
                temporaryDirectory.resolve(
                        directoryName + "-" + sourceLevel.directoryName));
        Path moduleSourceRoot = Files.createDirectories(
                compilationDirectory.resolve("module-sources"));
        List<File> sourceFiles = new ArrayList<File>();
        for (ModuleSource source : sources) {
            Path sourceFile = moduleSourceRoot
                    .resolve(source.moduleName)
                    .resolve(source.relativePath);
            Files.createDirectories(sourceFile.getParent());
            Files.write(sourceFile,
                    source.source.getBytes(StandardCharsets.UTF_8));
            sourceFiles.add(sourceFile.toFile());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "Tests must run on a JDK with the system Java compiler");
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
            Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            List<String> options = new ArrayList<String>(Arrays.asList(
                    "-classpath", classPath(additionalClassPath),
                    "-d", classOutput.toString(),
                    "-s", generatedSourceOutput.toString(),
                    "-Xlint:-options",
                    "--module-source-path", moduleSourceRoot.toString()));
            options.addAll(sourceLevel.compilerOptions);
            for (String moduleName : modulesReadingUnnamed) {
                options.add("--add-reads");
                options.add(moduleName + "=ALL-UNNAMED");
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits);
            task.setProcessors(Collections.singletonList(
                    new ProjectionSchemaProcessor()));
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

    private static List<ModuleSource> multiModuleSources(
            String schemaModule,
            String supportModule,
            boolean schemaFirst) {
        List<ModuleSource> schemaSources = Arrays.asList(
                new ModuleSource(
                        schemaModule,
                        "module-info.java",
                        "module " + schemaModule + " { }\n"),
                new ModuleSource(
                        schemaModule,
                        "example/Schema.java",
                        schemaSource("Schema")));
        List<ModuleSource> supportSources = Arrays.asList(
                new ModuleSource(
                        supportModule,
                        "module-info.java",
                        "module " + supportModule + " { }\n"),
                new ModuleSource(
                        supportModule,
                        "support/Helper.java",
                        "package support;\n"
                                + "public final class Helper { }\n"));
        List<ModuleSource> sources = new ArrayList<ModuleSource>();
        if (schemaFirst) {
            sources.addAll(schemaSources);
            sources.addAll(supportSources);
        } else {
            sources.addAll(supportSources);
            sources.addAll(schemaSources);
        }
        return sources;
    }

    private static StringSource markerAnnotation(
            String schemaName, String target) {
        String className = "example." + schemaName + "Store.sub.Marker";
        int separator = className.lastIndexOf('.');
        String packageName = className.substring(0, separator);
        return new StringSource(
                className,
                "package " + packageName + ";\n"
                        + "@java.lang.annotation.Target("
                        + "java.lang.annotation.ElementType." + target + ")\n"
                        + "public @interface Marker { }\n");
    }

    private static StringSource publicMarker(String className) {
        int separator = className.lastIndexOf('.');
        String packageName = className.substring(0, separator);
        String simpleName = className.substring(separator + 1);
        return new StringSource(
                className,
                "package " + packageName + ";\n"
                        + "public final class " + simpleName + " { }\n");
    }

    private static StringSource schemas(String... schemaNames) {
        StringBuilder source = new StringBuilder("package example;\n");
        for (String schemaName : schemaNames) {
            source.append("@io.github.jutil.columnarprojection.")
                    .append("ProjectionSchema\n")
                    .append("interface ").append(schemaName).append(" {\n")
                    .append("    int value();\n")
                    .append("}\n");
        }
        if (Arrays.asList(schemaNames).contains("Schema")) {
            int start = source.indexOf("interface Schema {");
            int end = source.indexOf("}\n", start);
            source.replace(
                    start,
                    end + 2,
                    "interface Schema {\n"
                            + "    int quantity();\n"
                            + "    String symbol();\n"
                            + "}\n");
        }
        return new StringSource("example.AnnotationSchemas", source.toString());
    }

    private static String schemaSource(String schemaName) {
        if ("Schema".equals(schemaName)) {
            return "package example;\n"
                    + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                    + "public interface Schema {\n"
                    + "    int quantity();\n"
                    + "    String symbol();\n"
                    + "}\n";
        }
        return "package example;\n"
                + "@io.github.jutil.columnarprojection.ProjectionSchema\n"
                + "public interface " + schemaName + " {\n"
                + "    int value();\n"
                + "}\n";
    }

    private static StringSource declarationAnnotationCarriers() {
        return new StringSource(
                "other.DeclarationAnnotationCarriers",
                "package other;\n"
                        + "@example.SchemaStore.sub.Marker\n"
                        + "final class TopLevelCarrier { }\n"
                        + "final class NestedCarrier {\n"
                        + "    @example.NestedDeclarationSchemaStore.sub.Marker\n"
                        + "    static final class Nested { }\n"
                        + "}\n"
                        + "final class FieldCarrier {\n"
                        + "    @example.FieldDeclarationSchemaStore.sub.Marker\n"
                        + "    int value;\n"
                        + "}\n"
                        + "enum EnumCarrier {\n"
                        + "    @example.EnumDeclarationSchemaStore.sub.Marker\n"
                        + "    VALUE\n"
                        + "}\n"
                        + "final class ConstructorCarrier {\n"
                        + "    @example.ConstructorDeclarationSchemaStore.sub."
                        + "Marker\n"
                        + "    ConstructorCarrier() { }\n"
                        + "}\n"
                        + "final class ExecutableCarrier {\n"
                        + "    @example.MethodDeclarationSchemaStore.sub.Marker\n"
                        + "    void method(\n"
                        + "            @example.ParameterDeclarationSchemaStore."
                        + "sub.Marker int value) { }\n"
                        + "}\n"
                        + "final class TypeParameterCarrier<\n"
                        + "        @example.TypeParameterDeclarationSchemaStore."
                        + "sub.Marker T> { }\n");
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
                        + "            throw new AssertionError(\"typed size\");\n"
                        + "        }\n"
                        + "        store.seal();\n"
                        + "        if (store.viewAt(0).quantity() != 10\n"
                        + "                || !\"A\".equals("
                        + "store.viewAt(0).symbol())\n"
                        + "                || store.viewAt(3).quantity() != 40\n"
                        + "                || !\"D\".equals("
                        + "store.viewAt(3).symbol())) {\n"
                        + "            throw new AssertionError(\"typed rows\");\n"
                        + "        }\n"
                        + "        io.github.jutil.columnarprojection."
                        + "ProjectionStore<Schema> common =\n"
                        + "                io.github.jutil.columnarprojection."
                        + "ProjectionStores.create(Schema.class, 1);\n"
                        + "        common.add(new Schema() {\n"
                        + "            public int quantity() { return 50; }\n"
                        + "            public String symbol() { return \"E\"; }\n"
                        + "        });\n"
                        + "        common.seal();\n"
                        + "        if (common.size() != 1\n"
                        + "                || common.viewAt(0).quantity() != 50\n"
                        + "                || !\"E\".equals("
                        + "common.viewAt(0).symbol())) {\n"
                        + "            throw new AssertionError(\"common\");\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n");
    }

    private static void assertEscapedStoreGenerated(
            SourceLevel sourceLevel,
            Compilation compilation,
            String schemaSimpleName) throws IOException {
        String baseName = "example." + schemaSimpleName + "Store";
        assertTrue(generatedSourceExists(compilation, baseName + "_"),
                sourceLevel + " did not generate " + baseName + "_:\n"
                        + compilation.diagnosticsText());
        assertFalse(generatedSourceExists(compilation, baseName),
                sourceLevel + " generated unescaped " + baseName);
    }

    private static boolean generatedSourceExists(
            Compilation compilation, String generatedClassName)
            throws IOException {
        return !generatedSourcePaths(
                compilation, generatedClassName).isEmpty();
    }

    private static String generatedSource(
            Compilation compilation, String generatedClassName)
            throws IOException {
        List<Path> paths = generatedSourcePaths(
                compilation, generatedClassName);
        assertEquals(1, paths.size(),
                "Expected one generated source for " + generatedClassName
                        + " but found " + paths + ":\n"
                        + compilation.diagnosticsText());
        return new String(
                Files.readAllBytes(paths.get(0)), StandardCharsets.UTF_8);
    }

    private static List<Path> generatedSourcePaths(
            Compilation compilation, String generatedClassName)
            throws IOException {
        Path relativePath = Paths.get(
                generatedClassName.replace('.', File.separatorChar)
                        + JavaFileObject.Kind.SOURCE.extension);
        try (Stream<Path> paths = Files.walk(
                compilation.generatedSourceOutput)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.endsWith(relativePath))
                    .collect(Collectors.toList());
        }
    }

    private static Path classOutputRoot(
            Compilation compilation, String className) throws IOException {
        Path relativePath = Paths.get(
                className.replace('.', File.separatorChar)
                        + JavaFileObject.Kind.CLASS.extension);
        List<Path> matches;
        try (Stream<Path> paths = Files.walk(compilation.classOutput)) {
            matches = paths.filter(Files::isRegularFile)
                    .filter(path -> path.endsWith(relativePath))
                    .collect(Collectors.toList());
        }
        assertEquals(1, matches.size(),
                "Expected one class file for " + className
                        + " but found " + matches);
        Path root = matches.get(0);
        for (int index = 0; index < relativePath.getNameCount(); index++) {
            root = root.getParent();
        }
        return root;
    }

    private static void invokeVerificationMethod(
            Path classOutput, String className) throws Exception {
        URL[] classPath = new URL[] {classOutput.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(
                classPath,
                ProjectionSchemaProcessorAnnotationAndModuleCompilationTest
                        .class.getClassLoader())) {
            Class<?> verificationClass = Class.forName(
                    className, true, loader);
            verificationClass.getMethod("verify").invoke(null);
        }
    }

    private static void assertNoGeneratedJavaSources(
            SourceLevel sourceLevel, Compilation compilation)
            throws IOException {
        List<Path> generated;
        try (Stream<Path> paths = Files.walk(
                compilation.generatedSourceOutput)) {
            generated = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(JavaFileObject.Kind.SOURCE.extension))
                    .collect(Collectors.toList());
        }
        assertTrue(generated.isEmpty(),
                sourceLevel + " wrote generated sources before rejecting "
                        + "multiple modules: " + generated);
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

    private static void assertDiagnosticCount(
            SourceLevel sourceLevel,
            Compilation compilation,
            String expectedMessage,
            int expectedCount) {
        int actualCount = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : compilation.diagnostics) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR
                    && diagnostic.getMessage(Locale.ROOT)
                            .contains(expectedMessage)) {
                actualCount++;
            }
        }
        assertEquals(expectedCount, actualCount,
                sourceLevel + " diagnostics:\n"
                        + compilation.diagnosticsText());
    }

    private static String classPath(List<Path> additionalClassPath) {
        StringBuilder classPath = new StringBuilder(
                System.getProperty("java.class.path"));
        for (Path path : additionalClassPath) {
            classPath.append(File.pathSeparatorChar).append(path);
        }
        return classPath.toString();
    }

    private static boolean isJava8Runtime() {
        return runtimeFeatureVersion() == 8;
    }

    private static int runtimeFeatureVersion() {
        String version = System.getProperty("java.specification.version");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        int separator = version.indexOf('.');
        if (separator >= 0) {
            version = version.substring(0, separator);
        }
        return Integer.parseInt(version);
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

    private static final class ModuleSource {
        private final String moduleName;
        private final String relativePath;
        private final String source;

        private ModuleSource(
                String moduleName, String relativePath, String source) {
            this.moduleName = moduleName;
            this.relativePath = relativePath;
            this.source = source;
        }
    }
}
