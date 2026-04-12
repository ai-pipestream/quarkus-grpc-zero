package io.quarkiverse.grpc.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos;

import io.quarkus.bootstrap.prebuild.CodeGenException;

class ProtoTypeConflictDetectorTest {

    private static DescriptorProtos.FileDescriptorSet buildDescriptorSet(String pkg, String messageName,
            DescriptorProtos.FieldDescriptorProto.Type fieldType) {
        return DescriptorProtos.FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("test.proto")
                        .setPackage(pkg)
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName(messageName)
                                .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                        .setName("value")
                                        .setNumber(1)
                                        .setType(fieldType)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    @Test
    void identicalTypesAreTreatedAsDuplicates() throws Exception {
        ProtoTypeConflictDetector detector = new ProtoTypeConflictDetector();

        var set = buildDescriptorSet("demo", "Foo", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING);

        boolean firstResult = detector.checkAndRecord(set, "source-a.proto");
        boolean secondResult = detector.checkAndRecord(set, "source-b.proto");

        assertTrue(!firstResult, "First encounter should not be a duplicate");
        assertTrue(secondResult, "Second encounter with identical bytes should be a duplicate");
    }

    @Test
    void conflictingTypesThrowCodeGenException() {
        ProtoTypeConflictDetector detector = new ProtoTypeConflictDetector();

        var stringVersion = buildDescriptorSet("demo", "Foo", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING);
        var intVersion = buildDescriptorSet("demo", "Foo", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32);

        assertDoesNotThrow(() -> detector.checkAndRecord(stringVersion, "first.proto"));

        CodeGenException ex = assertThrows(CodeGenException.class,
                () -> detector.checkAndRecord(intVersion, "second.proto"));

        assertTrue(ex.getMessage().contains(".demo.Foo"),
                "Should name the conflicting FQN, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("first.proto"),
                "Should name the first source, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("second.proto"),
                "Should name the second source, got: " + ex.getMessage());
    }

    @Test
    void differentFqnsDoNotConflict() throws Exception {
        ProtoTypeConflictDetector detector = new ProtoTypeConflictDetector();

        var fooSet = buildDescriptorSet("pkg.a", "Foo", DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING);
        var barSet = buildDescriptorSet("pkg.b", "Bar", DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32);

        boolean first = detector.checkAndRecord(fooSet, "a.proto");
        boolean second = detector.checkAndRecord(barSet, "b.proto");

        assertTrue(!first, "First type should not be a duplicate");
        assertTrue(!second, "Different FQN should not be a duplicate");
    }

    @Test
    void nestedTypesAreChecked() {
        ProtoTypeConflictDetector detector = new ProtoTypeConflictDetector();

        DescriptorProtos.FileDescriptorSet setA = DescriptorProtos.FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("a.proto")
                        .setPackage("outer")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("Parent")
                                .addNestedType(DescriptorProtos.DescriptorProto.newBuilder()
                                        .setName("Child")
                                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                                .setName("x").setNumber(1)
                                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        DescriptorProtos.FileDescriptorSet setB = DescriptorProtos.FileDescriptorSet.newBuilder()
                .addFile(DescriptorProtos.FileDescriptorProto.newBuilder()
                        .setName("b.proto")
                        .setPackage("outer")
                        .addMessageType(DescriptorProtos.DescriptorProto.newBuilder()
                                .setName("Parent")
                                .addNestedType(DescriptorProtos.DescriptorProto.newBuilder()
                                        .setName("Child")
                                        .addField(DescriptorProtos.FieldDescriptorProto.newBuilder()
                                                .setName("x").setNumber(1)
                                                .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_INT32)
                                                .build())
                                        .build())
                                .build())
                        .build())
                .build();

        assertDoesNotThrow(() -> detector.checkAndRecord(setA, "a.proto"));
        CodeGenException ex = assertThrows(CodeGenException.class,
                () -> detector.checkAndRecord(setB, "b.proto"));

        assertTrue(ex.getMessage().contains(".outer.Parent"),
                "Should identify the conflict at the parent level (which includes nested types), got: "
                        + ex.getMessage());
    }
}
