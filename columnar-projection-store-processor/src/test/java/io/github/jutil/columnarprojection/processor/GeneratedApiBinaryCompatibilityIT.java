package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
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

final class GeneratedApiBinaryCompatibilityIT {

    private static final String PACKAGE_NAME = "compatibility.v12";
    private static final String PROJECTION_NAME =
            PACKAGE_NAME + ".BinaryCompatibilityProjection";
    private static final String CONTRACT_NAME =
            PACKAGE_NAME + ".BinaryCompatibilityProjectionStore";
    private static final String IMPLEMENTATION_NAME = PACKAGE_NAME
            + ".BinaryCompatibilityProjection__ColumnarProjectionStore";
    private static final String CONSUMER_NAME =
            PACKAGE_NAME + ".PrecompiledV12Consumer";
    private static final String PRECOMPILED_IMPLEMENTATION_NAME =
            PACKAGE_NAME + ".PrecompiledV12Implementation";
    private static final String PROCESSOR_CLASS =
            "io.github.jutil.columnarprojection.processor."
                    + "ProjectionSchemaProcessor";

    @TempDir
    Path temporaryDirectory;

    @Test
    void precompiledV12ConsumerRunsAgainstNewGeneratedApi()
            throws Exception {
        Path coreJar = configuredJar("core.jar");
        Path processorJar = configuredJar("processor.jar");

        Compilation currentApi = compileGeneratedApi(
                coreJar, processorJar);
        assertTrue(currentApi.succeeded,
                diagnosticsText(currentApi.diagnostics));

        Compilation legacyApi = compilePlain(
                "legacy-api",
                Collections.singletonList(Files.createDirectories(
                        temporaryDirectory.resolve("empty-classpath"))),
                legacyProjectionCursorSource(),
                legacyProjectionStoreSource(),
                legacyProjectionSource(),
                legacyGeneratedContractSource(),
                legacyGeneratedImplementationSource());
        assertTrue(legacyApi.succeeded,
                diagnosticsText(legacyApi.diagnostics));

        Compilation sourceCompatibleDecorator = compilePlain(
                "source-compatible-decorator",
                Arrays.asList(currentApi.classes, coreJar),
                sourceCompatibleDecoratorSource());
        assertTrue(sourceCompatibleDecorator.succeeded,
                diagnosticsText(sourceCompatibleDecorator.diagnostics));

        Compilation precompiledImplementation = compilePlain(
                "precompiled-implementation",
                Collections.singletonList(legacyApi.classes),
                precompiledImplementationSource());
        assertTrue(precompiledImplementation.succeeded,
                diagnosticsText(precompiledImplementation.diagnostics));

        Compilation precompiledConsumer = compilePlain(
                "precompiled-consumer",
                Collections.singletonList(legacyApi.classes),
                precompiledConsumerSource());
        assertTrue(precompiledConsumer.succeeded,
                diagnosticsText(precompiledConsumer.diagnostics));

        Path consumerClass = precompiledConsumer.classes.resolve(
                "compatibility/v12/PrecompiledV12Consumer.class");
        assertTrue(Files.isRegularFile(consumerClass));
        assertEquals(52, classFileMajorVersion(consumerClass),
                "The compatibility consumer must be Java 8 bytecode");
        assertFalse(Files.exists(precompiledConsumer.classes.resolve(
                "compatibility/v12/"
                        + "BinaryCompatibilityProjectionStore.class")),
                "The runtime must not accidentally use the legacy contract");

        URL[] runtimeClassPath = new URL[] {
            precompiledConsumer.classes.toUri().toURL(),
            precompiledImplementation.classes.toUri().toURL(),
            currentApi.classes.toUri().toURL(),
            coreJar.toUri().toURL()
        };
        try (URLClassLoader loader = new URLClassLoader(
                runtimeClassPath, null)) {
            Class<?> consumer = Class.forName(CONSUMER_NAME, true, loader);
            assertEquals("binary-compatible-ok",
                    invokeStatic(consumer.getMethod("run")));

            Class<?> contract = Class.forName(CONTRACT_NAME, false, loader);
            Class<?> implementation = Class.forName(
                    IMPLEMENTATION_NAME, false, loader);
            assertLoadedFrom(currentApi.classes, contract);
            assertLoadedFrom(currentApi.classes, implementation);
            assertNotNull(contract.getMethod("create", int.class));
            Method columnAppender = contract.getMethod("columnAppender");
            assertTrue(columnAppender.isDefault());

            Class<?> oldImplementation = Class.forName(
                    PRECOMPILED_IMPLEMENTATION_NAME, true, loader);
            Object oldStore = oldImplementation
                    .getConstructor(int.class)
                    .newInstance(Integer.valueOf(0));
            assertEquals(0, oldImplementation.getMethod("size")
                    .invoke(oldStore));
            InvocationTargetException unsupported = assertThrows(
                    InvocationTargetException.class,
                    () -> columnAppender.invoke(oldStore));
            assertTrue(unsupported.getCause()
                    instanceof UnsupportedOperationException,
                    String.valueOf(unsupported.getCause()));
            assertEquals(
                    "Per-column filling is not supported by this implementation",
                    unsupported.getCause().getMessage());
            assertCommonRuntimeSurfaceUnchanged(loader);
        }
    }

