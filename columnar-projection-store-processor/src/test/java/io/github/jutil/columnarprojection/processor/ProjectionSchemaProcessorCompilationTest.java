package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectionSchemaProcessorCompilationTest {

    private static final String GENERATED_SUFFIX =
            "__ColumnarProjectionStore";

    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsAnnotatedClasses() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.NotAnInterface",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public final class NotAnInterface { }\n");

        assertFailedWith(compilation,
                "@ProjectionSchema may only annotate an interface");
    }

    @Test
    void rejectsGenericSchemaInterfaces() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.GenericSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface GenericSchema<T> {\n"
                        + "    T value();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection schema interfaces must not be generic");
    }

    @Test
    void rejectsRawGenericParentInterfaces() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.RawParentSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface GenericParent<T> {\n"
                        + "    T value();\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface RawParentSchema\n"
                        + "        extends GenericParent { }\n");

        assertFailedWith(compilation,
                "Projection schema interfaces must not extend raw generic "
                        + "interfaces: example.GenericParent");
    }

    @Test
    void rejectsGenericAccessorMethods() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.GenericAccessorSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface GenericAccessorSchema {\n"
                        + "    <T> T value();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection accessors must not declare type parameters");
    }

    @Test
    void rejectsAccessorsWithParameters() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.ParameterizedAccessorSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface ParameterizedAccessorSchema {\n"
                        + "    int value(int index);\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection accessors must not declare parameters");
    }

    @Test
    void rejectsVoidAccessors() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.VoidAccessorSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface VoidAccessorSchema {\n"
                        + "    void value();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection accessors must return a value");
    }

    @Test
    void rejectsAccessorsDeclaringCheckedExceptions() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.CheckedExceptionSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface CheckedExceptionSchema {\n"
                        + "    String value() throws java.io.IOException;\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection accessors must not declare checked exceptions: "
                        + "java.io.IOException");
    }

    @Test
    void acceptsOverrideThatNarrowsInheritedCheckedException()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.NarrowedExceptionSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface CheckedParent {\n"
                        + "    String value() throws java.io.IOException;\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface NarrowedExceptionSchema\n"
                        + "        extends CheckedParent {\n"
                        + "    @Override\n"
                        + "    String value() throws IllegalStateException;\n"
                        + "}\n");

        assertSucceeded(compilation);
        assertGeneratedSourceContains(compilation,
                "example.NarrowedExceptionSchema",
                "public java.lang.String value()");
    }

    @Test
    void resolvesAccessorsFromSpecializedGenericParents()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.SpecializedSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface GenericParent<T> {\n"
                        + "    T value();\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface SpecializedSchema\n"
                        + "        extends GenericParent<String> { }\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.SpecializedSchema");
        assertTrue(generated.contains("public java.lang.String value()"),
                generated);
        assertFalse(generated.contains("public java.lang.Object value()"),
                generated);
    }

    @Test
    void mergesInheritedCovariantAccessorsIntoOneColumn()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.CovariantSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface NumberParent {\n"
                        + "    Number value();\n"
                        + "}\n"
                        + "interface IntegerParent {\n"
                        + "    Integer value();\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface CovariantSchema\n"
                        + "        extends NumberParent, IntegerParent { }\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.CovariantSchema");
        assertTrue(generated.contains("public java.lang.Integer value()"),
                generated);
        assertTrue(countOccurrences(generated,
                "public java.lang.Integer value()") == 1, generated);
    }

    @Test
    void inheritedDefaultSuppressesAbstractParentAccessor()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.DefaultSuppressedSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface AbstractLabel {\n"
                        + "    String label();\n"
                        + "}\n"
                        + "interface DefaultLabel extends AbstractLabel {\n"
                        + "    @Override\n"
                        + "    default String label() { return \"default\"; }\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface DefaultSuppressedSchema\n"
                        + "        extends DefaultLabel { }\n");

        assertFailedWith(compilation,
                "Projection schema must declare or inherit at least one "
                        + "effective abstract accessor");
    }

    @Test
    void abstractRedeclarationOfDefaultMethodCreatesAColumn()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.AbstractRedeclaredSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "interface DefaultLabel {\n"
                        + "    default String label() { return \"default\"; }\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface AbstractRedeclaredSchema\n"
                        + "        extends DefaultLabel {\n"
                        + "    @Override\n"
                        + "    String label();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.AbstractRedeclaredSchema");
        assertTrue(generated.contains("projection.label()"), generated);
        assertTrue(generated.contains("public java.lang.String label()"),
                generated);
    }

    @Test
    void rejectsAnEmptySchema() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.EmptySchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface EmptySchema { }\n");

        assertFailedWith(compilation,
                "Projection schema must declare or inherit at least one "
                        + "effective abstract accessor");
    }

    @Test
    void rejectsASchemaContainingOnlyDefaultAndStaticMethods()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.BehaviorOnlySchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface BehaviorOnlySchema {\n"
                        + "    default int defaultValue() { return 1; }\n"
                        + "    static int staticValue() { return 2; }\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection schema must declare or inherit at least one "
                        + "effective abstract accessor");
    }

    @Test
    void rejectsToStringAsAnAccessor() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.ToStringSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface ToStringSchema {\n"
                        + "    String toString();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "must not conflict with java.lang.Object methods: "
                        + "toString()");
    }

    @Test
    void rejectsHashCodeAsAnAccessor() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.HashCodeSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface HashCodeSchema {\n"
                        + "    int hashCode();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "must not conflict with java.lang.Object methods: "
                        + "hashCode()");
    }

    @Test
    void givesEqualsAnObjectMethodConflictDiagnostic() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.EqualsSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface EqualsSchema {\n"
                        + "    boolean equals(Object other);\n"
                        + "}\n");

        assertFailedWith(compilation,
                "must not conflict with java.lang.Object methods: "
                        + "equals(java.lang.Object)");
        assertFalse(compilation.diagnosticsText().contains(
                "Projection accessors must not declare parameters"),
                compilation.diagnosticsText());
    }

    @Test
    void rejectsPrivateNestedSchemaInterfaces() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.PrivateSchemaContainer",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "public final class PrivateSchemaContainer {\n"
                        + "    @ProjectionSchema\n"
                        + "    private interface HiddenSchema {\n"
                        + "        int value();\n"
                        + "    }\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection schemas and their enclosing types must not be "
                        + "private");
    }

    @Test
    void rejectsReturnTypesInaccessibleToGeneratedTopLevelClass()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.InaccessibleReturnContainer",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "public final class InaccessibleReturnContainer {\n"
                        + "    private static final class HiddenValue { }\n"
                        + "    @ProjectionSchema\n"
                        + "    public interface Schema {\n"
                        + "        HiddenValue value();\n"
                        + "    }\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Projection accessor return type is not accessible from a "
                        + "generated top-level class");
    }

    @Test
    void generatedCapacityGrowthSaturatesBeforeApplyingTheMinimum()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.GrowthSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface GrowthSchema {\n"
                        + "    int value();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(compilation, "example.GrowthSchema");
        assertTrue(generated.contains(
                "if (newCapacity < 0) {\n"
                        + "            newCapacity = java.lang.Integer."
                        + "MAX_VALUE;\n"
                        + "        } else if (newCapacity < "
                        + "minimumCapacity) {"),
                generated);
    }

    @Test
    void processorIsDiscoveredThroughServiceProviderConfiguration()
            throws IOException {
        Compilation compilation = compileUsingServiceDiscovery(
                "example.DiscoveredSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface DiscoveredSchema {\n"
                        + "    long identifier();\n"
                        + "}\n");

        assertSucceeded(compilation);
        assertGeneratedSourceContains(compilation,
                "example.DiscoveredSchema",
                "public long identifier()");
    }

    private Compilation compileWithProcessor(
            String className, String source) throws IOException {
        return compile(className, source, true);
    }

    private Compilation compileUsingServiceDiscovery(
            String className, String source) throws IOException {
        return compile(className, source, false);
    }

    private Compilation compile(
            String className,
            String source,
            boolean installProcessorExplicitly) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "Tests must run on a JDK with the system Java compiler");

        Path compilationDirectory = Files.createTempDirectory(
                temporaryDirectory, "compiler-");
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
                    "-classpath", System.getProperty("java.class.path"),
                    "-d", classOutput.toString(),
                    "-s", generatedSourceOutput.toString(),
                    "-source", "8",
                    "-target", "8",
                    "-Xlint:-options"));
            if (!installProcessorExplicitly) {
                options.add("-processorpath");
                options.add(System.getProperty("java.class.path"));
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    Collections.singletonList(
                            new StringSource(className, source)));
            if (installProcessorExplicitly) {
                task.setProcessors(Collections.singletonList(
                        new ProjectionSchemaProcessor()));
            }
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }
        return new Compilation(
                succeeded,
                generatedSourceOutput,
                diagnostics.getDiagnostics());
    }

    private static void assertSucceeded(Compilation compilation) {
        assertTrue(compilation.succeeded,
                "Compilation failed:\n" + compilation.diagnosticsText());
    }

    private static void assertFailedWith(
            Compilation compilation, String expectedMessage) {
        assertFalse(compilation.succeeded,
                "Compilation unexpectedly succeeded");
        for (Diagnostic<? extends JavaFileObject> diagnostic
                : compilation.diagnostics) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR
                    && diagnostic.getMessage(Locale.ROOT)
                            .contains(expectedMessage)) {
                return;
            }
        }
        fail("Expected error containing <" + expectedMessage
                + "> but received:\n" + compilation.diagnosticsText());
    }

    private static void assertGeneratedSourceContains(
            Compilation compilation,
            String schemaClassName,
            String expectedText) throws IOException {
        String generated = generatedSource(compilation, schemaClassName);
        assertTrue(generated.contains(expectedText), generated);
    }

    private static String generatedSource(
            Compilation compilation, String schemaClassName)
            throws IOException {
        Path generatedSource = compilation.generatedSourceOutput.resolve(
                (schemaClassName + GENERATED_SUFFIX).replace('.', '/')
                        + JavaFileObject.Kind.SOURCE.extension);
        assertTrue(Files.isRegularFile(generatedSource),
                "Expected generated source " + generatedSource
                        + " but diagnostics were:\n"
                        + compilation.diagnosticsText());
        return new String(
                Files.readAllBytes(generatedSource), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static final class Compilation {
        private final boolean succeeded;
        private final Path generatedSourceOutput;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        private Compilation(
                boolean succeeded,
                Path generatedSourceOutput,
                List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            this.succeeded = succeeded;
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
