package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ArtifactBoundaryIT {

    private static final int WIDE_SCHEMA_COLUMN_COUNT = 1500;
    private static final int BATCH_HELPER_COLUMN_LIMIT = 128;
    private static final String PROCESSOR_SERVICE =
            "META-INF/services/javax.annotation.processing.Processor";
    private static final String PROCESSOR_CLASS =
            "io.github.jutil.columnarprojection.processor."
                    + "ProjectionSchemaProcessor";

    @TempDir
    Path temporaryDirectory;

    @Test
    void builtJarsHaveSeparateRuntimeAndProcessorContents() throws Exception {
        Path coreJar = configuredJar("core.jar");
        Path processorJar = configuredJar("processor.jar");

        Set<String> coreEntries = jarEntries(coreJar);
        Set<String> coreClasses = classEntries(coreEntries);
        assertEquals(new LinkedHashSet<String>(Arrays.asList(
                "io/github/jutil/columnarprojection/ProjectionCursor.class",
                "io/github/jutil/columnarprojection/ProjectionSchema.class",
                "io/github/jutil/columnarprojection/ProjectionStore.class",
                "io/github/jutil/columnarprojection/ProjectionStores.class",
                "io/github/jutil/columnarprojection/package-info.class")),
                coreClasses);
        assertFalse(coreEntries.contains(PROCESSOR_SERVICE));
        assertFalse(containsPrefix(coreEntries,
                "io/github/jutil/columnarprojection/processor/"));

        Set<String> processorEntries = jarEntries(processorJar);
        assertTrue(processorEntries.contains(
                "io/github/jutil/columnarprojection/processor/"
                        + "ProjectionSchemaProcessor.class"));
        assertTrue(processorEntries.contains(
                "io/github/jutil/columnarprojection/processor/"
                        + "package-info.class"));
        assertTrue(processorEntries.contains(PROCESSOR_SERVICE));
        assertFalse(processorEntries.contains(
                "io/github/jutil/columnarprojection/ProjectionStore.class"));

        try (JarFile jar = new JarFile(processorJar.toFile())) {
            JarEntry serviceEntry = jar.getJarEntry(PROCESSOR_SERVICE);
            assertNotNull(serviceEntry);
            assertEquals(PROCESSOR_CLASS,
                    readUtf8(jar.getInputStream(serviceEntry)).trim());
        }
    }

    @Test
    void consumerCompilesWithSeparatedClasspathAndProcessorPath()
            throws Exception {
        Path coreJar = configuredJar("core.jar");
        Path processorJar = configuredJar("processor.jar");
        ConsumerCompilation compilation = compileConsumer(
                coreJar,
                processorJar,
                "consumer.ConsumerProjection",
                "package consumer;\n"
                        + "import io.github.jutil."
                        + "columnarprojection.ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface ConsumerProjection {\n"
                        + "    long identifier();\n"
                        + "}\n"
                        + "final class TypedConsumer {\n"
                        + "    void use() {\n"
                        + "        ConsumerProjectionStore store =\n"
                        + "                ConsumerProjectionStore.create(2);\n"
                        + "        store.batch()\n"
                        + "                .identifier(new long[]{1L, 2L})\n"
                        + "                .append();\n"
                        + "        store.batch(0, 1)\n"
                        + "                .identifier(new long[]{3L})\n"
                        + "                .append();\n"
                        + "    }\n"
                        + "}\n",
                "ordinary-consumer");

        assertTrue(compilation.succeeded,
                diagnosticsText(compilation.diagnostics));
        Path generatedSource = compilation.generatedSources.resolve(
                "consumer/ConsumerProjection__ColumnarProjectionStore.java");
        Path generatedClass = compilation.classes.resolve(
                "consumer/ConsumerProjection__ColumnarProjectionStore.class");
        Path generatedStoreSource = compilation.generatedSources.resolve(
                "consumer/ConsumerProjectionStore.java");
        Path generatedStoreClass = compilation.classes.resolve(
                "consumer/ConsumerProjectionStore.class");
        assertTrue(Files.isRegularFile(generatedSource));
        assertTrue(Files.isRegularFile(generatedClass));
        assertTrue(Files.isRegularFile(generatedStoreSource));
        assertTrue(Files.isRegularFile(generatedStoreClass));
        assertEquals(52, classFileMajorVersion(generatedClass),
                "Generated source must compile to Java 8 bytecode");
        assertEquals(52, classFileMajorVersion(generatedStoreClass),
                "Generated contract must compile to Java 8 bytecode");
    }

    @Test
    void namedModuleConsumerCompilesAndRunsWithSeparatedArtifacts()
            throws Exception {
        assumeFalse(isJava8Runtime(),
                "Named-module compilation requires JDK 9 or newer");
        Path coreJar = configuredJar("core.jar");
        Path processorJar = configuredJar("processor.jar");
        ConsumerCompilation compilation = compileNamedModuleConsumer(
                coreJar, processorJar);

        assertTrue(compilation.succeeded,
                diagnosticsText(compilation.diagnostics));
        assertTrue(Files.isRegularFile(
                compilation.classes.resolve("module-info.class")));
        assertTrue(Files.isRegularFile(compilation.generatedSources.resolve(
                "consumer/NamedModuleProjectionStore_.java")));
        assertTrue(Files.isRegularFile(compilation.generatedSources.resolve(
                "consumer/NamedModuleProjection"
                        + "__ColumnarProjectionStore.java")));
        assertTrue(Files.isRegularFile(compilation.classes.resolve(
                "consumer/NamedModuleProjectionStore_.class")));
        assertTrue(Files.isRegularFile(compilation.classes.resolve(
                "consumer/NamedModuleProjection"
                        + "__ColumnarProjectionStore.class")));

        Path javaExecutable = javaExecutable();
        String modulePath = compilation.classes.toString()
                + java.io.File.pathSeparator + coreJar;
        Process process = new ProcessBuilder(
                javaExecutable.toString(),
                "--module-path", modulePath,
                "--module",
                "consumer.app/consumer.NamedModuleConsumer")
                .redirectErrorStream(true)
                .start();
        String output = readUtf8(process.getInputStream());
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        assertTrue(output.contains("named-module-ok"), output);
    }

    @Test
    void wideConsumerSchemaCompilesWithBoundedBatchHelpers()
            throws Exception {
        Path coreJar = configuredJar("core.jar");
        Path processorJar = configuredJar("processor.jar");
        ConsumerCompilation compilation = compileConsumer(
                coreJar,
                processorJar,
                "consumer.WideProjection",
                wideSchemaSource(WIDE_SCHEMA_COLUMN_COUNT),
                "wide-consumer");

        assertTrue(compilation.succeeded,
                diagnosticsText(compilation.diagnostics));
        Path generatedSource = compilation.generatedSources.resolve(
                "consumer/WideProjection__ColumnarProjectionStore.java");
        Path generatedClass = compilation.classes.resolve(
                "consumer/WideProjection__ColumnarProjectionStore.class");
        Path generatedStoreSource = compilation.generatedSources.resolve(
                "consumer/WideProjectionStore.java");
        Path generatedStoreClass = compilation.classes.resolve(
                "consumer/WideProjectionStore.class");
        assertTrue(Files.isRegularFile(generatedSource));
        assertTrue(Files.isRegularFile(generatedClass));
        assertTrue(Files.isRegularFile(generatedStoreSource));
        assertTrue(Files.isRegularFile(generatedStoreClass));
        assertEquals(52, classFileMajorVersion(generatedClass),
                "Generated source must compile to Java 8 bytecode");
        assertEquals(52, classFileMajorVersion(generatedStoreClass),
                "Generated contract must compile to Java 8 bytecode");

        String generated = new String(
                Files.readAllBytes(generatedSource), StandardCharsets.UTF_8);
        assertBoundedBatchHelpers(generated, WIDE_SCHEMA_COLUMN_COUNT);
        assertBatchImplementationHasNoBridgeMethods(
                coreJar, compilation.classes,
                "consumer.WideProjection__ColumnarProjectionStore"
                        + "$BatchImplementation",
                "consumer.WideProjectionStore$Batch");
    }

    private ConsumerCompilation compileConsumer(
            Path coreJar,
            Path processorJar,
            String className,
            String source,
            String directoryName) throws IOException {
        Path compilationDirectory = Files.createDirectories(
                temporaryDirectory.resolve(directoryName));
        Path classes = Files.createDirectories(
                compilationDirectory.resolve("classes"));
        Path generatedSources = Files.createDirectories(
                compilationDirectory.resolve("generated-sources"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,
                "Integration tests require a JDK with javac");
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        boolean succeeded;
        try {
            List<String> options = new ArrayList<String>(Arrays.asList(
                    "-classpath", coreJar.toString(),
                    "-processorpath", processorJar.toString(),
                    "-processor", PROCESSOR_CLASS,
                    "-d", classes.toString(),
                    "-s", generatedSources.toString(),
                    "-source", "8",
                    "-target", "8",
                    "-Xlint:-options"));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    Collections.singletonList(new StringSource(
                            className, source)));
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }
        return new ConsumerCompilation(
                succeeded,
                classes,
                generatedSources,
                diagnostics.getDiagnostics());
    }

    private ConsumerCompilation compileNamedModuleConsumer(
            Path coreJar, Path processorJar) throws IOException {
        Path compilationDirectory = Files.createDirectories(
                temporaryDirectory.resolve("named-module-consumer"));
        Path classes = Files.createDirectories(
                compilationDirectory.resolve("classes"));
        Path generatedSources = Files.createDirectories(
                compilationDirectory.resolve("generated-sources"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,
                "Integration tests require a JDK with javac");
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        boolean succeeded;
        try {
            List<String> options = new ArrayList<String>(Arrays.asList(
                    "--module-path", coreJar.toString(),
                    "-processorpath", processorJar.toString(),
                    "-processor", PROCESSOR_CLASS,
                    "-d", classes.toString(),
                    "-s", generatedSources.toString(),
                    "--release", "9"));
            List<StringSource> sources = Arrays.asList(
                    new StringSource(
                            "module-info",
                            "module consumer.app {\n"
                                    + "    requires "
                                    + "columnar.projection.store;\n"
                                    + "    exports consumer;\n"
                                    + "}\n"),
                    new StringSource(
                            "consumer.NamedModuleProjection",
                            "package consumer;\n"
                                    + "import io.github.jutil."
                                    + "columnarprojection.ProjectionSchema;\n"
                                    + "@ProjectionSchema\n"
                                    + "public interface "
                                    + "NamedModuleProjection {\n"
                                    + "    long identifier();\n"
                                    + "    String symbol();\n"
                                    + "}\n"),
                    new StringSource(
                            "consumer.NamedModuleProjectionStore.sub.Marker",
                            "package consumer.NamedModuleProjectionStore.sub;\n"
                                    + "public final class Marker { }\n"),
                    new StringSource(
                            "consumer.NamedModuleConsumer",
                            namedModuleConsumerSource()));
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    sources);
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }
        return new ConsumerCompilation(
                succeeded,
                classes,
                generatedSources,
                diagnostics.getDiagnostics());
    }

    private static String namedModuleConsumerSource() {
        return "package consumer;\n"
                + "public final class NamedModuleConsumer {\n"
                + "    private NamedModuleConsumer() { }\n"
                + "    public static void main(String[] arguments) {\n"
                + "        NamedModuleProjectionStore_ store =\n"
                + "                NamedModuleProjectionStore_.create(1);\n"
                + "        store.batch()\n"
                + "                .identifier(new long[]{10L, 20L})\n"
                + "                .symbol(new String[]{\"A\", \"B\"})\n"
                + "                .append();\n"
                + "        store.batch(1, 3)\n"
                + "                .identifier(new long[]{0L, 30L, 40L})\n"
                + "                .symbol(new String[]{\"ignored\", "
                + "\"C\", \"D\"})\n"
                + "                .append();\n"
                + "        if (store.size() != 4) {\n"
                + "            throw new AssertionError(\"unexpected size\");\n"
                + "        }\n"
                + "        store.seal();\n"
                + "        if (store.viewAt(0).identifier() != 10L\n"
                + "                || !\"A\".equals("
                + "store.viewAt(0).symbol())\n"
                + "                || store.viewAt(3).identifier() != 40L\n"
                + "                || !\"D\".equals("
                + "store.viewAt(3).symbol())) {\n"
                + "            throw new AssertionError(\"unexpected rows\");\n"
                + "        }\n"
                + "        System.out.println(\"named-module-ok\");\n"
                + "    }\n"
                + "}\n";
    }

    private static String wideSchemaSource(int columnCount) {
        StringBuilder source = new StringBuilder();
        source.append("package consumer;\n")
                .append("import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n")
                .append("@ProjectionSchema\n")
                .append("public interface WideProjection {\n");
        for (int index = 0; index < columnCount; index++) {
            source.append("    int value").append(index).append("();\n");
        }
        return source.append("}\n").toString();
    }

    private static void assertBoundedBatchHelpers(
            String generated, int columnCount) {
        int batchStart = generated.indexOf(
                "private final class BatchImplementation");
        int batchEnd = generated.indexOf(
                "private final class ProjectionView", batchStart);
        assertTrue(batchStart >= 0, generated);
        assertTrue(batchEnd > batchStart, generated);
        String batch = generated.substring(batchStart, batchEnd);
        String append = methodSource(batch, "public void append()");
        int helperCount = (columnCount + BATCH_HELPER_COLUMN_LIMIT - 1)
                / BATCH_HELPER_COLUMN_LIMIT;
        assertTrue(helperCount > 1, "Wide schema must use multiple chunks");

        int unconsumedCheck = append.indexOf("requireUnconsumed();");
        int sealedCheck = append.indexOf("if (sealed)");
        int overflowCheck = append.indexOf(
                "rowCount > java.lang.Integer.MAX_VALUE - size");
        int destinationCalculation = append.indexOf(
                "final int destinationOffset = size;");
        int requiredSizeCalculation = append.indexOf(
                "final int requiredSize = destinationOffset + rowCount;");
        int capacityReservation = append.indexOf(
                "ensureCapacity(requiredSize);");
        int positiveBatchGuard = append.indexOf("if (rowCount != 0)");
        int sizePublication = append.indexOf("size = requiredSize;");
        int consumedPublication = append.indexOf("consumed = true;");
        assertTrue(unconsumedCheck >= 0 && unconsumedCheck < sealedCheck,
                append);
        assertTrue(overflowCheck < destinationCalculation, append);
        assertTrue(destinationCalculation < requiredSizeCalculation, append);
        assertTrue(requiredSizeCalculation < capacityReservation, append);
        assertTrue(capacityReservation < positiveBatchGuard, append);
        assertEquals(1, countOccurrences(
                append, "ensureCapacity(requiredSize);"), append);
        assertEquals(0, countOccurrences(
                append, "java.lang.System.arraycopy("), append);

        int copyHelperArrayCopies = 0;
        for (int helperIndex = 0; helperIndex < helperCount; helperIndex++) {
            String requiredSignature = "private void requireColumns"
                    + helperIndex + "()";
            String copySignature = "private void copyColumns" + helperIndex
                    + "(int destinationOffset)";
            String clearSignature = "private void clearSources" + helperIndex
                    + "()";
            String requiredHelper = methodSource(batch, requiredSignature);
            String copyHelper = methodSource(batch, copySignature);
            String clearHelper = methodSource(batch, clearSignature);
            int columnsInHelper = Math.min(
                    BATCH_HELPER_COLUMN_LIMIT,
                    columnCount - helperIndex * BATCH_HELPER_COLUMN_LIMIT);

            int requiredCall = append.indexOf(
                    "requireColumns" + helperIndex + "();");
            int copyCall = append.indexOf(
                    "copyColumns" + helperIndex + "(destinationOffset);");
            int clearCall = append.indexOf(
                    "clearSources" + helperIndex + "();");
            assertTrue(requiredCall > sealedCheck
                    && requiredCall < overflowCheck, append);
            assertTrue(copyCall > positiveBatchGuard
                    && copyCall < sizePublication, append);
            assertTrue(clearCall > sizePublication
                    && clearCall < consumedPublication, append);

            assertEquals(columnsInHelper,
                    countOccurrences(requiredHelper, "&& !assigned"),
                    requiredHelper);
            assertEquals(columnsInHelper,
                    countOccurrences(
                            copyHelper, "java.lang.System.arraycopy("),
                    copyHelper);
            assertEquals(columnsInHelper,
                    countOccurrences(clearHelper, " = null;"), clearHelper);
            assertTrue(columnsInHelper <= BATCH_HELPER_COLUMN_LIMIT,
                    "Helper exceeded column limit");

            assertEquals(0, countOccurrences(
                    requiredHelper, "java.lang.System.arraycopy("),
                    requiredHelper);
            assertEquals(0, countOccurrences(
                    copyHelper, "&& !assigned"), copyHelper);
            assertEquals(0, countOccurrences(
                    clearHelper, "java.lang.System.arraycopy("), clearHelper);
            copyHelperArrayCopies += countOccurrences(
                    copyHelper, "java.lang.System.arraycopy(");
        }

        assertEquals(helperCount,
                countOccurrences(append, "requireColumns"), append);
        assertEquals(helperCount,
                countOccurrences(append, "copyColumns"), append);
        assertEquals(helperCount,
                countOccurrences(append, "clearSources"), append);
        assertEquals(columnCount, copyHelperArrayCopies, batch);
        assertEquals(columnCount, countOccurrences(
                generated, "java.lang.System.arraycopy("), generated);
    }

    private static void assertBatchImplementationHasNoBridgeMethods(
            Path coreJar,
            Path classes,
            String implementationName,
            String contractName) throws Exception {
        URL[] classPath = new URL[] {
            classes.toUri().toURL(),
            coreJar.toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(classPath, null)) {
            Class<?> implementation = Class.forName(
                    implementationName, false, loader);
            Class<?> contract = Class.forName(contractName, false, loader);
            assertEquals(1, implementation.getInterfaces().length);
            assertEquals(contract, implementation.getInterfaces()[0]);
            for (Method method : implementation.getDeclaredMethods()) {
                assertFalse(method.isBridge(), method.toString());
            }
        }
    }

    private static String methodSource(String value, String signature) {
        int methodStart = value.indexOf(signature);
        assertTrue(methodStart >= 0,
                "Expected method signature <" + signature + "> in source");
        int openingBrace = value.indexOf('{', methodStart);
        assertTrue(openingBrace >= 0,
                "Expected method body for <" + signature + "> in source");
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
        throw new AssertionError(
                "Unclosed method body for <" + signature + ">");
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

    private static boolean isJava8Runtime() {
        String version = System.getProperty("java.specification.version");
        return "1.8".equals(version) || "8".equals(version);
    }

    private static Path javaExecutable() {
        String executableName = System.getProperty("os.name")
                .toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe"
                        : "java";
        Path executable = Paths.get(
                System.getProperty("java.home"), "bin", executableName);
        assertTrue(Files.isRegularFile(executable),
                "Expected Java executable " + executable);
        return executable;
    }

    private static Path configuredJar(String propertyName) {
        String value = System.getProperty(propertyName);
        assertNotNull(value, propertyName + " was not configured");
        Path jar = Paths.get(value);
        assertTrue(Files.isRegularFile(jar), "Expected built JAR " + jar);
        return jar;
    }

    private static Set<String> jarEntries(Path jarPath) throws IOException {
        Set<String> entries = new LinkedHashSet<String>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement().getName());
            }
        }
        return entries;
    }

    private static Set<String> classEntries(Set<String> entries) {
        Set<String> classes = new LinkedHashSet<String>();
        for (String entry : entries) {
            if (entry.endsWith(".class")) {
                classes.add(entry);
            }
        }
        return classes;
    }

    private static boolean containsPrefix(
            Set<String> entries, String prefix) {
        for (String entry : entries) {
            if (entry.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String readUtf8(InputStream input) throws IOException {
        try {
            byte[] buffer = new byte[256];
            StringBuilder value = new StringBuilder();
            int count;
            while ((count = input.read(buffer)) >= 0) {
                value.append(new String(
                        buffer, 0, count, StandardCharsets.UTF_8));
            }
            return value.toString();
        } finally {
            input.close();
        }
    }

    private static int classFileMajorVersion(Path classFile)
            throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        assertTrue(bytes.length >= 8, "Invalid class file " + classFile);
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    private static String diagnosticsText(
            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder text = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            text.append(diagnostic.getKind())
                    .append(": ")
                    .append(diagnostic.getMessage(Locale.ROOT))
                    .append('\n');
        }
        return text.toString();
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        private StringSource(String className, String source) {
            super(java.net.URI.create("string:///"
                    + className.replace('.', '/')
                    + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class ConsumerCompilation {
        private final boolean succeeded;
        private final Path classes;
        private final Path generatedSources;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        private ConsumerCompilation(
                boolean succeeded,
                Path classes,
                Path generatedSources,
                List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            this.succeeded = succeeded;
            this.classes = classes;
            this.generatedSources = generatedSources;
            this.diagnostics = diagnostics;
        }
    }
}
