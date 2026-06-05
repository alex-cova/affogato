package dev.affogato.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AffogatoCompilerOptions {
    private final List<Path> sourceRoots;
    private final Path outputDirectory;
    private final List<Path> classpath;
    private final boolean failOnWarnings;
    private final int javaRelease;
    private final Path javaMetadataCacheDirectory;

    private AffogatoCompilerOptions(Builder builder) {
        this.sourceRoots = List.copyOf(builder.sourceRoots);
        this.outputDirectory = Objects.requireNonNull(builder.outputDirectory, "outputDirectory");
        this.classpath = List.copyOf(builder.classpath);
        this.failOnWarnings = builder.failOnWarnings;
        this.javaRelease = builder.javaRelease;
        this.javaMetadataCacheDirectory = builder.javaMetadataCacheDirectory;
    }

    public List<Path> sourceRoots() {
        return sourceRoots;
    }

    public Path outputDirectory() {
        return outputDirectory;
    }

    public List<Path> classpath() {
        return classpath;
    }

    public boolean failOnWarnings() {
        return failOnWarnings;
    }

    public int javaRelease() {
        return javaRelease;
    }

    public Path javaMetadataCacheDirectory() {
        return javaMetadataCacheDirectory;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<Path> sourceRoots = new ArrayList<>();
        private final List<Path> classpath = new ArrayList<>();
        private Path outputDirectory;
        private Path javaMetadataCacheDirectory;
        private boolean failOnWarnings;
        private int javaRelease = 21;

        public Builder addSourceRoot(Path sourceRoot) {
            this.sourceRoots.add(Objects.requireNonNull(sourceRoot, "sourceRoot"));
            return this;
        }

        public Builder addClasspathEntry(Path classpathEntry) {
            this.classpath.add(Objects.requireNonNull(classpathEntry, "classpathEntry"));
            return this;
        }

        public Builder outputDirectory(Path outputDirectory) {
            this.outputDirectory = Objects.requireNonNull(outputDirectory, "outputDirectory");
            return this;
        }

        public Builder failOnWarnings(boolean failOnWarnings) {
            this.failOnWarnings = failOnWarnings;
            return this;
        }

        public Builder javaRelease(int javaRelease) {
            this.javaRelease = javaRelease;
            return this;
        }

        public Builder javaMetadataCacheDirectory(Path javaMetadataCacheDirectory) {
            this.javaMetadataCacheDirectory = Objects.requireNonNull(javaMetadataCacheDirectory, "javaMetadataCacheDirectory");
            return this;
        }

        public AffogatoCompilerOptions build() {
            if (sourceRoots.isEmpty()) {
                throw new IllegalStateException("At least one source root is required.");
            }
            return new AffogatoCompilerOptions(this);
        }
    }
}
