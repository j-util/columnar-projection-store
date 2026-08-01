package io.github.jutil.columnarprojection.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
        Path classes = Files.createDirectories(
                temporaryDirectory.resolve("classes"));
        Path generatedSources = Files.createDirectories(
                temporaryDirectory.resolve("generated-sources"));

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
                            "consumer.ConsumerProjection",
                            "package consumer;\n"
                                    + "import io.github.jutil."
                                    + "columnarprojection.ProjectionSchema;\n"
                                    + "@ProjectionSchema\n"
                                    + "public interface ConsumerProjection {\n"
                                    + "    long identifier();\n"
                                    + "}\n")));
            succeeded = Boolean.TRUE.equals(task.call());
        } finally {
            fileManager.close();
        }

        assertTrue(succeeded, diagnosticsText(diagnostics.getDiagnostics()));
        Path generatedSource = generatedSources.resolve(
                "consumer/ConsumerProjection__ColumnarProjectionStore.java");
        Path generatedClass = classes.resolve(
                "consumer/ConsumerProjection__ColumnarProjectionStore.class");
        assertTrue(Files.isRegularFile(generatedSource));
        assertTrue(Files.isRegularFile(generatedClass));
        assertEquals(52, classFileMajorVersion(generatedClass),
                "Generated source must compile to Java 8 bytecode");
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
}
