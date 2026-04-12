package io.quarkiverse.grpc.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.compiler.PluginProtos;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.roastedroot.protobuf4j.v4.Protobuf;
import io.roastedroot.zerofs.Configuration;
import io.roastedroot.zerofs.ZeroFs;

class ProcessProtoFilesTest {

    @TempDir
    Path tempDir;

    private void writeProto(Path dir, String filename, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(filename), content, StandardCharsets.UTF_8);
    }

    @Test
    void differentProtosAreProcessed() throws Exception {
        // Two distinct proto files with different FQNs in the same directory
        Path protoDir = tempDir.resolve("protos");
        writeProto(protoDir, "a.proto",
                "syntax = \"proto3\";\npackage p.a;\nmessage A { string x = 1; }\n");
        writeProto(protoDir, "b.proto",
                "syntax = \"proto3\";\npackage p.b;\nmessage B { string y = 1; }\n");

        List<String> protoFiles = List.of(
                protoDir.resolve("a.proto").toAbsolutePath().toString(),
                protoDir.resolve("b.proto").toAbsolutePath().toString());
        Set<String> protoDirs = new LinkedHashSet<>(List.of(protoDir.toAbsolutePath().toString()));

        try (FileSystem fs = ZeroFs.newFileSystem(
                Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
            Path workdir = fs.getPath(".");
            GrpcZeroCodeGen.copyDirectory(protoDir, workdir);
            Protobuf protobuf = Protobuf.builder().withWorkdir(workdir).build();

            DescriptorProtos.FileDescriptorSet.Builder dsBuild = DescriptorProtos.FileDescriptorSet.newBuilder();
            PluginProtos.CodeGeneratorRequest.Builder reqBuild = PluginProtos.CodeGeneratorRequest.newBuilder();

            GrpcZeroCodeGen codegen = new GrpcZeroCodeGen();
            assertDoesNotThrow(() -> codegen.processProtoFiles(protoFiles, protoDirs, protobuf, dsBuild, reqBuild));

            assertEquals(2, reqBuild.getFileToGenerateCount());
        }
    }

    @Test
    void sameFqnDifferentShapeFailsFast() throws Exception {
        // Two proto files declaring the same FQN with different field types
        Path protoDir = tempDir.resolve("protos");
        writeProto(protoDir, "first.proto",
                "syntax = \"proto3\";\npackage demo;\nmessage Foo { string a = 1; }\n");
        writeProto(protoDir, "second.proto",
                "syntax = \"proto3\";\npackage demo;\nmessage Foo { int32 a = 1; }\n");

        List<String> protoFiles = List.of(
                protoDir.resolve("first.proto").toAbsolutePath().toString(),
                protoDir.resolve("second.proto").toAbsolutePath().toString());
        Set<String> protoDirs = new LinkedHashSet<>(List.of(protoDir.toAbsolutePath().toString()));

        try (FileSystem fs = ZeroFs.newFileSystem(
                Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
            Path workdir = fs.getPath(".");
            GrpcZeroCodeGen.copyDirectory(protoDir, workdir);
            Protobuf protobuf = Protobuf.builder().withWorkdir(workdir).build();

            DescriptorProtos.FileDescriptorSet.Builder dsBuild = DescriptorProtos.FileDescriptorSet.newBuilder();
            PluginProtos.CodeGeneratorRequest.Builder reqBuild = PluginProtos.CodeGeneratorRequest.newBuilder();

            GrpcZeroCodeGen codegen = new GrpcZeroCodeGen();
            CodeGenException ex = assertThrows(CodeGenException.class,
                    () -> codegen.processProtoFiles(protoFiles, protoDirs, protobuf, dsBuild, reqBuild));

            assertTrue(ex.getMessage().contains(".demo.Foo"),
                    "Should name the conflicting FQN, got: " + ex.getMessage());
        }
    }

    @Test
    void identicalDuplicateIsDeduped() throws Exception {
        // Same proto content contributed by two different source directories
        // (simulates the same dependency appearing twice)
        Path dirA = tempDir.resolve("src-a");
        Path dirB = tempDir.resolve("src-b");

        String content = "syntax = \"proto3\";\npackage demo;\nmessage Foo { string a = 1; }\n";
        writeProto(dirA, "demo.proto", content);
        writeProto(dirB, "demo.proto", content);

        List<String> protoFiles = List.of(
                dirA.resolve("demo.proto").toAbsolutePath().toString(),
                dirB.resolve("demo.proto").toAbsolutePath().toString());
        Set<String> protoDirs = new LinkedHashSet<>(List.of(
                dirA.toAbsolutePath().toString(),
                dirB.toAbsolutePath().toString()));

        try (FileSystem fs = ZeroFs.newFileSystem(
                Configuration.unix().toBuilder().setAttributeViews("unix").build())) {
            Path workdir = fs.getPath(".");
            for (String dir : protoDirs) {
                GrpcZeroCodeGen.copyDirectory(Path.of(dir), workdir);
            }
            Protobuf protobuf = Protobuf.builder().withWorkdir(workdir).build();

            DescriptorProtos.FileDescriptorSet.Builder dsBuild = DescriptorProtos.FileDescriptorSet.newBuilder();
            PluginProtos.CodeGeneratorRequest.Builder reqBuild = PluginProtos.CodeGeneratorRequest.newBuilder();

            GrpcZeroCodeGen codegen = new GrpcZeroCodeGen();
            assertDoesNotThrow(() -> codegen.processProtoFiles(protoFiles, protoDirs, protobuf, dsBuild, reqBuild));

            assertEquals(1, reqBuild.getFileToGenerateCount(),
                    "Identical duplicate should be deduped to a single file_to_generate entry");
        }
    }
}
