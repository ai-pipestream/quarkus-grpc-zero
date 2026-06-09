package io.quarkiverse.grpc.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Unit tests for the codegen input-hash cache key ({@link GrpcZeroCodeGen#computeInputHash}).
 * A bug here means either stale output (never invalidates) or no caching (never hits), so the
 * key must be stable for identical inputs and change for any output-affecting input.
 */
class GrpcZeroCodeGenCacheTest {

    @TempDir
    Path tempDir;

    private final GrpcZeroCodeGen codegen = new GrpcZeroCodeGen();

    private static final String PROTO_A = "syntax = \"proto3\";\npackage p.a;\nmessage A { string x = 1; }\n";

    private Path writeProto(String filename, String content) throws Exception {
        Path dir = tempDir.resolve("protos");
        Files.createDirectories(dir);
        Path p = dir.resolve(filename);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p.toAbsolutePath();
    }

    private static Config config(String... keyValues) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            builder.withDefaultValue(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    @Test
    void identicalInputsProduceIdenticalHash() throws Exception {
        List<String> protos = List.of(writeProto("a.proto", PROTO_A).toString());
        assertEquals(
                codegen.computeInputHash(protos, List.of(), config()),
                codegen.computeInputHash(protos, List.of(), config()),
                "Identical protos + config must hash identically, otherwise the cache could never hit");
    }

    @Test
    void editingProtoContentChangesHash() throws Exception {
        Path a = writeProto("a.proto", PROTO_A);
        String before = codegen.computeInputHash(List.of(a.toString()), List.of(), config());

        Files.writeString(a, PROTO_A.replace("string x = 1;", "string x = 1;\n  int32 y = 2;"), StandardCharsets.UTF_8);
        String after = codegen.computeInputHash(List.of(a.toString()), List.of(), config());

        assertNotEquals(before, after, "Editing a proto's content must invalidate the cache");
    }

    @Test
    void addingProtoChangesHash() throws Exception {
        Path a = writeProto("a.proto", PROTO_A);
        String oneFile = codegen.computeInputHash(List.of(a.toString()), List.of(), config());

        Path b = writeProto("b.proto", "syntax = \"proto3\";\npackage p.b;\nmessage B { string y = 1; }\n");
        String twoFiles = codegen.computeInputHash(List.of(a.toString(), b.toString()), List.of(), config());

        assertNotEquals(oneFile, twoFiles, "Adding a proto must invalidate the cache");
    }

    @Test
    void changingOutputAffectingConfigChangesHash() throws Exception {
        List<String> protos = List.of(writeProto("a.proto", PROTO_A).toString());
        String defaults = codegen.computeInputHash(protos, List.of(), config());
        String kotlinEnabled = codegen.computeInputHash(protos, List.of(),
                config("quarkus.generate-code.grpc.kotlin.generate", "true"));

        assertNotEquals(defaults, kotlinEnabled, "An output-affecting config change must invalidate the cache");
    }
}
