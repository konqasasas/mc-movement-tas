package io.github.konqasasas.mctas.physics;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which build of the engine produced a result: a hash of every source file that decides what a list of
 * inputs does, so a solution found by one build and replayed or judged by another is caught instead of
 * silently drifting. It covers the {@code physics} sources and tables (not the {@code testing} package)
 * and the {@code problem} sources (not the {@code cli} and {@code baseline} packages): the physics, the
 * input encoding, the judge and the file formats.
 *
 * <p>The id is written to {@link #RESOURCE} by {@code ./gradlew :problem:writeEngineBuild}, and a test
 * fails while it is out of date, so every change to those sources comes with a new id.
 */
public final class EngineBuild {

    static final String RESOURCE = "/io/github/konqasasas/mctas/physics/engine-build.txt";

    /** The id of this build, or {@code "unknown"} when the resource is missing. */
    public static final String ID = load();

    private EngineBuild() {
    }

    private static String load() {
        InputStream in = EngineBuild.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return "unknown";
        }
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        return line.trim();
                    }
                }
                return "unknown";
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * The id of the sources under {@code repoRoot}: the first 16 hex digits of a SHA-256 over each file's
     * path from the root and its contents, in path order, with line endings made {@code \n}.
     */
    public static String compute(Path repoRoot) throws IOException {
        List<Path> files = new ArrayList<Path>();
        collect(repoRoot, repoRoot.resolve("physics/src/main"), files);
        collect(repoRoot, repoRoot.resolve("problem/src/main"), files);
        List<String> names = new ArrayList<String>();
        for (Path p : files) {
            names.add(repoRoot.relativize(p).toString().replace('\\', '/'));
        }
        Collections.sort(names);
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String name : names) {
            md.update(name.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            String text = new String(Files.readAllBytes(repoRoot.resolve(name)), StandardCharsets.UTF_8);
            md.update(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        StringBuilder sb = new StringBuilder();
        byte[] d = md.digest();
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", d[i] & 0xFF));
        }
        return sb.toString();
    }

    private static void collect(final Path root, Path dir, final List<Path> out) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                String rel = root.relativize(d).toString().replace('\\', '/');
                if (rel.endsWith("/mctas/physics/testing") || rel.endsWith("/mctas/problem/cli")
                        || rel.endsWith("/mctas/problem/baseline")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                if (!f.toString().replace('\\', '/').endsWith(RESOURCE)) {
                    out.add(f);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Writes the id of the sources under {@code args[0]} to {@code args[1]}. */
    public static void main(String[] args) throws IOException {
        String id = compute(java.nio.file.Paths.get(args[0]).toAbsolutePath().normalize());
        Files.write(java.nio.file.Paths.get(args[1]),
                ("# Generated by ./gradlew :problem:writeEngineBuild; see EngineBuild.\n" + id + "\n").getBytes(StandardCharsets.UTF_8));
        System.out.println("engine build " + id);
    }
}
