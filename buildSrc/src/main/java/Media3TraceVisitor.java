import com.android.build.api.instrumentation.AsmClassVisitorFactory;
import com.android.build.api.instrumentation.ClassContext;
import com.android.build.api.instrumentation.ClassData;
import com.android.build.api.instrumentation.InstrumentationParameters;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public abstract class Media3TraceVisitor implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    static final String TARGET = "androidx.media3.datasource.DefaultHttpDataSource";
    static final String HOOK = "de/danoeh/antennapod/Media3HttpTrace";
    static final String CONNECTION = "java/net/HttpURLConnection";
    static final String SHA256 = "046e9eaa9b6924eea1d0c635514c2999e1f8c7f2daf9cac01723a995512dc990";

    @Override
    public boolean isInstrumentable(ClassData data) {
        return TARGET.equals(data.getClassName());
    }

    @Override
    public ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return instrument(next);
    }

    static ClassVisitor instrument(ClassVisitor next) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            int connects;
            int responses;

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean iface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && CONNECTION.equals(owner)) {
                            if (method.equals("connect") && desc.equals("()V")) {
                                connects++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "connect",
                                        "(Ljava/net/HttpURLConnection;)V", false);
                                return;
                            }
                            if (method.equals("getResponseCode") && desc.equals("()I")) {
                                responses++;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "responseCode",
                                        "(Ljava/net/HttpURLConnection;)I", false);
                                return;
                            }
                        }
                        super.visitMethodInsn(opcode, owner, method, desc, iface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (connects != 2 || responses != 2) {
                    throw new IllegalStateException("Unexpected Media3 connection call sites: "
                            + connects + "/" + responses);
                }
                super.visitEnd();
            }
        };
    }

    static byte[] readClass(File archive) throws IOException {
        try (ZipFile aar = new ZipFile(archive);
                InputStream stream = aar.getInputStream(aar.getEntry("classes.jar"));
                ZipInputStream jar = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = jar.getNextEntry()) != null) {
                if (entry.getName().equals(TARGET.replace('.', '/') + ".class")) {
                    return jar.readAllBytes();
                }
            }
        }
        throw new IOException("Pinned Media3 class is missing");
    }

    public static void verifyArchive(File archive) throws Exception {
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(readClass(archive)));
        if (!SHA256.equals(actual)) {
            throw new IllegalStateException("Media3 diagnostic instrumentation requires the audited 1.10.0 class");
        }
    }
}
