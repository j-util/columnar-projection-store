package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ProjectionSchemaProcessorCompilationTest {

    private static final String GENERATED_SUFFIX =
            "__ColumnarProjectionStore";
    private static final String GENERATED_STORE_SUFFIX = "Store";

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
        assertTrue(generated.contains(
                "private java.lang.String[] column0;"), generated);
        assertTrue(generated.contains(
                "this.column0 = new java.lang.String[expectedSize];"),
                generated);
        assertTrue(generated.contains(
                "final java.lang.String value0 = projection.value();"),
                generated);
        assertTrue(generated.contains("return column0[rowIndex];"), generated);
        assertFalse(generated.contains("public java.lang.Object value()"),
                generated);
        assertFalse(generated.contains(
                "(java.lang.String) column0[rowIndex]"), generated);
    }

    @Test
    void generatesColumnsUsingErasedAccessorTypes() throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.TypedColumnsSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "final class Customer { }\n"
                        + "@ProjectionSchema\n"
                        + "public interface TypedColumnsSchema {\n"
                        + "    int count();\n"
                        + "    Customer customer();\n"
                        + "    int[] ids();\n"
                        + "    java.util.List<String> labels();\n"
                        + "    java.util.Map<String, Integer> lookup();\n"
                        + "    String name();\n"
                        + "    String[] names();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.TypedColumnsSchema");

        assertTrue(generated.contains("private int[] column0;"), generated);
        assertTrue(generated.contains(
                "private example.Customer[] column1;"), generated);
        assertTrue(generated.contains("private int[][] column2;"), generated);
        assertTrue(generated.contains(
                "private java.util.List[] column3;"), generated);
        assertTrue(generated.contains(
                "private java.util.Map[] column4;"), generated);
        assertTrue(generated.contains(
                "private java.lang.String[] column5;"), generated);
        assertTrue(generated.contains(
                "private java.lang.String[][] column6;"), generated);

        assertTrue(generated.contains(
                "this.column2 = new int[expectedSize][];"), generated);
        assertTrue(generated.contains(
                "this.column3 = new java.util.List[expectedSize];"),
                generated);
        assertTrue(generated.contains(
                "this.column6 = new java.lang.String[expectedSize][];"),
                generated);

        assertTrue(generated.contains(
                "final example.Customer value1 = projection.customer();"),
                generated);
        assertTrue(generated.contains(
                "final java.util.List value3 = projection.labels();"),
                generated);
        assertTrue(generated.contains(
                "final java.lang.String[] value6 = projection.names();"),
                generated);
        assertTrue(generated.contains(
                "final java.util.List[] grownColumn3 = "
                        + "java.util.Arrays.copyOf(column3, newCapacity);"),
                generated);
        assertTrue(generated.contains(
                "final java.lang.String[][] grownColumn6 = "
                        + "java.util.Arrays.copyOf(column6, newCapacity);"),
                generated);
        assertFalse(generated.contains(
                "(java.lang.String) column5[rowIndex]"), generated);
        assertFalse(generated.contains(
                "(java.lang.String[]) column6[rowIndex]"), generated);
    }

    @Test
    void generatesTypedBatchApiWithBulkCopyImplementation()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.BatchSchema",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface BatchSchema {\n"
                        + "    int count();\n"
                        + "    java.util.List<String> labels();\n"
                        + "    byte[] payload();\n"
                        + "    String symbol();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(compilation, "example.BatchSchema");
        String contract = generatedStoreSource(
                compilation, "example.BatchSchema");
        int batchStart = generated.indexOf(
                "private final class BatchImplementation");
        int batchEnd = generated.indexOf(
                "private final class ProjectionView", batchStart);
        assertTrue(batchStart >= 0, generated);
        assertTrue(batchEnd > batchStart, generated);
        String batch = generated.substring(batchStart, batchEnd);
        assertEquals(1, countOccurrences(contract,
                "intentionally omitting type-use annotations; the projection "
                        + "interface remains authoritative for those "
                        + "annotations."), contract);

        assertTrue(contract.contains("Batch batch()"), contract);
        assertTrue(contract.contains(
                "Batch batch(int sourceFromIndex, int sourceToIndex)"),
                contract);
        assertTrue(contract.contains("interface Batch"), contract);
        assertTrue(generated.contains(
                "public example.BatchSchemaStore.Batch batch()"), generated);
        assertTrue(generated.contains(
                "public example.BatchSchemaStore.Batch batch("
                        + "int sourceFromIndex, int sourceToIndex)"),
                generated);
        assertFalse(generated.contains("batch(int rowCount)"), generated);
        assertTrue(batch.contains("private BatchImplementation()"), batch);
        assertTrue(batch.contains(
                "private BatchImplementation("
                        + "int sourceFromIndex, int sourceToIndex)"),
                batch);
        assertTrue(batch.contains(
                "implements example.BatchSchemaStore.Batch"), batch);
        assertTrue(batch.contains(
                "public example.BatchSchemaStore.Batch "
                        + "count(int[] source)"), batch);
        assertTrue(batch.contains(
                "private java.util.List<java.lang.String>[] source1;"),
                batch);
        assertTrue(batch.contains(
                "public example.BatchSchemaStore.Batch labels("
                        + "java.util.List<java.lang.String>[] source)"), batch);
        assertTrue(batch.contains(
                "public example.BatchSchemaStore.Batch "
                        + "payload(byte[][] source)"),
                batch);
        assertTrue(batch.contains(
                "public example.BatchSchemaStore.Batch "
                        + "symbol(java.lang.String[] source)"), batch);
        assertTrue(contract.contains("Batch count(int[] source)"), contract);
        assertTrue(contract.contains(
                "Batch labels(java.util.List<java.lang.String>[] source)"),
                contract);
        assertTrue(batch.contains("public void append()"), batch);
        assertTrue(batch.contains("source.length != rowCount"), batch);
        assertTrue(batch.contains("source.length < sourceToIndex"), batch);
        assertTrue(batch.contains(
                "rowCount > java.lang.Integer.MAX_VALUE - size"), batch);
        assertEquals(1, countOccurrences(batch,
                "ensureCapacity(requiredSize);"), batch);
        assertEquals(4, countOccurrences(batch,
                "java.lang.System.arraycopy("), batch);
        assertEquals(4, countOccurrences(batch,
                ", sourceFromIndex, column"), batch);
        assertFalse(batch.contains("for ("), batch);

        String append = methodSource(batch, "public void append()");
        String requiredColumns = methodSource(
                batch, "private void requireColumns0()");
        String copyColumns = methodSource(
                batch,
                "private void copyColumns0(int destinationOffset)");
        String clearSources = methodSource(
                batch, "private void clearSources0()");
        assertEquals(0, countOccurrences(append,
                "java.lang.System.arraycopy("), append);
        assertEquals(4, countOccurrences(copyColumns,
                "java.lang.System.arraycopy("), copyColumns);
        assertEquals(4, countOccurrences(requiredColumns,
                "&& !assigned"), requiredColumns);
        assertEquals(4, countOccurrences(clearSources,
                " = null;"), clearSources);
        assertEquals(countOccurrences(batch,
                "java.lang.System.arraycopy("), countOccurrences(
                        copyColumns, "java.lang.System.arraycopy("), batch);

        int unconsumedCheck = append.indexOf("requireUnconsumed();");
        int sealedCheck = append.indexOf("if (sealed)");
        int requiredColumnsCall = append.indexOf("requireColumns0();");
        int overflowCheck = append.indexOf(
                "rowCount > java.lang.Integer.MAX_VALUE - size");
        int capacityReservation = append.indexOf(
                "ensureCapacity(requiredSize);");
        int copyColumnsCall = append.indexOf(
                "copyColumns0(destinationOffset);");
        int sizeChange = append.indexOf("size = requiredSize;");
        int sourceClear = append.indexOf("clearSources0();");
        int consumedChange = append.indexOf("consumed = true;");
        assertTrue(unconsumedCheck >= 0 && unconsumedCheck < sealedCheck,
                append);
        assertTrue(sealedCheck < requiredColumnsCall, append);
        assertTrue(requiredColumnsCall < overflowCheck, append);
        assertTrue(overflowCheck < capacityReservation, append);
        assertTrue(capacityReservation < copyColumnsCall, append);
        assertTrue(copyColumnsCall < sizeChange, append);
        assertTrue(sizeChange < sourceClear, append);
        assertTrue(sourceClear < consumedChange, append);
    }

    @Test
    void generatedStoreContractIsAJava8TypedConsumerBoundary()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.PriceProjection",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection.*;\n"
                        + "@ProjectionSchema\n"
                        + "public interface PriceProjection {\n"
                        + "    double price();\n"
                        + "    String symbol();\n"
                        + "}\n"
                        + "final class PriceConsumer {\n"
                        + "    void use() {\n"
                        + "        PriceProjectionStore prices = "
                        + "PriceProjectionStore.create(2);\n"
                        + "        prices.batch()\n"
                        + "                .price(new double[]{15.1, 25.2})\n"
                        + "                .symbol(new String[]{\"A\", \"B\"})\n"
                        + "                .append();\n"
                        + "        prices.batch(0, 1)\n"
                        + "                .price(new double[]{35.3})\n"
                        + "                .symbol(new String[]{\"C\"})\n"
                        + "                .append();\n"
                        + "        prices.add(new PriceProjection() {\n"
                        + "            public double price() { return 45.4; }\n"
                        + "            public String symbol() { return \"D\"; }\n"
                        + "        });\n"
                        + "        ProjectionStore<PriceProjection> common = "
                        + "prices;\n"
                        + "        prices.seal();\n"
                        + "        prices.cursor();\n"
                        + "        prices.viewAt(0);\n"
                        + "        ProjectionStores.create("
                        + "PriceProjection.class, 1);\n"
                        + "        new PriceProjection"
                        + "__ColumnarProjectionStore(1);\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String contract = generatedStoreSource(
                compilation, "example.PriceProjection");
        String implementation = generatedSource(
                compilation, "example.PriceProjection");
        assertTrue(contract.contains(
                "public interface PriceProjectionStore"), contract);
        assertTrue(contract.contains(
                "extends io.github.jutil.columnarprojection."
                        + "ProjectionStore<example.PriceProjection>"),
                contract);
        assertTrue(contract.contains(
                "static PriceProjectionStore create(int expectedSize)"),
                contract);
        String factory = methodSource(
                contract,
                "static PriceProjectionStore create(int expectedSize)");
        assertTrue(factory.contains(
                "return new PriceProjection__ColumnarProjectionStore("
                        + "expectedSize);"), factory);
        assertFalse(factory.contains("ProjectionStores.create"), factory);
        assertFalse(factory.contains(".class.isInstance"), factory);
        assertFalse(factory.contains(".class.cast"), factory);
        assertFalse(factory.contains(
                "clean and recompile using the current"), factory);
        assertFalse(contract.contains("@java.lang.SuppressWarnings"), contract);
        assertFalse(contract.contains("(PriceProjectionStore)"), contract);
        assertTrue(implementation.contains(
                "implements example.PriceProjectionStore"), implementation);
        assertTrue(implementation.contains(
                "private final class BatchImplementation implements "
                        + "example.PriceProjectionStore.Batch"),
                implementation);
    }

    @Test
    void recompilesIdenticalSchemaWithPriorClassOutputOnClasspath()
            throws IOException {
        String source = "package example;\n"
                + "import io.github.jutil.columnarprojection."
                + "ProjectionSchema;\n"
                + "@ProjectionSchema\n"
                + "public interface RepeatedProjection {\n"
                + "    int quantity();\n"
                + "    String symbol();\n"
                + "}\n";
        Path compilationDirectory = temporaryDirectory.resolve(
                "identical-recompilation");

        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource("example.RepeatedProjection", source));

        assertSucceeded(first);
        String firstContract = generatedStoreSource(
                first, "example.RepeatedProjection");
        String firstImplementation = generatedSource(
                first, "example.RepeatedProjection");

        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource("example.RepeatedProjection", source));

        assertSucceeded(second);
        assertEquals(firstContract, generatedStoreSource(
                second, "example.RepeatedProjection"));
        assertEquals(firstImplementation, generatedSource(
                second, "example.RepeatedProjection"));
    }

    @Test
    void recompilesSchemaWithPackageInfoAndRunsFactoryAndBothBatchModes()
            throws Exception {
        String packageInfoSource = "@java.lang.Deprecated\n"
                + "package example;\n";
        String schemaSource = "package example;\n"
                + "import io.github.jutil.columnarprojection."
                + "ProjectionSchema;\n"
                + "@ProjectionSchema\n"
                + "public interface PackageInfoProjection {\n"
                + "    int quantity();\n"
                + "    String symbol();\n"
                + "}\n";
        Path compilationDirectory = temporaryDirectory.resolve(
                "package-info-recompilation");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource("example.package-info", packageInfoSource),
                new StringSource(
                        "example.PackageInfoProjection", schemaSource));

        assertSucceeded(first);

        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource("example.package-info", packageInfoSource),
                new StringSource(
                        "example.PackageInfoProjection", schemaSource),
                new StringSource(
                        "example.PackageInfoProjectionConsumer",
                        "package example;\n"
                                + "public final class "
                                + "PackageInfoProjectionConsumer {\n"
                                + "    private PackageInfoProjectionConsumer()"
                                + " { }\n"
                                + "    public static void verify() {\n"
                                + "        PackageInfoProjectionStore store = "
                                + "PackageInfoProjectionStore.create(1);\n"
                                + "        store.batch()\n"
                                + "                .quantity(new int[]"
                                + "{10, 20})\n"
                                + "                .symbol(new String[]"
                                + "{\"A\", \"B\"})\n"
                                + "                .append();\n"
                                + "        store.batch(1, 3)\n"
                                + "                .quantity(new int[]"
                                + "{0, 30, 40})\n"
                                + "                .symbol(new String[]"
                                + "{\"ignored\", \"C\", \"D\", "
                                + "\"unused\"})\n"
                                + "                .append();\n"
                                + "        if (store.size() != 4) {\n"
                                + "            throw new AssertionError("
                                + "\"unexpected size\");\n"
                                + "        }\n"
                                + "        store.seal();\n"
                                + "        if (store.viewAt(0).quantity() "
                                + "!= 10\n"
                                + "                || !\"A\".equals("
                                + "store.viewAt(0).symbol())\n"
                                + "                || store.viewAt(3)."
                                + "quantity() != 40\n"
                                + "                || !\"D\".equals("
                                + "store.viewAt(3).symbol())) {\n"
                                + "            throw new AssertionError("
                                + "\"unexpected rows\");\n"
                                + "        }\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(second);
        assertTrue(generatedStoreSource(
                second, "example.PackageInfoProjection").contains(
                        "Batch symbol(java.lang.String[] source)"));
        invokeVerificationMethod(
                second.classOutput,
                "example.PackageInfoProjectionConsumer");
    }

    @Test
    void recompilesEvolvedSchemaAndRunsFactoryAndBothBatchModes()
            throws Exception {
        Path compilationDirectory = temporaryDirectory.resolve(
                "evolved-recompilation");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.EvolvedProjection",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface EvolvedProjection {\n"
                                + "    int quantity();\n"
                                + "}\n"));

        assertSucceeded(first);

        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource(
                        "example.EvolvedProjection",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface EvolvedProjection {\n"
                                + "    int quantity();\n"
                                + "    String symbol();\n"
                                + "}\n"),
                new StringSource(
                        "example.EvolvedProjectionConsumer",
                        "package example;\n"
                                + "public final class "
                                + "EvolvedProjectionConsumer {\n"
                                + "    private EvolvedProjectionConsumer() { }\n"
                                + "    public static void verify() {\n"
                                + "        EvolvedProjectionStore store = "
                                + "EvolvedProjectionStore.create(1);\n"
                                + "        store.batch()\n"
                                + "                .quantity(new int[]"
                                + "{10, 20})\n"
                                + "                .symbol(new String[]"
                                + "{\"A\", \"B\"})\n"
                                + "                .append();\n"
                                + "        store.batch(1, 3)\n"
                                + "                .quantity(new int[]"
                                + "{0, 30, 40})\n"
                                + "                .symbol(new String[]"
                                + "{\"ignored\", \"C\", \"D\", "
                                + "\"unused\"})\n"
                                + "                .append();\n"
                                + "        if (store.size() != 4) {\n"
                                + "            throw new AssertionError("
                                + "\"unexpected size\");\n"
                                + "        }\n"
                                + "        store.seal();\n"
                                + "        if (store.viewAt(0).quantity() "
                                + "!= 10\n"
                                + "                || !\"A\".equals("
                                + "store.viewAt(0).symbol())\n"
                                + "                || store.viewAt(3)."
                                + "quantity() != 40\n"
                                + "                || !\"D\".equals("
                                + "store.viewAt(3).symbol())) {\n"
                                + "            throw new AssertionError("
                                + "\"unexpected rows\");\n"
                                + "        }\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(second);
        String contract = generatedStoreSource(
                second, "example.EvolvedProjection");
        String implementation = generatedSource(
                second, "example.EvolvedProjection");
        assertTrue(contract.contains(
                "Batch symbol(java.lang.String[] source)"), contract);
        assertTrue(implementation.contains(
                "public java.lang.String symbol()"), implementation);
        invokeVerificationMethod(
                second.classOutput,
                "example.EvolvedProjectionConsumer");
    }

    @Test
    void generatedContractFactoryConstructsCurrentImplementationAtRuntime()
            throws Exception {
        Compilation compilation = compileWithProcessor(
                "example.FactoryProjection",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface FactoryProjection {\n"
                        + "    int value();\n"
                        + "}\n");
        assertSucceeded(compilation);

        URL[] classPath = new URL[] {
            compilation.classOutput.toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(
                classPath,
                ProjectionSchemaProcessorCompilationTest.class
                        .getClassLoader())) {
            Class<?> contract = Class.forName(
                    "example.FactoryProjectionStore", true, loader);
            Class<?> implementation = Class.forName(
                    "example.FactoryProjection__ColumnarProjectionStore",
                    true,
                    loader);
            Object store = contract.getMethod("create", Integer.TYPE)
                    .invoke(null, 1);

            assertTrue(contract.isInstance(store));
            assertTrue(implementation.isInstance(store));
        }
    }

    @Test
    void rejectsUserDeclaredGeneratedStoreContractName()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.ContractCollision",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "interface ContractCollision { int value(); }\n"
                        + "interface ContractCollisionStore { }\n");

        assertFailedWith(compilation,
                "Generated type name collision: "
                        + "example.ContractCollisionStore is already declared");
    }

    @Test
    void rejectsUserDeclaredGeneratedImplementationName()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.ImplementationCollision",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "interface ImplementationCollision { int value(); }\n"
                        + "final class ImplementationCollision"
                        + "__ColumnarProjectionStore { }\n");

        assertFailedWith(compilation,
                "Generated type name collision: example."
                        + "ImplementationCollision__ColumnarProjectionStore "
                        + "is already declared");
    }

    @Test
    void rejectsExternalUserDeclaredGeneratedStoreContractName()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.ExternalContractCollisionStore",
                        "package example;\n"
                                + "public interface "
                                + "ExternalContractCollisionStore { }\n"));
        assertSucceeded(external);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(external.classOutput),
                new StringSource(
                        "example.ExternalContractCollision",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface "
                                + "ExternalContractCollision {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(compilation,
                "Generated type name collision: example."
                        + "ExternalContractCollisionStore is already declared "
                        + "by example.ExternalContractCollisionStore");
    }

    @Test
    void rejectsExternalUserDeclaredGeneratedImplementationName()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.ExternalImplementationCollision"
                                + "__ColumnarProjectionStore",
                        "package example;\n"
                                + "public final class "
                                + "ExternalImplementationCollision"
                                + "__ColumnarProjectionStore { }\n"));
        assertSucceeded(external);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(external.classOutput),
                new StringSource(
                        "example.ExternalImplementationCollision",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface "
                                + "ExternalImplementationCollision {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(compilation,
                "Generated type name collision: example."
                        + "ExternalImplementationCollision"
                        + "__ColumnarProjectionStore is already declared by "
                        + "example.ExternalImplementationCollision"
                        + "__ColumnarProjectionStore");
    }

    @Test
    void rejectsExternalImplementationWithMismatchedForgedProvenance()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.ForgedProjection"
                                + "__ColumnarProjectionStore",
                        "package example;\n"
                                + "@ForgedProjection"
                                + "__ColumnarProjectionStore."
                                + "GeneratedProvenance(\n"
                                + "        generator = \"io.github.jutil."
                                + "columnarprojection.processor."
                                + "ProjectionSchemaProcessor:1\",\n"
                                + "        schema = "
                                + "\"example.ForgedProjection\",\n"
                                + "        store = "
                                + "\"example.WrongProjectionStore\",\n"
                                + "        implementation = \"example."
                                + "ForgedProjection"
                                + "__ColumnarProjectionStore\",\n"
                                + "        role = \"implementation\")\n"
                                + "public final class ForgedProjection"
                                + "__ColumnarProjectionStore {\n"
                                + "    @java.lang.annotation.Retention(\n"
                                + "            java.lang.annotation."
                                + "RetentionPolicy.CLASS)\n"
                                + "    @java.lang.annotation.Target(\n"
                                + "            java.lang.annotation."
                                + "ElementType.TYPE)\n"
                                + "    @interface GeneratedProvenance {\n"
                                + "        String generator();\n"
                                + "        String schema();\n"
                                + "        String store();\n"
                                + "        String implementation();\n"
                                + "        String role();\n"
                                + "    }\n"
                                + "}\n"));
        assertSucceeded(external);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(external.classOutput),
                new StringSource(
                        "example.ForgedProjection",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface ForgedProjection {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(compilation,
                "Generated type name collision: example.ForgedProjection"
                        + "__ColumnarProjectionStore is already declared by "
                        + "example.ForgedProjection"
                        + "__ColumnarProjectionStore");
    }

    @Test
    void rejectsExternalMemberTypeWithGeneratedContractBinaryName()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.Outer",
                        "package example;\n"
                                + "public final class Outer {\n"
                                + "    public interface ProjectionStore { }\n"
                                + "}\n"));
        assertSucceeded(external);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(external.classOutput),
                new StringSource(
                        "example.Outer$Projection",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Outer$Projection {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(compilation,
                "Generated type name collision: "
                        + "example.Outer$ProjectionStore is already declared "
                        + "by example.Outer.ProjectionStore");
    }

    @Test
    void rejectsCrossSchemaGeneratedNameCollision()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.GeneratedCollision",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "interface GeneratedCollision { int first(); }\n"
                        + "@ProjectionSchema\n"
                        + "interface GeneratedCollision__ColumnarProjection {\n"
                        + "    int second();\n"
                        + "}\n");

        assertFailedWith(compilation,
                "Generated type name collision: example."
                        + "GeneratedCollision__ColumnarProjectionStore");
    }

    @Test
    void rejectsNestedSchemaGeneratedNameCollision()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.NestedCollisionContainer",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "public final class NestedCollisionContainer {\n"
                        + "    @ProjectionSchema\n"
                        + "    interface Projection { int value(); }\n"
                        + "}\n"
                        + "interface NestedCollisionContainer$ProjectionStore"
                        + " { }\n");

        assertFailedWith(compilation,
                "Generated type name collision: example."
                        + "NestedCollisionContainer$ProjectionStore "
                        + "is already declared");
    }

    @Test
    void privateBatchImplementationNameIsCollisionSafe()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "BatchImplementation.Value",
                        "package BatchImplementation;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.BatchImplementationCollision",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "interface BatchImplementationCollision {\n"
                                + "    BatchImplementation.Value value();\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.BatchImplementationCollision");
        assertTrue(generated.contains(
                "private final class BatchImplementation_ implements"),
                generated);
        assertTrue(generated.contains(
                "BatchImplementation.Value[] source"), generated);
    }

    @Test
    void generatedStoreContractNameAvoidsRequiredSourceTypeRoots()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "SchemaStore.Value",
                        "package SchemaStore;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "interface Schema {\n"
                                + "    SchemaStore.Value value();\n"
                                + "}\n"
                                + "final class Usage {\n"
                                + "    SchemaStore_ create() {\n"
                                + "        return SchemaStore_.create(0);\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String contract = generatedStoreSourceWithName(
                compilation, "example.SchemaStore_");
        String implementation = generatedSource(
                compilation, "example.Schema");
        assertTrue(contract.contains(
                "public interface SchemaStore_"), contract);
        assertTrue(contract.contains(
                "Batch value(SchemaStore.Value[] source)"), contract);
        assertTrue(implementation.contains(
                "implements example.SchemaStore_"), implementation);
    }

    @Test
    void packageNamedLikeStoreContractSelectsUnderscore()
            throws IOException {
        Compilation packageCompilation = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.SchemaStore.Marker",
                        "package example.SchemaStore;\n"
                                + "public final class Marker { }\n"));
        assertSucceeded(packageCompilation);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(packageCompilation.classOutput),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String contract = generatedStoreSourceWithName(
                compilation, "example.SchemaStore_");
        String implementation = generatedSource(
                compilation, "example.Schema");
        assertTrue(contract.contains("public interface SchemaStore_"),
                contract);
        assertTrue(implementation.contains(
                "public final class Schema__ColumnarProjectionStore"),
                implementation);
        assertTrue(implementation.contains(
                "implements example.SchemaStore_"), implementation);
    }

    @Test
    void referencedExternalDescendantPackageSelectsUnderscore()
            throws IOException {
        Compilation external = compileWithoutProcessor(
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.SchemaStore.sub.Marker",
                        "package example.SchemaStore.sub;\n"
                                + "public final class Marker { }\n"));
        assertSucceeded(external);

        Compilation compilation = compileWithProcessor(
                Collections.singletonList(external.classOutput),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"),
                new StringSource(
                        "example.DescendantPackageConsumer",
                        "package example;\n"
                                + "public final class "
                                + "DescendantPackageConsumer {\n"
                                + "    private final SchemaStore_ store;\n"
                                + "    private final example.SchemaStore.sub."
                                + "Marker marker;\n"
                                + "    public DescendantPackageConsumer(\n"
                                + "            SchemaStore_ store,\n"
                                + "            example.SchemaStore.sub.Marker "
                                + "marker) {\n"
                                + "        this.store = store;\n"
                                + "        this.marker = marker;\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String contract = generatedStoreSourceWithName(
                compilation, "example.SchemaStore_");
        String implementation = generatedSource(
                compilation, "example.Schema");
        assertTrue(contract.contains("public interface SchemaStore_"),
                contract);
        assertTrue(implementation.contains(
                "implements example.SchemaStore_"), implementation);
    }

    @Test
    void multiplePackagesNamedLikeStoreContractExtendUnderscoreChain()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "example.SchemaStore.Marker",
                        "package example.SchemaStore;\n"
                                + "public final class Marker { }\n"),
                new StringSource(
                        "example.SchemaStore_.Marker",
                        "package example.SchemaStore_;\n"
                                + "public final class Marker { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String contract = generatedStoreSourceWithName(
                compilation, "example.SchemaStore__");
        String implementation = generatedSource(
                compilation, "example.Schema");
        assertTrue(contract.contains("public interface SchemaStore__"),
                contract);
        assertTrue(implementation.contains(
                "public final class Schema__ColumnarProjectionStore"),
                implementation);
        assertTrue(implementation.contains(
                "implements example.SchemaStore__"), implementation);
    }

    @Test
    void laterRequiredSourceRootExtendsStoreContractUnderscoreChain()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "example.SchemaStore.Marker",
                        "package example.SchemaStore;\n"
                                + "public final class Marker { }\n"),
                new StringSource(
                        "SchemaStore_.Value",
                        "package SchemaStore_;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    SchemaStore_.Value value();\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String contract = generatedStoreSourceWithName(
                compilation, "example.SchemaStore__");
        String implementation = generatedSource(
                compilation, "example.Schema");
        assertTrue(contract.contains("public interface SchemaStore__"),
                contract);
        assertTrue(contract.contains(
                "Batch value(SchemaStore_.Value[] source)"), contract);
        assertTrue(implementation.contains(
                "implements example.SchemaStore__"), implementation);
    }

    @Test
    void recompilesStoreContractSelectedForRequiredSourceRoot()
            throws IOException {
        String rootSource = "package SchemaStore;\n"
                + "public final class Value { }\n";
        String schemaSource = "package example;\n"
                + "import io.github.jutil.columnarprojection."
                + "ProjectionSchema;\n"
                + "@ProjectionSchema\n"
                + "public interface Schema {\n"
                + "    SchemaStore.Value value();\n"
                + "}\n";
        Path compilationDirectory = temporaryDirectory.resolve(
                "required-root-recompilation");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource("SchemaStore.Value", rootSource),
                new StringSource("example.Schema", schemaSource));

        assertSucceeded(first);
        String firstContract = generatedStoreSourceWithName(
                first, "example.SchemaStore_");

        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource("SchemaStore.Value", rootSource),
                new StringSource("example.Schema", schemaSource));

        assertSucceeded(second);
        assertEquals(firstContract, generatedStoreSourceWithName(
                second, "example.SchemaStore_"));
        assertTrue(generatedSource(second, "example.Schema").contains(
                "implements example.SchemaStore_"));
    }

    @Test
    void changedStoreContractNameRequiresCleanRebuild()
            throws IOException {
        Path compilationDirectory = temporaryDirectory.resolve(
                "changed-store-name");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(first);
        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource(
                        "SchemaStore.Value",
                        "package SchemaStore;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    SchemaStore.Value category();\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(second,
                "Generated store contract name changed for projection schema "
                        + "example.Schema from example.SchemaStore to "
                        + "example.SchemaStore_");
        assertFailedWith(second,
                "Clean the compilation output and recompile");
    }

    @Test
    void wildcardBoundChangingStoreContractNameRequiresCleanRebuild()
            throws IOException {
        Path compilationDirectory = temporaryDirectory.resolve(
                "wildcard-changed-store-name");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(first);
        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource(
                        "SchemaStore.Value",
                        "package SchemaStore;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    java.util.List<? extends "
                                + "SchemaStore.Value> values();\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(second,
                "Generated store contract name changed for projection schema "
                        + "example.Schema from example.SchemaStore to "
                        + "example.SchemaStore_");
        assertFailedWith(second,
                "Clean the compilation output and recompile");
    }

    @Test
    void genericArgumentChangingStoreContractNameRequiresCleanRebuild()
            throws IOException {
        Path compilationDirectory = temporaryDirectory.resolve(
                "generic-changed-store-name");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(first);
        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource(
                        "SchemaStore.Value",
                        "package SchemaStore;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    java.util.List<SchemaStore.Value> "
                                + "values();\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(second,
                "Generated store contract name changed for projection schema "
                        + "example.Schema from example.SchemaStore to "
                        + "example.SchemaStore_");
        assertFailedWith(second,
                "Clean the compilation output and recompile");
    }

    @Test
    void nestedEnclosingArrayRequiresCleanRebuild()
            throws IOException {
        Path compilationDirectory = temporaryDirectory.resolve(
                "nested-array-changed-store-name");
        Compilation first = compileWithProcessor(
                compilationDirectory,
                Collections.<Path>emptyList(),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    int value();\n"
                                + "}\n"));

        assertSucceeded(first);
        Compilation second = compileWithProcessor(
                compilationDirectory,
                Collections.singletonList(first.classOutput),
                new StringSource(
                        "SchemaStore.Owner",
                        "package SchemaStore;\n"
                                + "public final class Owner {\n"
                                + "    public final class Inner { }\n"
                                + "}\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    SchemaStore.Owner.Inner[] values();\n"
                                + "    int value();\n"
                                + "}\n"));

        assertFailedWith(second,
                "Generated store contract name changed for projection schema "
                        + "example.Schema from example.SchemaStore to "
                        + "example.SchemaStore_");
        assertFailedWith(second,
                "Clean the compilation output and recompile");
    }

    @Test
    void omitsTypeUseAnnotationsWithoutErasingTypedBatchColumns()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.AnnotatedTypes",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "import java.util.List;\n"
                        + "final class TopLevelValue { }\n"
                        + "public final class AnnotatedTypes {\n"
                        + "    @java.lang.annotation.Target("
                        + "java.lang.annotation.ElementType.TYPE_USE)\n"
                        + "    private @interface TypeUseMarker { }\n"
                        + "    interface Parent<T> {\n"
                        + "        java.util.List<@TypeUseMarker T> inherited();\n"
                        + "    }\n"
                        + "    @ProjectionSchema\n"
                        + "    public interface Schema extends "
                        + "Parent<@TypeUseMarker String> {\n"
                        + "        java.util.List<@TypeUseMarker String> "
                        + "annotatedArgument();\n"
                        + "        @TypeUseMarker List<String> "
                        + "annotatedDeclaredType();\n"
                        + "        @TypeUseMarker TopLevelValue "
                        + "annotatedTopLevel();\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.AnnotatedTypes$Schema");
        String contract = generatedStoreSource(
                compilation, "example.AnnotatedTypes$Schema");
        assertFalse(generated.contains("TypeUseMarker"), generated);
        assertFalse(contract.contains("TypeUseMarker"), contract);
        assertTrue(contract.contains(
                "annotatedArgument(java.util.List<java.lang.String>[] source)"),
                contract);
        assertTrue(contract.contains(
                "annotatedDeclaredType("
                        + "java.util.List<java.lang.String>[] source)"),
                contract);
        assertTrue(contract.contains(
                "annotatedTopLevel(example.TopLevelValue[] source)"),
                contract);
        assertTrue(contract.contains(
                "inherited(java.util.List<java.lang.String>[] source)"),
                contract);
        assertTrue(generated.contains("private java.util.List[] column"),
                generated);
    }

    @Test
    void rendersGenericWildcardNestedAndArrayBatchSourceTypes()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.LegalSourceTypes",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "final class Owner<T> {\n"
                        + "    final class Inner<U> { }\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "public interface LegalSourceTypes {\n"
                        + "    String[][] arrays();\n"
                        + "    java.util.List<String> generic();\n"
                        + "    java.util.List<String>[] genericArrays();\n"
                        + "    Owner<String>.Inner<Integer> member();\n"
                        + "    java.util.Map.Entry<String, Integer> nested();\n"
                        + "    java.util.List<? super Integer> lower();\n"
                        + "    java.util.List<?> unbounded();\n"
                        + "    java.util.List<? extends Number> upper();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.LegalSourceTypes");
        String contract = generatedStoreSource(
                compilation, "example.LegalSourceTypes");
        assertTrue(contract.contains(
                "arrays(java.lang.String[][][] source)"),
                contract);
        assertTrue(contract.contains(
                "generic(java.util.List<java.lang.String>[] source)"),
                contract);
        assertTrue(contract.contains(
                "genericArrays(java.util.List<java.lang.String>[][] source)"),
                contract);
        assertTrue(contract.contains(
                "member(example.Owner<java.lang.String>.Inner<"
                        + "java.lang.Integer>[] source)"),
                contract);
        assertTrue(contract.contains(
                "nested(java.util.Map.Entry<java.lang.String, "
                        + "java.lang.Integer>[] source)"),
                contract);
        assertTrue(contract.contains(
                "lower(java.util.List<? super java.lang.Integer>[] source)"),
                contract);
        assertTrue(contract.contains(
                "unbounded(java.util.List<?>[] source)"),
                contract);
        assertTrue(contract.contains(
                "upper(java.util.List<? extends java.lang.Number>[] source)"),
                contract);
        assertTrue(generated.contains("private java.util.List[][] column"),
                generated);
    }

    @Test
    void unnamedPackageSchemaNamedBatchUsesCollisionSafeBatchType()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "Batch",
                "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface Batch {\n"
                        + "    int value();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(compilation, "Batch");
        String contract = generatedStoreSource(compilation, "Batch");
        assertTrue(generated.contains(
                "implements BatchStore"), generated);
        assertTrue(contract.contains(
                "ProjectionStore<Batch>"), contract);
        assertTrue(contract.contains("Batch_ batch()"), contract);
        assertTrue(contract.contains("interface Batch_"), contract);
        assertTrue(generated.contains(
                "private BatchImplementation()"), generated);
    }

    @Test
    void unnamedPackageAccessorReturningBatchUsesCollisionSafeBatchType()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "ProjectionReturningBatch",
                "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "final class Batch { }\n"
                        + "@ProjectionSchema\n"
                        + "interface ProjectionReturningBatch {\n"
                        + "    Batch value();\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "ProjectionReturningBatch");
        String contract = generatedStoreSource(
                compilation, "ProjectionReturningBatch");
        assertTrue(generated.contains("private Batch[] column0;"), generated);
        assertTrue(contract.contains("Batch_ batch()"), contract);
        assertTrue(contract.contains(
                "Batch_ value(Batch[] source)"), contract);
        assertTrue(generated.contains("public Batch value()"), generated);
    }

    @Test
    void namedBatchRootPackageAccessorUsesCollisionSafeBatchType()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "Batch.Value",
                        "package Batch;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.Schema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface Schema {\n"
                                + "    Batch.Value value();\n"
                                + "}\n"
                                + "final class Usage {\n"
                                + "    void append("
                                + "Schema__ColumnarProjectionStore store, "
                                + "Batch.Value[] values) {\n"
                                + "        store.batch().value(values)"
                                + ".append();\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String generated = generatedSource(compilation, "example.Schema");
        String contract = generatedStoreSource(
                compilation, "example.Schema");
        assertTrue(generated.contains("private Batch.Value[] column0;"),
                generated);
        assertTrue(contract.contains("Batch_ batch()"), contract);
        assertTrue(contract.contains(
                "Batch_ value(Batch.Value[] source)"), contract);
    }

    @Test
    void schemaUnderBatchRootPackageUsesCollisionSafeBatchType()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "Batch.example.Schema",
                "package Batch.example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface Schema {\n"
                        + "    int value();\n"
                        + "}\n"
                        + "final class Usage {\n"
                        + "    void append("
                        + "Schema__ColumnarProjectionStore store) {\n"
                        + "        store.batch().value(new int[] {1})"
                        + ".append();\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "Batch.example.Schema");
        String contract = generatedStoreSource(
                compilation, "Batch.example.Schema");
        assertTrue(generated.contains(
                "implements Batch.example.SchemaStore"), generated);
        assertTrue(contract.contains(
                "ProjectionStore<Batch.example.Schema>"), contract);
        assertTrue(contract.contains("Batch_ batch()"), contract);
        assertTrue(contract.contains("interface Batch_"), contract);
    }

    @Test
    void namedPackageRootsInGenericArgumentsDriveCollisionChain()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                new StringSource(
                        "Batch.Value",
                        "package Batch;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "Batch_.Value",
                        "package Batch_;\n"
                                + "public final class Value { }\n"),
                new StringSource(
                        "example.RootPackageCollisionSchema",
                        "package example;\n"
                                + "import io.github.jutil.columnarprojection."
                                + "ProjectionSchema;\n"
                                + "@ProjectionSchema\n"
                                + "public interface "
                                + "RootPackageCollisionSchema {\n"
                                + "    java.util.Map<Batch.Value[], "
                                + "? extends Batch_.Value> values();\n"
                                + "}\n"
                                + "final class Usage {\n"
                                + "    void append("
                                + "RootPackageCollisionSchema"
                                + "__ColumnarProjectionStore store,\n"
                                + "            java.util.Map<Batch.Value[], "
                                + "? extends Batch_.Value>[] values) {\n"
                                + "        store.batch().values(values)"
                                + ".append();\n"
                                + "    }\n"
                                + "}\n"));

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "example.RootPackageCollisionSchema");
        String contract = generatedStoreSource(
                compilation, "example.RootPackageCollisionSchema");
        assertTrue(contract.contains("Batch__ batch()"), contract);
        assertTrue(contract.contains("interface Batch__"), contract);
        assertTrue(contract.contains(
                "Batch__ values(java.util.Map<Batch.Value[], "
                        + "? extends Batch_.Value>[] source)"), contract);
        assertTrue(generated.contains(
                "implements example.RootPackageCollisionSchemaStore.Batch__"),
                generated);
    }

    @Test
    void unnamedSchemaAndAccessorNamesDriveCollisionChain()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "Batch",
                "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "final class Batch_ { }\n"
                        + "@ProjectionSchema\n"
                        + "public interface Batch {\n"
                        + "    Batch_ value();\n"
                        + "}\n"
                        + "final class Usage {\n"
                        + "    void append(Batch__ColumnarProjectionStore "
                        + "store, Batch_[] values) {\n"
                        + "        store.batch().value(values).append();\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(compilation, "Batch");
        String contract = generatedStoreSource(compilation, "Batch");
        assertTrue(contract.contains("Batch__ batch()"), contract);
        assertTrue(contract.contains(
                "Batch__ value(Batch_[] source)"), contract);
        assertTrue(generated.contains(
                "implements BatchStore.Batch__"), generated);
    }

    @Test
    void enclosingParameterizedTypesDriveBatchCollisionChain()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "EnclosingCollisionUsage",
                "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "final class Batch { }\n"
                        + "final class Batch_ { }\n"
                        + "final class Owner<T> {\n"
                        + "    final class Inner<U> { }\n"
                        + "}\n"
                        + "@ProjectionSchema\n"
                        + "interface EnclosingCollisionSchema {\n"
                        + "    Owner<Batch>.Inner<String> first();\n"
                        + "    Owner<Batch_>.Inner<String> second();\n"
                        + "}\n"
                        + "final class EnclosingCollisionUsage {\n"
                        + "    void append(\n"
                        + "            EnclosingCollisionSchema__ColumnarProjectionStore store,\n"
                        + "            Owner<Batch>.Inner<String>[] first,\n"
                        + "            Owner<Batch_>.Inner<String>[] second) {\n"
                        + "        store.batch().first(first)"
                        + ".second(second);\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(
                compilation, "EnclosingCollisionSchema");
        String contract = generatedStoreSource(
                compilation, "EnclosingCollisionSchema");
        assertTrue(contract.contains("Batch__ batch()"), contract);
        assertTrue(contract.contains("interface Batch__"), contract);
        assertTrue(contract.contains(
                "Batch__ first(Owner<Batch>.Inner<java.lang.String>[] "
                        + "source)"), contract);
        assertTrue(contract.contains(
                "Batch__ second(Owner<Batch_>.Inner<java.lang.String>[] "
                        + "source)"), contract);
        assertTrue(generated.contains(
                "implements EnclosingCollisionSchemaStore.Batch__"),
                generated);
    }

    @Test
    void acceptsCompatibleGenericSubtypeArrayForBatchColumn()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.CompatibleGenericBatchUsage",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "interface GenericBatchSchema {\n"
                        + "    java.util.List<String> labels();\n"
                        + "}\n"
                        + "final class CompatibleGenericBatchUsage {\n"
                        + "    void append(\n"
                        + "            GenericBatchSchema__ColumnarProjectionStore store,\n"
                        + "            java.util.ArrayList<String>[] labels) {\n"
                        + "        store.batch().labels(labels);\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        assertGeneratedSourceContains(compilation,
                "example.GenericBatchSchema",
                "labels(java.util.List<java.lang.String>[] source)");
    }

    @Test
    void rejectsIncompatibleGenericSubtypeArrayForBatchColumn()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.IncompatibleGenericBatchUsage",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "interface GenericBatchSchema {\n"
                        + "    java.util.List<String> labels();\n"
                        + "}\n"
                        + "final class IncompatibleGenericBatchUsage {\n"
                        + "    void append(\n"
                        + "            GenericBatchSchema__ColumnarProjectionStore store,\n"
                        + "            java.util.ArrayList<Integer>[] labels) {\n"
                        + "        store.batch().labels(labels);\n"
                        + "    }\n"
                        + "}\n");

        assertFailedWith(compilation, "incompatible types");
    }

    @Test
    void inaccessibleGenericArgumentsFallBackToErasedBatchColumn()
            throws IOException {
        Compilation compilation = compileWithProcessor(
                "example.GenericFallbackContainer",
                "package example;\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "public final class GenericFallbackContainer {\n"
                        + "    private static final class Hidden { }\n"
                        + "    @ProjectionSchema\n"
                        + "    public interface Schema {\n"
                        + "        java.util.List<Hidden> values();\n"
                        + "    }\n"
                        + "}\n");

        assertSucceeded(compilation);
        String generated = generatedSource(compilation,
                "example.GenericFallbackContainer$Schema");
        String contract = generatedStoreSource(compilation,
                "example.GenericFallbackContainer$Schema");
        assertTrue(generated.contains("private java.util.List[] source0;"),
                generated);
        assertTrue(contract.contains(
                "Batch values(java.util.List[] source)"), contract);
        assertFalse(generated.contains("GenericFallbackContainer.Hidden"),
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
        return compile(className, source, ProcessorMode.EXPLICIT);
    }

    private Compilation compileWithProcessor(StringSource... sources)
            throws IOException {
        return compile(
                Arrays.asList(sources),
                ProcessorMode.EXPLICIT,
                Files.createTempDirectory(temporaryDirectory, "compiler-"),
                Collections.<Path>emptyList());
    }

    private Compilation compileWithProcessor(
            List<Path> additionalClassPath,
            StringSource... sources) throws IOException {
        return compile(
                Arrays.asList(sources),
                ProcessorMode.EXPLICIT,
                Files.createTempDirectory(temporaryDirectory, "compiler-"),
                additionalClassPath);
    }

    private Compilation compileWithProcessor(
            Path compilationDirectory,
            List<Path> additionalClassPath,
            StringSource... sources) throws IOException {
        return compile(
                Arrays.asList(sources),
                ProcessorMode.EXPLICIT,
                compilationDirectory,
                additionalClassPath);
    }

    private Compilation compileWithoutProcessor(
            List<Path> additionalClassPath,
            StringSource... sources) throws IOException {
        return compile(
                Arrays.asList(sources),
                ProcessorMode.DISABLED,
                Files.createTempDirectory(temporaryDirectory, "compiler-"),
                additionalClassPath);
    }

    private Compilation compileUsingServiceDiscovery(
            String className, String source) throws IOException {
        return compile(className, source, ProcessorMode.SERVICE_DISCOVERY);
    }

    private Compilation compile(
            String className,
            String source,
            ProcessorMode processorMode) throws IOException {
        return compile(
                Collections.singletonList(
                        new StringSource(className, source)),
                processorMode,
                Files.createTempDirectory(temporaryDirectory, "compiler-"),
                Collections.<Path>emptyList());
    }

    private Compilation compile(
            Iterable<? extends JavaFileObject> compilationUnits,
            ProcessorMode processorMode,
            Path compilationDirectory,
            List<Path> additionalClassPath) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null,
                "Tests must run on a JDK with the system Java compiler");

        Files.createDirectories(compilationDirectory);
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
                    "-source", "8",
                    "-target", "8",
                    "-Xlint:-options"));
            if (processorMode == ProcessorMode.SERVICE_DISCOVERY) {
                options.add("-processorpath");
                options.add(System.getProperty("java.class.path"));
            } else if (processorMode == ProcessorMode.DISABLED) {
                options.add("-proc:none");
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    compilationUnits);
            if (processorMode == ProcessorMode.EXPLICIT) {
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
                ProjectionSchemaProcessorCompilationTest.class
                        .getClassLoader())) {
            Class<?> verificationClass = Class.forName(
                    className, true, loader);
            verificationClass.getMethod("verify").invoke(null);
        }
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

    private static String generatedStoreSource(
            Compilation compilation, String schemaClassName)
            throws IOException {
        return generatedStoreSourceWithName(
                compilation, schemaClassName + GENERATED_STORE_SUFFIX);
    }

    private static String generatedStoreSourceWithName(
            Compilation compilation, String generatedClassName)
            throws IOException {
        Path generatedSource = compilation.generatedSourceOutput.resolve(
                generatedClassName.replace('.', '/')
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

    private static String methodSource(String value, String signature) {
        int methodStart = value.indexOf(signature);
        assertTrue(methodStart >= 0,
                "Expected method signature <" + signature + "> in:\n" + value);
        int openingBrace = value.indexOf('{', methodStart);
        assertTrue(openingBrace >= 0,
                "Expected method body for <" + signature + "> in:\n" + value);
        int depth = 0;
        for (int index = openingBrace; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    return value.substring(methodStart, index + 1);
                }
            }
        }
        fail("Unclosed method body for <" + signature + "> in:\n" + value);
        throw new AssertionError("unreachable");
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

    private enum ProcessorMode {
        EXPLICIT,
        SERVICE_DISCOVERY,
        DISABLED
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