    private Compilation compileGeneratedApi(
            Path coreJar, Path processorJar) throws Exception {
        Path directory = Files.createDirectories(
                temporaryDirectory.resolve("current-generated-api"));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        Path generatedSources = Files.createDirectories(
                directory.resolve("generated-sources"));
        List<String> options = new ArrayList<String>(Arrays.asList(
                "-classpath", coreJar.toString(),
                "-processorpath", processorJar.toString(),
                "-processor", PROCESSOR_CLASS,
                "-d", classes.toString(),
                "-s", generatedSources.toString(),
                "-source", "8",
                "-target", "8",
                "-Xlint:-options"));
        return compile(
                classes,
                options,
                Collections.singletonList(currentProjectionSource()));
    }

    private Compilation compilePlain(
            String directoryName,
            List<Path> classPath,
            StringSource... sources) throws Exception {
        Path directory = Files.createDirectories(
                temporaryDirectory.resolve(directoryName));
        Path classes = Files.createDirectories(directory.resolve("classes"));
        List<String> options = new ArrayList<String>(Arrays.asList(
                "-proc:none",
                "-classpath", joinClassPath(classPath),
                "-d", classes.toString(),
                "-source", "8",
                "-target", "8",
                "-Xlint:-options"));
        return compile(classes, options, Arrays.asList(sources));
    }

