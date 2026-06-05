package dev.affogato.compiler;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class CompilationBenchmark {
    private Path sourceRoot;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        sourceRoot = Files.createTempDirectory("affogato-bench-src");
        for (int i = 0; i < 100; i++) {
            Path file = sourceRoot.resolve("Class" + i + ".aff");
            Files.writeString(file, """
                    package dev.affogato.bench
                    class Class%d {
                        value(): String { return "v%d" }
                    }
                    """.formatted(i, i));
        }
    }

    @Benchmark
    public AffogatoCompilationResult compile() throws Exception {
        Path out = Files.createTempDirectory("affogato-bench-out");
        AffogatoCompilerOptions opts = AffogatoCompilerOptions.builder()
                .addSourceRoot(sourceRoot)
                .outputDirectory(out)
                .build();
        return new AffogatoCompiler().compile(opts);
    }
}
