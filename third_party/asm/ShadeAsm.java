import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Usage: java -cp asm-9.8.jar;asm-commons-9.8.jar;asm-tree-9.8.jar ShadeAsm.java <out.jar> <in.jar>... */
public class ShadeAsm {
    static final String FROM = "org/objectweb/asm/";
    static final String TO = "com/pzoptimizer/shaded/asm/";

    public static void main(String[] args) throws Exception {
        Remapper remapper = new Remapper() {
            @Override
            public String map(String name) {
                return name.startsWith(FROM) ? TO + name.substring(FROM.length()) : name;
            }
        };
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[0]))) {
            for (int i = 1; i < args.length; i++) {
                try (ZipFile in = new ZipFile(args[i])) {
                    for (ZipEntry e : java.util.Collections.list(in.entries())) {
                        String n = e.getName();
                        if (!n.endsWith(".class") || !n.startsWith(FROM)) continue;
                        byte[] b = in.getInputStream(e).readAllBytes();
                        ClassReader cr = new ClassReader(b);
                        ClassWriter cw = new ClassWriter(0);
                        cr.accept(new ClassRemapper(cw, remapper), 0);
                        out.putNextEntry(new ZipEntry(TO + n.substring(FROM.length())));
                        out.write(cw.toByteArray());
                        out.closeEntry();
                    }
                }
            }
        }
    }
}
