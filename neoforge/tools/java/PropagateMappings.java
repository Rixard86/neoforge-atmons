// SPDX-License-Identifier: Apache-2.0
//
// Completes a TSRG v1 mapping (left = names used in the jar) through the class
// hierarchy of that jar:
//
//  * an overriding method takes its overridden method's target name, because
//    an override that maps to a different name stops overriding;
//  * inherited, non-private members are listed under every subclass as well,
//    because javac and the Mixin annotation processor look members up by the
//    owner written in the source, which is often a subclass.
//
// Writes the forward (left -> right) and reverse (right -> left) files.
//
// Usage: java -cp asm.jar PropagateMappings.java <declared.tsrg> <jar> <forward.tsrg> <reverse.tsrg>

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;

public class PropagateMappings {
    record Info(String name, String sup, String[] itfs, List<String[]> methods, List<String[]> fields) {}
    // methods: {name, desc, access}; fields: {name, desc, access}

    static final Map<String, String> classes = new HashMap<>();
    static final Map<String, Map<String, String>> fieldMap = new HashMap<>();   // owner -> name -> target
    static final Map<String, Map<String, String>> methodMap = new HashMap<>();  // owner -> name+desc -> target
    static final Map<String, Info> infos = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // Optional fifth argument: Mojang-name proposals for members Yarn's
        // mapping files leave unnamed ("owner field" or "owner method(desc)").
        Set<String> proposals = new HashSet<>();
        if (args.length > 4) {
            for (String l : Files.readAllLines(Path.of(args[4]))) if (!l.isBlank()) proposals.add(l.trim());
        }
        String cur = null;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isEmpty()) continue;
            if (line.charAt(0) != '\t') {
                String[] p = line.split(" ");
                cur = p[0];
                classes.put(p[0], p[1]);
            } else {
                String[] p = line.trim().split(" ");
                if (p.length == 2) fieldMap.computeIfAbsent(cur, k -> new HashMap<>()).put(p[0], p[1]);
                else methodMap.computeIfAbsent(cur, k -> new HashMap<>()).put(p[0] + p[1], p[2]);
            }
        }
        try (ZipFile z = new ZipFile(args[1])) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) continue;
                ClassReader r = new ClassReader(z.getInputStream(e).readAllBytes());
                List<String[]> ms = new ArrayList<>(), fs = new ArrayList<>();
                r.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] x) {
                        ms.add(new String[]{n, d, Integer.toString(acc)}); return null;
                    }
                    @Override public FieldVisitor visitField(int acc, String n, String d, String s, Object v) {
                        fs.add(new String[]{n, d, Integer.toString(acc)}); return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                infos.put(r.getClassName(), new Info(r.getClassName(), r.getSuperName(), r.getInterfaces(), ms, fs));
            }
        }

        // 0. Apply proposals the way Yarn's build does: constants (static final
        //    fields) and record components take their natural names.
        int applied = 0;
        for (Info i : infos.values()) {
            Map<String, String> fm = fieldMap.get(i.name());
            if (fm != null) {
                for (String[] f : i.fields()) {
                    int acc = Integer.parseInt(f[2]);
                    boolean constant = (acc & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL);
                    boolean component = "java/lang/Record".equals(i.sup()) && (acc & Opcodes.ACC_STATIC) == 0;
                    if ((constant || component) && proposals.contains(i.name() + " " + f[0]) && fm.containsKey(f[0])) {
                        fm.put(f[0], f[0]);
                        applied++;
                    }
                }
            }
            Map<String, String> mm = methodMap.get(i.name());
            if (mm != null && "java/lang/Record".equals(i.sup())) {
                for (String[] m : i.methods()) {
                    String key = m[0] + m[1];
                    if (proposals.contains(i.name() + " " + key) && mm.containsKey(key)) {
                        mm.put(key, m[0]);
                        applied++;
                    }
                }
            }
        }
        System.out.println("applied " + applied + " name proposals");

        // 1. Overrides take the overridden method's name (resolved top-down).
        Set<String> done = new HashSet<>();
        for (String c : infos.keySet()) resolveOverrides(c, done);

        // 2. List inherited members under subclasses.
        Map<String, Map<String, String>> fullMethods = new HashMap<>();
        Map<String, Map<String, String>> fullFields = new HashMap<>();
        for (String c : infos.keySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            Map<String, String> f = new LinkedHashMap<>();
            collect(c, m, f, new HashSet<>(), true);
            fullMethods.put(c, m);
            fullFields.put(c, f);
        }

        int nm = 0, nf = 0;
        try (PrintWriter fwd = new PrintWriter(Files.newBufferedWriter(Path.of(args[2])));
             PrintWriter rev = new PrintWriter(Files.newBufferedWriter(Path.of(args[3])))) {
            TreeSet<String> all = new TreeSet<>(classes.keySet());
            all.addAll(infos.keySet());
            for (String c : all) {
                String tc = mapClass(c);
                fwd.println(c + " " + tc);
                rev.println(tc + " " + c);
                for (Map.Entry<String, String> e : fullFields.getOrDefault(c, Map.of()).entrySet()) {
                    fwd.println("\t" + e.getKey() + " " + e.getValue());
                    rev.println("\t" + e.getValue() + " " + e.getKey());
                    nf++;
                }
                for (Map.Entry<String, String> e : fullMethods.getOrDefault(c, Map.of()).entrySet()) {
                    String key = e.getKey();
                    int paren = key.indexOf('(');
                    String name = key.substring(0, paren), desc = key.substring(paren);
                    fwd.println("\t" + name + " " + desc + " " + e.getValue());
                    rev.println("\t" + e.getValue() + " " + mapDesc(desc) + " " + name);
                    nm++;
                }
            }
        }
        System.out.println("propagated mappings: " + all().size() + " classes, " + nf + " field entries, " + nm + " method entries");
    }

    static Set<String> all() { Set<String> s = new HashSet<>(classes.keySet()); s.addAll(infos.keySet()); return s; }

    static String mapClass(String c) {
        String t = classes.get(c);
        if (t != null) return t;
        int d = c.lastIndexOf('$');
        if (d > 0) return mapClass(c.substring(0, d)) + c.substring(d);
        return c;
    }

    static final Pattern DESC = Pattern.compile("L([^;]+);");
    static String mapDesc(String desc) {
        Matcher m = DESC.matcher(desc);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement("L" + mapClass(m.group(1)) + ";"));
        m.appendTail(sb);
        return sb.toString();
    }

    static boolean overridable(int acc) {
        return (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
    }

    static List<String> supers(Info i) {
        List<String> s = new ArrayList<>();
        if (i.sup() != null) s.add(i.sup());
        s.addAll(Arrays.asList(i.itfs()));
        return s;
    }

    /** Finds the target name of an overridable method named key in c's supertypes. */
    static String inheritedName(String c, String key, Set<String> seen) {
        for (String s : supers(infos.get(c))) {
            if (!seen.add(s)) continue;
            Info si = infos.get(s);
            if (si == null) continue;
            for (String[] m : si.methods()) {
                if ((m[0] + m[1]).equals(key) && overridable(Integer.parseInt(m[2]))) {
                    String t = methodMap.getOrDefault(s, Map.of()).get(key);
                    if (t != null) return t;
                }
            }
            String t = inheritedName(s, key, seen);
            if (t != null) return t;
        }
        return null;
    }

    static void resolveOverrides(String c, Set<String> done) {
        if (!done.add(c)) return;
        Info i = infos.get(c);
        for (String s : supers(i)) if (infos.containsKey(s)) resolveOverrides(s, done);
        for (String[] m : i.methods()) {
            if (!overridable(Integer.parseInt(m[2])) || m[0].startsWith("<")) continue;
            String key = m[0] + m[1];
            String t = inheritedName(c, key, new HashSet<>());
            if (t != null) methodMap.computeIfAbsent(c, k -> new HashMap<>()).put(key, t);
        }
    }

    static void collect(String c, Map<String, String> m, Map<String, String> f, Set<String> seen, boolean self) {
        if (!seen.add(c)) return;
        Info i = infos.get(c);
        Map<String, String> mm = methodMap.getOrDefault(c, Map.of());
        Map<String, String> fm = fieldMap.getOrDefault(c, Map.of());
        if (i == null) {
            // Only known from the mapping file (not in the jar): take it as declared.
            for (Map.Entry<String, String> e : mm.entrySet()) m.putIfAbsent(e.getKey(), e.getValue());
            for (Map.Entry<String, String> e : fm.entrySet()) f.putIfAbsent(e.getKey(), e.getValue());
            return;
        }
        for (String[] x : i.methods()) {
            String key = x[0] + x[1];
            String t = mm.get(key);
            // Members with no mapping at all (NeoForge additions, overrides of
            // library methods) keep their name; listing them lets the Mixin
            // annotation processor resolve targets like NeoForge's getQuads.
            if (t == null && self && !x[0].startsWith("<")) t = x[0];
            if (t == null || (!self && !overridable(Integer.parseInt(x[2])) && (Integer.parseInt(x[2]) & Opcodes.ACC_STATIC) == 0)) continue;
            if (!self && (Integer.parseInt(x[2]) & Opcodes.ACC_PRIVATE) != 0) continue;
            m.putIfAbsent(key, t);
        }
        for (String[] x : i.fields()) {
            String t = fm.get(x[0]);
            if (t == null && self) t = x[0];
            if (t == null) continue;
            if (!self && (Integer.parseInt(x[2]) & Opcodes.ACC_PRIVATE) != 0) continue;
            f.putIfAbsent(x[0], t);
        }
        for (String s : supers(i)) collect(s, m, f, seen, false);
    }
}
