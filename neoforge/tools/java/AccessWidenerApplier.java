// SPDX-License-Identifier: Apache-2.0
//
// Applies a Fabric access widener (v1/v2, any namespace matching the jar) to a
// jar of classes, so javac sees the widened access when compiling the mod.
// Runtime widening on NeoForge is done by the generated access transformer.
//
// Usage: java -cp asm.jar AccessWidenerApplier.java <in.jar> <widener> <out.jar>

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

public class AccessWidenerApplier {
    static final Set<String> classAccessible = new HashSet<>();
    static final Set<String> classExtendable = new HashSet<>();
    static final Map<String, Set<String>> members = new HashMap<>(); // owner -> "kind name desc"

    public static void main(String[] args) throws IOException {
        for (String line : Files.readAllLines(Path.of(args[1]))) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            String[] p = line.trim().split("\\s+");
            if (p.length < 3 || p[0].equals("accessWidener")) continue;
            String access = p[0].replace("transitive-", "");
            switch (p[1]) {
                case "class" -> {
                    if (access.equals("extendable")) classExtendable.add(p[2]);
                    else classAccessible.add(p[2]);
                }
                case "method", "field" ->
                    members.computeIfAbsent(p[2], k -> new HashSet<>())
                           .add(access + " " + p[1] + " " + p[3] + " " + p[4]);
                default -> throw new IllegalArgumentException(line);
            }
        }
        // Widening a member requires its owner (and outers) to be reachable too.
        for (String owner : new ArrayList<>(members.keySet())) classAccessible.add(owner);
        for (String c : new ArrayList<>(classAccessible)) {
            String o = c;
            while (o.contains("$")) { o = o.substring(0, o.lastIndexOf('$')); classAccessible.add(o); }
        }

        int changed = 0;
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(args[0])));
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(args[2])))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                byte[] data = in.readAllBytes();
                if (e.getName().endsWith(".class")) {
                    byte[] w = widen(data);
                    if (w != data) changed++;
                    data = w;
                }
                out.putNextEntry(new ZipEntry(e.getName()));
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("access widener: " + changed + " classes changed");
    }

    static int pub(int acc) {
        return (acc & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }

    static int classAccess(String name, int acc) {
        if (classAccessible.contains(name) || classExtendable.contains(name)) acc = pub(acc);
        if (classExtendable.contains(name)) acc &= ~Opcodes.ACC_FINAL;
        return acc;
    }

    static byte[] widen(byte[] data) {
        ClassReader r = new ClassReader(data);
        String name = r.getClassName();
        boolean touchesInner = false;
        // Cheap pre-check: does this class mention anything we widen?
        Set<String> rules = members.get(name);
        if (rules == null && !classAccessible.contains(name) && !classExtendable.contains(name)) {
            boolean any = false;
            for (String c : classAccessible) if (c.startsWith(name + "$")) { any = true; break; }
            for (String c : classExtendable) if (c.startsWith(name + "$")) { any = true; break; }
            if (!any) return data;
        }
        ClassWriter w = new ClassWriter(0);
        r.accept(new ClassVisitor(Opcodes.ASM9, w) {
            @Override public void visit(int v, int acc, String n, String sig, String sup, String[] itf) {
                super.visit(v, classAccess(n, acc), n, sig, sup, itf);
            }
            @Override public void visitInnerClass(String n, String outer, String inner, int acc) {
                super.visitInnerClass(n, outer, inner, classAccess(n, acc));
            }
            @Override public FieldVisitor visitField(int acc, String n, String desc, String sig, Object val) {
                if (rules != null) {
                    if (rules.contains("accessible field " + n + " " + desc)) acc = pub(acc);
                    if (rules.contains("mutable field " + n + " " + desc)) acc &= ~Opcodes.ACC_FINAL;
                    if (rules.contains("extendable field " + n + " " + desc)) acc = pub(acc);
                }
                return super.visitField(acc, n, desc, sig, val);
            }
            @Override public MethodVisitor visitMethod(int acc, String n, String desc, String sig, String[] ex) {
                if (rules != null) {
                    if (rules.contains("accessible method " + n + " " + desc)) acc = pub(acc);
                    if (rules.contains("extendable method " + n + " " + desc)) acc = pub(acc) & ~Opcodes.ACC_FINAL;
                }
                return super.visitMethod(acc, n, desc, sig, ex);
            }
        }, 0);
        return w.toByteArray();
    }
}
