import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;

public class Media3TraceVisitorTest {
    @Test
    public void onlyFourDelegatingCallSitesChangeInPinnedDependency() throws Exception {
        File archive = new File(System.getProperty("media3.fixture"));
        Media3TraceVisitor.verifyArchive(archive);
        byte[] original = Media3TraceVisitor.readClass(archive);
        ClassWriter normalized = new ClassWriter(0);
        new ClassReader(original).accept(normalized, 0);
        ClassWriter instrumented = new ClassWriter(0);
        new ClassReader(original).accept(Media3TraceVisitor.instrument(instrumented), 0);
        ClassWriter reversed = new ClassWriter(0);
        new ClassReader(instrumented.toByteArray()).accept(new ClassVisitor(Opcodes.ASM9, reversed) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                        if (Media3TraceVisitor.HOOK.equals(owner)) {
                            boolean connect = method.equals("connect");
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, Media3TraceVisitor.CONNECTION,
                                    connect ? "connect" : "getResponseCode", connect ? "()V" : "()I", false);
                        } else {
                            super.visitMethodInsn(opcode, owner, method, desc, iface);
                        }
                    }
                };
            }
        }, 0);
        assertArrayEquals(normalized.toByteArray(), reversed.toByteArray());
    }

    @Test(expected = IllegalStateException.class)
    public void changedCallShapeFailsInsteadOfSilentlyDroppingCoverage() {
        Media3TraceVisitor.instrument(new ClassWriter(0)).visitEnd();
    }
}