    private static Compilation compile(
            Path classes,
            List<String> options,
            List<StringSource> sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Integration tests require a JDK with javac");
        DiagnosticCollector<JavaFileObject> diagnostics =
                new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        boolean succeeded;
        try {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    options,
                    null,
                    new ArrayList<JavaFileObject>(sources));
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }
        return new Compilation(
                succeeded, classes, diagnostics.getDiagnostics());
    }

    private static String joinClassPath(List<Path> entries) {
        StringBuilder classPath = new StringBuilder();
        for (Path entry : entries) {
            if (classPath.length() != 0) {
                classPath.append(File.pathSeparatorChar);
            }
            classPath.append(entry);
        }
        return classPath.toString();
    }

    private static void assertLoadedFrom(
            Path expectedLocation, Class<?> type) throws Exception {
        Path actualLocation = Paths.get(type.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toRealPath();
        assertEquals(expectedLocation.toRealPath(), actualLocation,
                type.getName() + " must come from current generated output");
    }

    private static void assertCommonRuntimeSurfaceUnchanged(
            ClassLoader loader) throws Exception {
        Class<?> cursor = Class.forName(
                "io.github.jutil.columnarprojection.ProjectionCursor",
                false,
                loader);
        Class<?> store = Class.forName(
                "io.github.jutil.columnarprojection.ProjectionStore",
                false,
                loader);

        assertEquals(3, cursor.getDeclaredMethods().length);
        assertEquals(boolean.class,
                cursor.getMethod("moveNext").getReturnType());
        assertEquals(Object.class,
                cursor.getMethod("current").getReturnType());
        assertEquals(void.class,
                cursor.getMethod("rewind").getReturnType());

        assertEquals(5, store.getDeclaredMethods().length);
        assertEquals(void.class,
                store.getMethod("add", Object.class).getReturnType());
        assertEquals(int.class, store.getMethod("size").getReturnType());
        assertEquals(void.class, store.getMethod("seal").getReturnType());
        assertEquals(cursor, store.getMethod("cursor").getReturnType());
        assertEquals(Object.class,
                store.getMethod("viewAt", int.class).getReturnType());
    }

    private static Object invokeStatic(Method method) throws Exception {
        try {
            return method.invoke(null);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw failure;
        }
    }

    private static StringSource currentProjectionSource() {
        return new StringSource(
                PROJECTION_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "import io.github.jutil.columnarprojection."
                        + "ProjectionSchema;\n"
                        + "@ProjectionSchema\n"
                        + "public interface BinaryCompatibilityProjection {\n"
                        + "    long identifier();\n"
                        + "    String symbol();\n"
                        + "}\n");
    }

    private static StringSource legacyProjectionCursorSource() {
        return new StringSource(
                "io.github.jutil.columnarprojection.ProjectionCursor",
                "package io.github.jutil.columnarprojection;\n"
                        + "public interface ProjectionCursor<T> {\n"
                        + "    boolean moveNext();\n"
                        + "    T current();\n"
                        + "    void rewind();\n"
                        + "}\n");
    }

    private static StringSource legacyProjectionStoreSource() {
        return new StringSource(
                "io.github.jutil.columnarprojection.ProjectionStore",
                "package io.github.jutil.columnarprojection;\n"
                        + "public interface ProjectionStore<T> {\n"
                        + "    void add(T projection);\n"
                        + "    int size();\n"
                        + "    void seal();\n"
                        + "    ProjectionCursor<T> cursor();\n"
                        + "    T viewAt(int index);\n"
                        + "}\n");
    }

    private static StringSource legacyProjectionSource() {
        return new StringSource(
                PROJECTION_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "public interface BinaryCompatibilityProjection {\n"
                        + "    long identifier();\n"
                        + "    String symbol();\n"
                        + "}\n");
    }

    private static StringSource legacyGeneratedContractSource() {
        return new StringSource(
                CONTRACT_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "public interface BinaryCompatibilityProjectionStore\n"
                        + "        extends io.github.jutil.columnarprojection."
                        + "ProjectionStore<BinaryCompatibilityProjection> {\n"
                        + "    static BinaryCompatibilityProjectionStore "
                        + "create(int expectedSize) {\n"
                        + "        return new "
                        + "BinaryCompatibilityProjection__ColumnarProjectionStore("
                        + "expectedSize);\n"
                        + "    }\n"
                        + "    Batch batch();\n"
                        + "    Batch batch(int sourceFromIndex, "
                        + "int sourceToIndex);\n"
                        + "    interface Batch {\n"
                        + "        Batch identifier(long[] source);\n"
                        + "        Batch symbol(String[] source);\n"
                        + "        void append();\n"
                        + "    }\n"
                        + "}\n");
    }

    private static StringSource legacyGeneratedImplementationSource() {
        return new StringSource(
                IMPLEMENTATION_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "public final class "
                        + "BinaryCompatibilityProjection__ColumnarProjectionStore\n"
                        + "        implements "
                        + "BinaryCompatibilityProjectionStore {\n"
                        + "    public "
                        + "BinaryCompatibilityProjection__ColumnarProjectionStore("
                        + "int expectedSize) { }\n"
                        + "    public Batch batch() { return null; }\n"
                        + "    public Batch batch(int from, int to) { "
                        + "return null; }\n"
                        + "    public void add(BinaryCompatibilityProjection "
                        + "projection) { }\n"
                        + "    public int size() { return 0; }\n"
                        + "    public void seal() { }\n"
                        + "    public io.github.jutil.columnarprojection."
                        + "ProjectionCursor<BinaryCompatibilityProjection> "
                        + "cursor() { return null; }\n"
                        + "    public BinaryCompatibilityProjection "
                        + "viewAt(int index) { return null; }\n"
                        + "}\n");
    }

    private static StringSource precompiledConsumerSource() {
        return new StringSource(
                CONSUMER_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "public final class PrecompiledV12Consumer {\n"
                        + "    private PrecompiledV12Consumer() { }\n"
                        + "    public static String run() {\n"
                        + "        BinaryCompatibilityProjectionStore store =\n"
                        + "                BinaryCompatibilityProjectionStore."
                        + "create(1);\n"
                        + "        store.batch()\n"
                        + "                .identifier(new long[]{10L, 20L})\n"
                        + "                .symbol(new String[]{\"A\", \"B\"})\n"
                        + "                .append();\n"
                        + "        store.batch(1, 3)\n"
                        + "                .identifier(new long[]{0L, 30L, "
                        + "40L})\n"
                        + "                .symbol(new String[]{\"ignored\", "
                        + "\"C\", \"D\"})\n"
                        + "                .append();\n"
                        + "        if (store.size() != 4) {\n"
                        + "            throw new AssertionError(\"size\");\n"
                        + "        }\n"
                        + "        store.seal();\n"
                        + "        if (store.viewAt(0).identifier() != 10L\n"
                        + "                || !\"A\".equals("
                        + "store.viewAt(0).symbol())\n"
                        + "                || store.viewAt(3).identifier() "
                        + "!= 40L\n"
                        + "                || !\"D\".equals("
                        + "store.viewAt(3).symbol())) {\n"
                        + "            throw new AssertionError(\"rows\");\n"
                        + "        }\n"
                        + "        return \"binary-compatible-ok\";\n"
                        + "    }\n"
                        + "}\n");
    }

    private static StringSource sourceCompatibleDecoratorSource() {
        return new StringSource(
                PACKAGE_NAME + ".SourceCompatibleV12Decorator",
                "package " + PACKAGE_NAME + ";\n"
                        + "public final class SourceCompatibleV12Decorator\n"
                        + "        implements "
                        + "BinaryCompatibilityProjectionStore {\n"
                        + "    private final "
                        + "BinaryCompatibilityProjectionStore delegate;\n"
                        + "    public SourceCompatibleV12Decorator(\n"
                        + "            BinaryCompatibilityProjectionStore "
                        + "delegate) {\n"
                        + "        this.delegate = delegate;\n"
                        + "    }\n"
                        + "    public Batch batch() { return delegate.batch(); }\n"
                        + "    public Batch batch(int from, int to) {\n"
                        + "        return delegate.batch(from, to);\n"
                        + "    }\n"
                        + "    public void add(BinaryCompatibilityProjection "
                        + "projection) { delegate.add(projection); }\n"
                        + "    public int size() { return delegate.size(); }\n"
                        + "    public void seal() { delegate.seal(); }\n"
                        + "    public io.github.jutil.columnarprojection."
                        + "ProjectionCursor<BinaryCompatibilityProjection> "
                        + "cursor() { return delegate.cursor(); }\n"
                        + "    public BinaryCompatibilityProjection "
                        + "viewAt(int index) { return delegate.viewAt(index); }\n"
                        + "}\n");
    }

    private static StringSource precompiledImplementationSource() {
        return new StringSource(
                PRECOMPILED_IMPLEMENTATION_NAME,
                "package " + PACKAGE_NAME + ";\n"
                        + "public final class PrecompiledV12Implementation\n"
                        + "        implements "
                        + "BinaryCompatibilityProjectionStore {\n"
                        + "    public PrecompiledV12Implementation("
                        + "int expectedSize) { }\n"
                        + "    public Batch batch() { return null; }\n"
                        + "    public Batch batch(int from, int to) { "
                        + "return null; }\n"
                        + "    public void add(BinaryCompatibilityProjection "
                        + "projection) { }\n"
                        + "    public int size() { return 0; }\n"
                        + "    public void seal() { }\n"
                        + "    public io.github.jutil.columnarprojection."
                        + "ProjectionCursor<BinaryCompatibilityProjection> "
                        + "cursor() { return null; }\n"
                        + "    public BinaryCompatibilityProjection "
                        + "viewAt(int index) { return null; }\n"
                        + "}\n");
    }

    private static Path configuredJar(String propertyName) {
        String value = System.getProperty(propertyName);
        assertNotNull(value, propertyName + " was not configured");
        Path jar = Paths.get(value);
        assertTrue(Files.isRegularFile(jar), "Expected built JAR " + jar);
        return jar;
    }

    private static int classFileMajorVersion(Path classFile)
            throws Exception {
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

    private static final class Compilation {
        private final boolean succeeded;
        private final Path classes;
        private final List<Diagnostic<? extends JavaFileObject>> diagnostics;

        private Compilation(
                boolean succeeded,
                Path classes,
                List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            this.succeeded = succeeded;
            this.classes = classes;
            this.diagnostics = diagnostics;
        }
    }
}
