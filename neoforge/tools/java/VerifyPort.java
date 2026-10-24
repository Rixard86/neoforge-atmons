// SPDX-License-Identifier: Apache-2.0
//
// Static check of the NeoForge jar against the runtime it will meet, run by
// `build.py verify`. Nothing here starts Minecraft; it answers the questions
// that would otherwise only show up as a crash on the player's machine:
//
//  1. Linkage: every class, field and method the mod's code refers to exists
//     in Minecraft (NeoForge-patched, Mojang names), NeoForge or a library,
//     and non-mixin code only touches members it can access (after the
//     generated access transformer).
//  2. Mixins: every mixin target class, target method, @At injection point
//     (INVOKE, FIELD, NEW, CONSTANT, with ordinals), @Shadow, @Accessor and
//     @Invoker resolves in the real target bytecode, through the refmap.
//  3. Neighbours (optional): which other mods' mixins hit the same target
//     methods, as a list of places to look first if something breaks.
//
// Usage: java -cp <asm jars> VerifyPort.java <mod.jar> <runtime-classpath-file> [other-mods-dir]
// Exit status 1 if any linkage or mixin error was found.

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class VerifyPort {
    // ------------------------------------------------------------ repository

    static final Map<String, ZipFile> index = new HashMap<>();   // class name -> jar
    static final Map<String, ClassNode> cache = new HashMap<>();
    static final Map<String, byte[]> modClasses = new TreeMap<>();
    static final Set<String> missingReported = new HashSet<>();

    static void indexJar(String path, boolean mod) throws IOException {
        ZipFile z = new ZipFile(path);
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
            ZipEntry en = e.nextElement();
            String n = en.getName();
            if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
            String cn = n.substring(0, n.length() - 6);
            if (mod) {
                modClasses.put(cn, z.getInputStream(en).readAllBytes());
                index.put(cn, z);
            } else {
                index.putIfAbsent(cn, z);
            }
        }
    }

    static ClassNode node(String name) {
        if (cache.containsKey(name)) return cache.get(name);
        ClassNode cn = null;
        try {
            byte[] data = null;
            if (modClasses.containsKey(name)) data = modClasses.get(name);
            else if (index.containsKey(name)) {
                ZipFile z = index.get(name);
                data = z.getInputStream(z.getEntry(name + ".class")).readAllBytes();
            } else {
                try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
                    if (in != null) data = in.readAllBytes();
                }
            }
            if (data != null) {
                cn = new ClassNode();
                new ClassReader(data).accept(cn, ClassReader.SKIP_FRAMES);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cache.put(name, cn);
        return cn;
    }

    static boolean exists(String cls) {
        if (cls.startsWith("[")) {
            Type t = Type.getType(cls);
            while (t.getSort() == Type.ARRAY) t = t.getElementType();
            return t.getSort() != Type.OBJECT || exists(t.getInternalName());
        }
        return node(cls) != null;
    }

    // ------------------------------------------------------------ resolution

    record Found(ClassNode owner, Object member) {}

    static Found findMethod(String owner, String name, String desc) {
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(owner);
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            ClassNode n = node(c);
            if (n == null) continue;
            for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return new Found(n, m);
            // Signature-polymorphic methods (MethodHandle.invoke*) match any descriptor.
            if (c.equals("java/lang/invoke/MethodHandle") || c.equals("java/lang/invoke/VarHandle")) {
                for (MethodNode m : n.methods) if (m.name.equals(name) && (m.access & Opcodes.ACC_NATIVE) != 0) return new Found(n, m);
            }
            if (n.superName != null) q.add(n.superName);
            q.addAll(n.interfaces);
        }
        return null;
    }

    static Found findField(String owner, String name, String desc) {
        Deque<String> q = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        q.add(owner);
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            ClassNode n = node(c);
            if (n == null) continue;
            for (FieldNode f : n.fields) if (f.name.equals(name) && f.desc.equals(desc)) return new Found(n, f);
            q.addAll(n.interfaces);
            if (n.superName != null) q.add(n.superName);
        }
        return null;
    }

    static boolean isSubclass(String sub, String sup) {
        for (String c = sub; c != null; ) {
            if (c.equals(sup)) return true;
            ClassNode n = node(c);
            if (n == null) return false;
            c = n.superName;
        }
        return false;
    }

    static String pkg(String c) {
        int i = c.lastIndexOf('/');
        return i < 0 ? "" : c.substring(0, i);
    }

    // ------------------------------------------------------------ access transformer

    static final Set<String> atPublic = new HashSet<>(); // "owner", "owner field", "owner name desc"

    static void readAt(String text) {
        for (String raw : text.split("\n")) {
            String line = raw.split("#", 2)[0].trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            String owner = p[1].replace('.', '/');
            if (p.length == 2) atPublic.add(owner);
            else {
                String m = p[2];
                int paren = m.indexOf('(');
                atPublic.add(paren < 0 ? owner + " " + m : owner + " " + m.substring(0, paren) + " " + m.substring(paren));
            }
        }
    }

    // ------------------------------------------------------------ reporting

    static int errors = 0, warnings = 0;
    static final Map<String, List<String>> report = new TreeMap<>();

    static void error(String where, String what) {
        errors++;
        report.computeIfAbsent("ERROR " + where, k -> new ArrayList<>()).add(what);
    }

    static void warn(String where, String what) {
        warnings++;
        report.computeIfAbsent("warn  " + where, k -> new ArrayList<>()).add(what);
    }

    // ------------------------------------------------------------ 1. linkage

    static void checkLinkage(String cls, Set<String> mixins) {
        ClassNode n = node(cls);
        boolean mixin = mixins.contains(cls);
        Set<String> classRefs = new TreeSet<>();
        if (n.superName != null) classRefs.add(n.superName);
        classRefs.addAll(n.interfaces);
        for (FieldNode f : n.fields) addTypes(classRefs, Type.getType(f.desc));
        for (MethodNode m : n.methods) {
            addTypes(classRefs, Type.getMethodType(m.desc));
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode mi) {
                    if (mi.owner.startsWith("[")) continue;
                    classRefs.add(mi.owner);
                    addTypes(classRefs, Type.getMethodType(mi.desc));
                    if (mixin && mi.owner.equals(cls)) continue; // shadows: checked in part 2
                    if (!exists(mi.owner)) continue;
                    Found f = findMethod(mi.owner, mi.name, mi.desc);
                    if (f == null) error(cls, "missing method " + mi.owner + "." + mi.name + mi.desc + " (in " + m.name + ")");
                    else if (!mixin) checkAccess(cls, f.owner(), ((MethodNode) f.member()).access,
                            mi.owner + " " + mi.name + " " + mi.desc, f.owner().name + " " + mi.name + " " + mi.desc, m.name);
                } else if (insn instanceof FieldInsnNode fi) {
                    classRefs.add(fi.owner);
                    addTypes(classRefs, Type.getType(fi.desc));
                    if (mixin && fi.owner.equals(cls)) continue;
                    if (!exists(fi.owner)) continue;
                    Found f = findField(fi.owner, fi.name, fi.desc);
                    if (f == null) error(cls, "missing field " + fi.owner + "." + fi.name + ":" + fi.desc + " (in " + m.name + ")");
                    else if (!mixin) checkAccess(cls, f.owner(), ((FieldNode) f.member()).access,
                            fi.owner + " " + fi.name, f.owner().name + " " + fi.name, m.name);
                } else if (insn instanceof TypeInsnNode ti) {
                    if (ti.desc.startsWith("[")) addTypes(classRefs, Type.getType(ti.desc));
                    else classRefs.add(ti.desc);
                } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) {
                    addTypes(classRefs, t);
                } else if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object a : indy.bsmArgs) {
                        if (a instanceof Handle h && !h.getOwner().startsWith("[")) {
                            classRefs.add(h.getOwner());
                            if (h.getTag() <= Opcodes.H_PUTSTATIC) {
                                if (exists(h.getOwner()) && findField(h.getOwner(), h.getName(), h.getDesc()) == null)
                                    error(cls, "missing field handle " + h);
                            } else if (!(mixin && h.getOwner().equals(cls)) && exists(h.getOwner())
                                    && findMethod(h.getOwner(), h.getName(), h.getDesc()) == null) {
                                error(cls, "missing method handle " + h.getOwner() + "." + h.getName() + h.getDesc() + " (in " + m.name + ")");
                            }
                        }
                    }
                }
            }
        }
        for (String c : classRefs) {
            if (c.startsWith("org/jetbrains/annotations")) continue;
            if (!exists(c) && missingReported.add(cls + c)) error(cls, "missing class " + c);
        }
    }

    static void addTypes(Set<String> out, Type t) {
        switch (t.getSort()) {
            case Type.ARRAY -> addTypes(out, t.getElementType());
            case Type.OBJECT -> out.add(t.getInternalName());
            case Type.METHOD -> {
                addTypes(out, t.getReturnType());
                for (Type a : t.getArgumentTypes()) addTypes(out, a);
            }
            default -> { }
        }
    }

    static void checkAccess(String from, ClassNode decl, int access, String refKey, String declKey, String inMethod) {
        if (from.equals(decl.name)) return;
        if (modClasses.containsKey(decl.name)) return; // the mod's own classes: javac already checked
        boolean classPublic = (decl.access & Opcodes.ACC_PUBLIC) != 0 || atPublic.contains(decl.name);
        if (!classPublic && !pkg(from).equals(pkg(decl.name)))
            error(from, "class " + decl.name + " is not public at runtime (used in " + inMethod + ")");
        if ((access & Opcodes.ACC_PUBLIC) != 0) return;
        if (atPublic.contains(declKey) || atPublic.contains(refKey)) return;
        if ((access & Opcodes.ACC_PROTECTED) != 0 && isSubclass(from, decl.name)) return;
        if ((access & Opcodes.ACC_PRIVATE) == 0 && pkg(from).equals(pkg(decl.name))) return;
        error(from, "illegal access to " + declKey + " (" + accessName(access) + ", used in " + inMethod + ")");
    }

    static String accessName(int a) {
        if ((a & Opcodes.ACC_PRIVATE) != 0) return "private";
        if ((a & Opcodes.ACC_PROTECTED) != 0) return "protected";
        return "package-private";
    }

    // ------------------------------------------------------------ 2. mixins

    static Map<String, Map<String, String>> refmap = new HashMap<>();

    static String remap(String mixin, String value) {
        Map<String, String> m = refmap.get(mixin);
        if (m != null && m.containsKey(value)) return m.get(value);
        return value;
    }

    /** MemberInfo-like parse of "Lowner;name(desc)ret" / "name(desc)" / "name" / "owner.name:desc" */
    record Ref(String owner, String name, String desc) {}

    static final Pattern REF = Pattern.compile("^(?:L([^;]+);)?([^(:]*)(?::(.+)|(\\(.*))?$");

    static Ref parseRef(String s) {
        s = s.replace(" ", "");
        // "net/minecraft/Foo.bar:I" style
        if (!s.startsWith("L") && s.contains(".") && !s.contains("(")) {
            int dot = s.lastIndexOf('.');
            String rest = s.substring(dot + 1);
            int colon = rest.indexOf(':');
            return new Ref(s.substring(0, dot), colon < 0 ? rest : rest.substring(0, colon), colon < 0 ? null : rest.substring(colon + 1));
        }
        Matcher m = REF.matcher(s);
        if (!m.matches()) return new Ref(null, s, null);
        String desc = m.group(3) != null ? m.group(3) : m.group(4);
        String name = m.group(2);
        if (name.equals("*")) name = null;
        return new Ref(m.group(1), name, desc);
    }

    static Object annValue(AnnotationNode a, String key) {
        if (a == null || a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return (List<Object>) l;
        return List.of(o);
    }

    static final Set<String> INJECTORS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;");

    static final Map<String, Set<String>> ourTargets = new TreeMap<>(); // "Target.method(desc)" -> mixins

    static void checkMixin(String mixinName) {
        ClassNode mx = node(mixinName);
        if (mx == null) { error(mixinName, "mixin class missing from jar"); return; }
        List<String> targets = new ArrayList<>();
        AnnotationNode mixinAnn = null;
        for (AnnotationNode a : invisible(mx.invisibleAnnotations, mx.visibleAnnotations)) {
            if (a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) mixinAnn = a;
        }
        if (mixinAnn == null) { error(mixinName, "no @Mixin annotation"); return; }
        for (Object v : list(annValue(mixinAnn, "value"))) targets.add(((Type) v).getInternalName());
        for (Object v : list(annValue(mixinAnn, "targets"))) targets.add(remap(mixinName, (String) v).replace('.', '/'));
        List<ClassNode> targetNodes = new ArrayList<>();
        for (String t : targets) {
            ClassNode tn = node(t);
            if (tn == null) error(mixinName, "target class missing: " + t);
            else targetNodes.add(tn);
        }
        if (targetNodes.isEmpty()) return;

        // Shadows (names already remapped in the jar) must exist in the target.
        for (FieldNode f : mx.fields) {
            if (has(f.invisibleAnnotations, f.visibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) {
                for (ClassNode tn : targetNodes) {
                    boolean ok = tn.fields.stream().anyMatch(x -> x.name.equals(f.name) && x.desc.equals(f.desc));
                    if (!ok) error(mixinName, "@Shadow field " + f.name + ":" + f.desc + " not in " + tn.name);
                }
            }
        }
        for (MethodNode m : mx.methods) {
            List<AnnotationNode> anns = invisible(m.invisibleAnnotations, m.visibleAnnotations);
            for (AnnotationNode a : anns) {
                switch (a.desc) {
                    case "Lorg/spongepowered/asm/mixin/Shadow;", "Lorg/spongepowered/asm/mixin/Overwrite;" -> {
                        for (ClassNode tn : targetNodes) {
                            boolean ok = findMethod(tn.name, m.name, m.desc) != null;
                            if (!ok) error(mixinName, (a.desc.contains("Shadow") ? "@Shadow" : "@Overwrite")
                                    + " method " + m.name + m.desc + " not in " + tn.name);
                        }
                    }
                    case "Lorg/spongepowered/asm/mixin/gen/Accessor;", "Lorg/spongepowered/asm/mixin/gen/Invoker;" -> {
                        boolean accessor = a.desc.contains("Accessor");
                        String raw = (String) annValue(a, "value");
                        if (raw == null || raw.isEmpty()) raw = inflect(m.name, accessor);
                        Ref r = parseRef(remap(mixinName, raw));
                        for (ClassNode tn : targetNodes) {
                            boolean ok;
                            if (accessor) {
                                Type ft = Type.getArgumentTypes(m.desc).length == 1 && Type.getReturnType(m.desc) == Type.VOID_TYPE
                                        ? Type.getArgumentTypes(m.desc)[0] : Type.getReturnType(m.desc);
                                ok = tn.fields.stream().anyMatch(x -> x.name.equals(r.name()) && (r.desc() == null || x.desc.equals(r.desc())));
                                if (!ok) error(mixinName, "@Accessor target " + r.name() + " (" + ft + ") not in " + tn.name);
                            } else {
                                ok = tn.methods.stream().anyMatch(x -> x.name.equals(r.name()) && (r.desc() == null || x.desc.equals(r.desc())));
                                if (!ok) error(mixinName, "@Invoker target " + r.name() + " not in " + tn.name);
                            }
                        }
                    }
                    default -> {
                        if (INJECTORS.contains(a.desc)) checkInjector(mixinName, m, a, targetNodes);
                    }
                }
            }
        }
    }

    static String inflect(String method, boolean accessor) {
        String n = method;
        int dollar = n.lastIndexOf('$');
        if (dollar >= 0) n = n.substring(dollar + 1);
        for (String prefix : accessor ? new String[]{"get", "set", "is"} : new String[]{"call", "invoke", "new", "create"}) {
            if (n.startsWith(prefix) && n.length() > prefix.length() && Character.isUpperCase(n.charAt(prefix.length()))) {
                String rest = n.substring(prefix.length());
                if (accessor && rest.chars().allMatch(ch -> Character.isUpperCase(ch) || ch == '_' || Character.isDigit(ch))) return rest;
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return n;
    }

    static boolean has(List<AnnotationNode> a, List<AnnotationNode> b, String desc) {
        for (AnnotationNode x : invisible(a, b)) if (x.desc.equals(desc)) return true;
        return false;
    }

    static List<AnnotationNode> invisible(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> out = new ArrayList<>();
        if (a != null) out.addAll(a);
        if (b != null) out.addAll(b);
        return out;
    }

    static void checkInjector(String mixinName, MethodNode handler, AnnotationNode inj, List<ClassNode> targets) {
        injectorsChecked++;
        String kind = inj.desc.substring(inj.desc.lastIndexOf('/') + 1, inj.desc.length() - 1);
        Object requireObj = annValue(inj, "require");
        int require = requireObj == null ? 1 : (Integer) requireObj;
        List<Object> methods = list(annValue(inj, "method"));
        List<Object> ats = new ArrayList<>(list(annValue(inj, "at")));
        boolean constantInjector = kind.equals("ModifyConstant");
        for (ClassNode tn : targets) {
            List<MethodNode> matched = new ArrayList<>();
            for (Object mo : methods) {
                Ref r = parseRef(remap(mixinName, (String) mo));
                for (MethodNode tm : tn.methods) {
                    if (r.name() != null && !tm.name.equals(r.name())) continue;
                    if (r.desc() != null && !tm.desc.equals(r.desc())) continue;
                    matched.add(tm);
                }
                if (matched.isEmpty() && require > 0) {
                    error(mixinName, "@" + kind + " " + handler.name + ": target method " + mo + " -> "
                            + remap(mixinName, (String) mo) + " not found in " + tn.name);
                }
            }
            for (MethodNode tm : matched) {
                ourTargets.computeIfAbsent(tn.name + "." + tm.name + tm.desc, k -> new TreeSet<>()).add(simple(mixinName));
                checkHandler(mixinName, kind, handler, tn, tm, ats);
            }
            if (kind.equals("WrapMethod") || (constantInjector && ats.isEmpty()) || matched.isEmpty()) continue;
            // Mixin counts successful injections across every matched target
            // method, so an @At only has to match in one of them.
            for (Object ao : ats) {
                AnnotationNode at = (AnnotationNode) ao;
                int total = 0;
                boolean unchecked = false;
                atsChecked++;
                for (MethodNode tm : matched) {
                    int n = countAt(mixinName, tm, at);
                    if (n < 0) unchecked = true;
                    else total += n;
                }
                if (unchecked) {
                    warn(mixinName, "@" + kind + " " + handler.name + ": @At(" + annValue(at, "value") + ") not checked statically");
                } else if (total == 0 && require > 0) {
                    StringBuilder where = new StringBuilder();
                    for (MethodNode tm : matched) where.append(' ').append(tn.name).append('.').append(tm.name).append(tm.desc);
                    error(mixinName, "@" + kind + " " + handler.name + ": @At(" + annValue(at, "value") + ", "
                            + remap(mixinName, String.valueOf(annValue(at, "target"))) + ") matches nothing in" + where);
                }
            }
        }
    }

    // ------------------------------------------------------------ handler signatures

    static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    static final String CIR = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
    static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";

    /** Types of a handler's parameters, minus trailing MixinExtras @Local/@Share captures. */
    static List<Type> handlerParams(MethodNode h) {
        Type[] args = Type.getArgumentTypes(h.desc);
        int n = args.length;
        while (n > 0 && h.invisibleParameterAnnotations != null && h.invisibleParameterAnnotations.length > n - 1
                && hasLocal(h.invisibleParameterAnnotations[n - 1])) n--;
        while (n > 0 && h.visibleParameterAnnotations != null && h.visibleParameterAnnotations.length > n - 1
                && hasLocal(h.visibleParameterAnnotations[n - 1])) n--;
        return new ArrayList<>(Arrays.asList(args).subList(0, n));
    }

    static boolean hasLocal(List<AnnotationNode> anns) {
        if (anns == null) return false;
        for (AnnotationNode a : anns) {
            if (a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) return true;
        }
        return false;
    }

    static boolean assignable(Type from, Type to) {
        if (from.equals(to)) return true;
        if (from.getSort() != Type.OBJECT || to.getSort() != Type.OBJECT) {
            return from.getSort() >= Type.ARRAY && to.getDescriptor().equals("Ljava/lang/Object;");
        }
        if (to.getInternalName().equals("java/lang/Object")) return true;
        Deque<String> q = new ArrayDeque<>(List.of(from.getInternalName()));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            if (c.equals(to.getInternalName())) return true;
            ClassNode n = node(c);
            if (n == null) continue;
            if (n.superName != null) q.add(n.superName);
            q.addAll(n.interfaces);
        }
        return false;
    }

    static void sigError(String mixin, String kind, MethodNode h, String why) {
        error(mixin, "@" + kind + " " + h.name + h.desc + ": " + why);
    }

    static int handlersChecked = 0, injectorsChecked = 0, atsChecked = 0;

    static void checkHandler(String mixin, String kind, MethodNode h, ClassNode tn, MethodNode tm, List<Object> ats) {
        handlersChecked++;
        List<Type> hp = handlerParams(h);
        Type[] targetArgs = Type.getArgumentTypes(tm.desc);
        Type targetRet = Type.getReturnType(tm.desc);
        boolean targetStatic = (tm.access & Opcodes.ACC_STATIC) != 0;
        switch (kind) {
            case "Inject" -> {
                int ci = -1;
                for (int i = 0; i < hp.size(); i++) {
                    String d = hp.get(i).getDescriptor();
                    if (d.equals(CI) || d.equals(CIR)) { ci = i; break; }
                }
                if (ci < 0) { sigError(mixin, kind, h, "no CallbackInfo parameter"); return; }
                if (targetRet.getSort() != Type.VOID && !tm.name.equals("<init>")
                        && !hp.get(ci).getDescriptor().equals(CIR)) {
                    sigError(mixin, kind, h, "target returns " + targetRet + ", needs CallbackInfoReturnable");
                }
                if (ci != 0 && ci != targetArgs.length) {
                    sigError(mixin, kind, h, "takes " + ci + " target arguments, target " + tn.name + "." + tm.name + tm.desc
                            + " has " + targetArgs.length);
                    return;
                }
                for (int i = 0; i < ci; i++) {
                    if (!hp.get(i).equals(targetArgs[i])) {
                        sigError(mixin, kind, h, "argument " + i + " is " + hp.get(i) + ", target has " + targetArgs[i]
                                + " in " + tn.name + "." + tm.name + tm.desc);
                        return;
                    }
                }
            }
            case "ModifyReturnValue" -> {
                if (hp.isEmpty() || !hp.get(0).equals(targetRet) || !Type.getReturnType(h.desc).equals(targetRet))
                    sigError(mixin, kind, h, "target returns " + targetRet);
            }
            case "Redirect", "WrapOperation", "ModifyExpressionValue", "WrapWithCondition" -> {
                if (ats.isEmpty()) return;
                AnnotationNode at = (AnnotationNode) ats.get(0);
                String value = (String) annValue(at, "value");
                String target = (String) annValue(at, "target");
                if (target == null) return;
                target = remap(mixin, target);
                List<Type> call = new ArrayList<>();
                Type callRet;
                if (value.startsWith("INVOKE")) {
                    Ref r = parseRef(target);
                    if (r.desc() == null || r.owner() == null) return;
                    Found f = findMethod(r.owner(), r.name(), r.desc());
                    boolean isStatic = f != null && (((MethodNode) f.member()).access & Opcodes.ACC_STATIC) != 0;
                    if (!isStatic) call.add(Type.getObjectType(r.owner()));
                    call.addAll(Arrays.asList(Type.getArgumentTypes(r.desc())));
                    callRet = Type.getReturnType(r.desc());
                } else if (value.equals("FIELD")) {
                    Ref r = parseRef(target);
                    if (r.desc() == null || r.owner() == null) return;
                    Found f = findField(r.owner(), r.name(), r.desc());
                    boolean isStatic = f != null && (((FieldNode) f.member()).access & Opcodes.ACC_STATIC) != 0;
                    Object opObj = annValue(at, "opcode");
                    int opcode = opObj == null ? -1 : (Integer) opObj;
                    if (!isStatic) call.add(Type.getObjectType(r.owner()));
                    if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
                        call.add(Type.getType(r.desc()));
                        callRet = Type.VOID_TYPE;
                    } else {
                        callRet = Type.getType(r.desc());
                    }
                } else if (value.equals("NEW")) {
                    // NEW targets are a class name, "Lclass;", or a constructor
                    // descriptor "(args)Lclass;".
                    String t = target;
                    if (t.startsWith("(")) t = Type.getReturnType(t).getInternalName();
                    else if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
                    callRet = Type.getObjectType(t);
                    if (!kind.equals("ModifyExpressionValue")) return; // constructor args need the <init> desc
                } else {
                    return;
                }
                Type hr = Type.getReturnType(h.desc);
                if (kind.equals("ModifyExpressionValue")) {
                    if (hp.isEmpty() || !assignable(callRet, hp.get(0)) || !assignable(hr, callRet))
                        sigError(mixin, kind, h, "expression type is " + callRet);
                    return;
                }
                if (kind.equals("WrapOperation") || kind.equals("WrapWithCondition")) {
                    int op = -1;
                    for (int i = 0; i < hp.size(); i++) if (hp.get(i).getDescriptor().equals(OPERATION)) op = i;
                    List<Type> args = op >= 0 ? hp.subList(0, op) : hp;
                    if (kind.equals("WrapOperation") && op < 0) { sigError(mixin, kind, h, "no Operation parameter"); return; }
                    checkCallArgs(mixin, kind, h, call, args, target);
                    if (kind.equals("WrapOperation") && !assignable(hr, callRet) && !(hr.equals(callRet)))
                        sigError(mixin, kind, h, "returns " + hr + ", wrapped call returns " + callRet);
                    return;
                }
                // Redirect: may also capture the target method's arguments after the call's.
                List<Type> args = hp.size() > call.size() ? hp.subList(0, call.size()) : hp;
                checkCallArgs(mixin, kind, h, call, args, target);
                if (!assignable(hr, callRet) && !hr.equals(callRet))
                    sigError(mixin, kind, h, "returns " + hr + ", redirected call returns " + callRet);
            }
            default -> { }
        }
    }

    static void checkCallArgs(String mixin, String kind, MethodNode h, List<Type> call, List<Type> args, String target) {
        if (args.size() != call.size()) {
            sigError(mixin, kind, h, "takes " + args.size() + " call arguments, " + target + " has " + call.size());
            return;
        }
        for (int i = 0; i < call.size(); i++) {
            if (!assignable(call.get(i), args.get(i)) && !call.get(i).equals(args.get(i))) {
                sigError(mixin, kind, h, "call argument " + i + " is " + call.get(i) + ", handler takes " + args.get(i));
                return;
            }
        }
    }

    static String simple(String c) {
        return c.substring(c.lastIndexOf('/') + 1);
    }

    /** Number of matches of an @At in a target method; -1 if this kind is not checked. */
    static int countAt(String mixinName, MethodNode tm, AnnotationNode at) {
        String value = (String) annValue(at, "value");
        Object ordObj = annValue(at, "ordinal");
        int ordinal = ordObj == null ? -1 : (Integer) ordObj;
        String target = (String) annValue(at, "target");
        if (target != null) target = remap(mixinName, target);
        List<Object> args = list(annValue(at, "args"));
        int count = 0;
        switch (value) {
            case "HEAD", "RETURN", "TAIL" -> { return 1; }
            case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                Ref r = parseRef(target);
                for (AbstractInsnNode i : tm.instructions) {
                    if (i instanceof MethodInsnNode mi
                            && (r.owner() == null || mi.owner.equals(r.owner()))
                            && (r.name() == null || mi.name.equals(r.name()))
                            && (r.desc() == null || mi.desc.equals(r.desc()))) count++;
                }
            }
            case "FIELD" -> {
                Ref r = parseRef(target);
                Object opObj = annValue(at, "opcode");
                int opcode = opObj == null ? -1 : (Integer) opObj;
                for (AbstractInsnNode i : tm.instructions) {
                    if (i instanceof FieldInsnNode fi
                            && (r.owner() == null || fi.owner.equals(r.owner()))
                            && (r.name() == null || fi.name.equals(r.name()))
                            && (r.desc() == null || fi.desc.equals(r.desc()))
                            && (opcode < 0 || fi.getOpcode() == opcode)) count++;
                }
            }
            case "NEW" -> {
                String type = target;
                if (type != null && type.startsWith("(")) type = Type.getReturnType(type).getInternalName();
                else if (type != null && type.startsWith("L") && type.endsWith(";")) type = type.substring(1, type.length() - 1);
                for (AbstractInsnNode i : tm.instructions) {
                    if (i instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW && (type == null || ti.desc.equals(type))) count++;
                }
            }
            case "CONSTANT" -> {
                String want = null;
                for (Object a : args) {
                    String s = (String) a;
                    if (s.startsWith("stringValue=")) want = s.substring("stringValue=".length());
                    else if (s.startsWith("intValue=") || s.startsWith("floatValue=") || s.startsWith("doubleValue=")
                            || s.startsWith("longValue=") || s.startsWith("classValue=")) return -1;
                }
                if (want == null) return -1;
                for (AbstractInsnNode i : tm.instructions) {
                    if (i instanceof LdcInsnNode ldc && want.equals(ldc.cst)) count++;
                }
            }
            default -> { return -1; }
        }
        if (ordinal >= 0) return count > ordinal ? 1 : 0;
        return count;
    }

    // ------------------------------------------------------------ 3. neighbours

    static void scanNeighbours(Path dir) throws IOException {
        Map<String, Set<String>> theirs = new TreeMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path jar : ds) {
                try (ZipFile z = new ZipFile(jar.toFile())) {
                    Map<String, Map<String, String>> theirRefmap = new HashMap<>();
                    List<String> mixinClasses = new ArrayList<>();
                    for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                        ZipEntry en = e.nextElement();
                        String n = en.getName();
                        if (n.contains("/") || !n.endsWith(".json")) continue;
                        String text = new String(z.getInputStream(en).readAllBytes());
                        if (text.contains("\"package\"") && (text.contains("\"mixins\"") || text.contains("\"client\""))) {
                            String pkg = jsonString(text, "package");
                            for (String m : jsonArray(text, "mixins")) mixinClasses.add(pkg.replace('.', '/') + "/" + m.replace('.', '/'));
                            for (String m : jsonArray(text, "client")) mixinClasses.add(pkg.replace('.', '/') + "/" + m.replace('.', '/'));
                        } else if (text.contains("\"mappings\"")) {
                            theirRefmap.putAll(parseRefmap(text));
                        }
                    }
                    for (String mc : mixinClasses) {
                        ZipEntry en = z.getEntry(mc + ".class");
                        if (en == null) continue;
                        ClassNode cn = new ClassNode();
                        new ClassReader(z.getInputStream(en).readAllBytes()).accept(cn, ClassReader.SKIP_CODE);
                        List<String> targets = new ArrayList<>();
                        for (AnnotationNode a : invisible(cn.invisibleAnnotations, cn.visibleAnnotations)) {
                            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                            for (Object v : list(annValue(a, "value"))) targets.add(((Type) v).getInternalName());
                            for (Object v : list(annValue(a, "targets"))) targets.add(((String) v).replace('.', '/'));
                        }
                        for (MethodNode m : cn.methods) {
                            for (AnnotationNode a : invisible(m.invisibleAnnotations, m.visibleAnnotations)) {
                                if (!INJECTORS.contains(a.desc) && !a.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) continue;
                                for (String t : targets) {
                                    ClassNode tn = node(t);
                                    if (tn == null) continue;
                                    List<Object> ms = list(annValue(a, "method"));
                                    if (a.desc.contains("Overwrite")) ms = List.of(m.name + m.desc);
                                    for (Object mo : ms) {
                                        Map<String, String> rm = theirRefmap.getOrDefault(mc, Map.of());
                                        String raw = (String) mo;
                                        Ref r = parseRef(rm.getOrDefault(raw, raw));
                                        for (MethodNode tm : tn.methods) {
                                            if (r.name() != null && !tm.name.equals(r.name())) continue;
                                            if (r.desc() != null && !tm.desc.equals(r.desc())) continue;
                                            String key = t + "." + tm.name + tm.desc;
                                            if (ourTargets.containsKey(key))
                                                theirs.computeIfAbsent(key, k -> new TreeSet<>()).add(jar.getFileName().toString());
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    // A jar we cannot read is not ours to judge.
                }
            }
        }
        System.out.println();
        System.out.println("== Other mods injecting into the same methods (" + theirs.size() + " methods)");
        for (Map.Entry<String, Set<String>> e : theirs.entrySet()) {
            System.out.println("  " + e.getKey().replace('/', '.') + "  ours: " + ourTargets.get(e.getKey()) + "  theirs: " + e.getValue());
        }
    }

    static String jsonString(String text, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(text);
        return m.find() ? m.group(1) : "";
    }

    static List<String> jsonArray(String text, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(text);
        List<String> out = new ArrayList<>();
        if (m.find()) {
            Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
            while (s.find()) out.add(s.group(1));
        }
        return out;
    }

    /** {"mappings": {"a/B": {"k": "v", ...}, ...}} with no nesting beyond that. */
    static Map<String, Map<String, String>> parseRefmap(String text) {
        Map<String, Map<String, String>> out = new HashMap<>();
        int start = text.indexOf("\"mappings\"");
        if (start < 0) return out;
        int i = text.indexOf('{', start) + 1;
        Pattern cls = Pattern.compile("\\G\\s*,?\\s*\"([^\"]+)\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher m = cls.matcher(text);
        m.region(i, text.length());
        while (m.find()) {
            Map<String, String> entries = new HashMap<>();
            Matcher kv = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(m.group(2));
            while (kv.find()) entries.put(kv.group(1), kv.group(2));
            out.put(m.group(1), entries);
            m.region(m.end(), text.length());
        }
        return out;
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) throws Exception {
        String modJar = args[0];
        for (String l : Files.readAllLines(Path.of(args[1]))) if (!l.isBlank()) indexJar(l.trim(), false);
        indexJar(modJar, true);
        Set<String> mixins = new TreeSet<>();
        try (ZipFile z = new ZipFile(modJar)) {
            ZipEntry at = z.getEntry("META-INF/accesstransformer.cfg");
            if (at != null) readAt(new String(z.getInputStream(at).readAllBytes()));
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.getName().endsWith("refmap.json")) {
                    refmap.putAll(parseRefmap(new String(z.getInputStream(en).readAllBytes())));
                }
            }
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                String n = en.getName();
                if (n.contains("/") || !n.endsWith("mixins.json")) continue;
                String text = new String(z.getInputStream(en).readAllBytes());
                String pkg = jsonString(text, "package").replace('.', '/');
                for (String section : new String[]{"mixins", "client", "server"})
                    for (String m : jsonArray(text, section)) mixins.add(pkg + "/" + m.replace('.', '/'));
            }
        }
        System.out.println("classes: " + modClasses.size() + ", mixins: " + mixins.size() + ", access transformer entries: " + atPublic.size());
        for (String c : modClasses.keySet()) checkLinkage(c, mixins);
        for (String m : mixins) checkMixin(m);

        for (Map.Entry<String, List<String>> e : report.entrySet()) {
            System.out.println(e.getKey());
            for (String s : e.getValue()) System.out.println("    " + s);
        }
        System.out.println();
        System.out.println("checked " + injectorsChecked + " injectors, " + atsChecked + " injection points, "
                + handlersChecked + " handler/target pairs");
        System.out.println("linkage + mixin check: " + errors + " errors, " + warnings + " warnings");
        if (args.length > 2) scanNeighbours(Path.of(args[2]));
        System.exit(errors > 0 ? 1 : 0);
    }
}
