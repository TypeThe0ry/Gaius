import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * G3 link-gap scanner, run by minecraft-link-gap-smoke.mjs through the JDK
 * source launcher with ASM on the class path.
 *
 * <p>Every class of the subject roots (the patched client jar and the compiled
 * Gaius runtime classes) is scanned.  Each class, field and method reference
 * is resolved against subjects, then the class path roots in order, then the
 * running JDK, following the JVM resolution rules (JVMS 5.4.3).  The opcode
 * and the invoke {@code itf} flag must match the owner's kind, and static
 * accesses must hit static members.  Issues are written as tab-separated
 * lines: {@code key<TAB>count<TAB>referrer...}, where key is
 * {@code <kind> <target>}.
 *
 * <pre>
 * java -cp asm.jar LinkGapScanner.java --out issues.tsv
 *     --subject a.jar --subject classesDir --classpath lib.jar ...
 * </pre>
 */
public final class LinkGapScanner {
    private static final int MAX_REFERRERS = 3;

    private final List<Root> subjects = new ArrayList<>();
    private final List<Root> classpath = new ArrayList<>();
    private final Map<String, ClassInfo> classes = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final Map<String, Issue> issues = new TreeMap<>();
    private int scannedClasses;
    private int checkedReferences;

    public static void main(String[] args) throws IOException {
        LinkGapScanner scanner = new LinkGapScanner();
        Path out = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            switch (argument) {
                case "--subject" -> scanner.subjects.add(Root.open(Path.of(args[++index])));
                case "--classpath" -> scanner.classpath.add(Root.open(Path.of(args[++index])));
                case "--out" -> out = Path.of(args[++index]);
                default -> throw new IllegalArgumentException("unknown argument " + argument);
            }
        }
        if (out == null || scanner.subjects.isEmpty()) {
            throw new IllegalArgumentException(
                    "usage: LinkGapScanner --out FILE --subject ROOT... [--classpath ROOT...]");
        }
        try {
            scanner.scan();
        } finally {
            for (Root root : scanner.subjects) root.close();
            for (Root root : scanner.classpath) root.close();
        }
        StringBuilder text = new StringBuilder();
        for (Issue issue : scanner.issues.values()) {
            text.append(issue.key).append('\t').append(issue.count);
            for (String referrer : issue.referrers) text.append('\t').append(referrer);
            text.append('\n');
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("LINK_GAP_SCANNED classes=" + scanner.scannedClasses
                + " references=" + scanner.checkedReferences
                + " issues=" + scanner.issues.size());
    }

    private void scan() throws IOException {
        Set<String> seen = new HashSet<>();
        for (Root root : subjects) {
            for (String name : root.classNames()) {
                if (!seen.add(name)) continue;
                byte[] bytes = root.read(name);
                if (bytes == null) continue;
                scannedClasses++;
                reader(bytes).accept(new SubjectVisitor(), ClassReader.SKIP_FRAMES);
            }
        }
    }

    // ---------------------------------------------------------------- lookup

    private ClassInfo info(String name) {
        if (name.startsWith("[")) {
            return info("java/lang/Object");
        }
        ClassInfo cached = classes.get(name);
        if (cached != null || missing.contains(name)) return cached;
        byte[] bytes = null;
        for (Root root : subjects) {
            if ((bytes = root.read(name)) != null) break;
        }
        if (bytes == null) {
            for (Root root : classpath) {
                if ((bytes = root.read(name)) != null) break;
            }
        }
        if (bytes == null) bytes = readJdk(name);
        if (bytes == null) {
            missing.add(name);
            return null;
        }
        ClassInfo info = new ClassInfo();
        reader(bytes).accept(new InfoVisitor(info), ClassReader.SKIP_CODE
                | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        classes.put(name, info);
        return info;
    }

    // ASM 9.8 reads class files up to major 69 (Java 25). The scanner also
    // reads the running JDK's platform classes, which are newer on a JDK 26
    // runner (major 70). Only symbolic references are needed, so parse a copy
    // with the major version lowered to the newest one ASM accepts.
    private static final int MAX_ASM_MAJOR = 69;

    private static ClassReader reader(byte[] bytes) {
        int major = ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
        if (major <= MAX_ASM_MAJOR) {
            return new ClassReader(bytes);
        }
        byte[] copy = bytes.clone();
        copy[6] = (byte) (MAX_ASM_MAJOR >>> 8);
        copy[7] = (byte) MAX_ASM_MAJOR;
        return new ClassReader(copy);
    }

    private static byte[] readJdk(String name) {
        try (InputStream stream = ClassLoader.getPlatformClassLoader()
                .getResourceAsStream(name + ".class")) {
            return stream == null ? null : stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The class and every supertype must be loadable for a decision. */
    private String missingSupertype(ClassInfo start) {
        Deque<ClassInfo> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            ClassInfo current = queue.poll();
            if (!visited.add(current.name)) continue;
            List<String> parents = new ArrayList<>(current.interfaces);
            if (current.superName != null) parents.add(current.superName);
            for (String parent : parents) {
                ClassInfo parentInfo = info(parent);
                if (parentInfo == null) return parent;
                queue.add(parentInfo);
            }
        }
        return null;
    }

    private Member findField(ClassInfo owner, String name, String desc) {
        Member declared = owner.fields.get(name + ":" + desc);
        if (declared != null) return declared;
        for (String parent : owner.interfaces) {
            ClassInfo parentInfo = info(parent);
            if (parentInfo == null) continue;
            Member found = findField(parentInfo, name, desc);
            if (found != null) return found;
        }
        ClassInfo superInfo = owner.superName == null ? null : info(owner.superName);
        return superInfo == null ? null : findField(superInfo, name, desc);
    }

    private Member findClassMethod(ClassInfo owner, String name, String desc) {
        for (ClassInfo current = owner; current != null;
                current = current.superName == null ? null : info(current.superName)) {
            Member declared = current.methods.get(name + desc);
            if (declared != null) return declared;
            if (current.signaturePolymorphic(name)) return current.methodsByName.get(name);
        }
        return findSuperinterfaceMethod(owner, name, desc, new HashSet<>());
    }

    private Member findInterfaceMethod(ClassInfo owner, String name, String desc) {
        Member declared = owner.methods.get(name + desc);
        if (declared != null) return declared;
        ClassInfo object = info("java/lang/Object");
        if (object != null) {
            Member objectMethod = object.methods.get(name + desc);
            if (objectMethod != null && (objectMethod.access & Opcodes.ACC_PUBLIC) != 0
                    && (objectMethod.access & Opcodes.ACC_STATIC) == 0) {
                return objectMethod;
            }
        }
        return findSuperinterfaceMethod(owner, name, desc, new HashSet<>());
    }

    private Member findSuperinterfaceMethod(
            ClassInfo owner, String name, String desc, Set<String> visited) {
        for (ClassInfo current = owner; current != null;
                current = current.superName == null ? null : info(current.superName)) {
            for (String parent : current.interfaces) {
                if (!visited.add(parent)) continue;
                ClassInfo parentInfo = info(parent);
                if (parentInfo == null) continue;
                Member declared = parentInfo.methods.get(name + desc);
                if (declared != null && (declared.access
                        & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                    return declared;
                }
                Member inherited = findSuperinterfaceMethod(parentInfo, name, desc, visited);
                if (inherited != null) return inherited;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- checks

    private void report(String kind, String target, String referrer) {
        String key = kind + " " + target;
        Issue issue = issues.computeIfAbsent(key, Issue::new);
        issue.count++;
        if (issue.referrers.size() < MAX_REFERRERS && !issue.referrers.contains(referrer)) {
            issue.referrers.add(referrer);
        }
    }

    private ClassInfo requireClass(String name, String referrer) {
        checkedReferences++;
        if (name.startsWith("[")) {
            Type element = Type.getType(name).getElementType();
            if (element.getSort() == Type.OBJECT) requireClass(element.getInternalName(), referrer);
            return info("java/lang/Object");
        }
        ClassInfo info = info(name);
        if (info == null) report("missing-class", name, referrer);
        return info;
    }

    private void checkField(int opcode, String owner, String name, String desc, String referrer) {
        ClassInfo ownerInfo = requireClass(owner, referrer);
        if (ownerInfo == null) return;
        String target = owner + "." + name + ":" + desc;
        Member field = findField(ownerInfo, name, desc);
        if (field == null) {
            String unknown = missingSupertype(ownerInfo);
            report(unknown == null ? "missing-field" : "unresolved-field", target
                    + (unknown == null ? "" : " (missing supertype " + unknown + ")"), referrer);
            return;
        }
        boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
        boolean wantsStatic = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
        if (isStatic != wantsStatic) {
            report("static-mismatch", target + " accessed with " + opcodeName(opcode), referrer);
        }
    }

    private void checkMethod(int opcode, String owner, String name, String desc, boolean itf,
            String referrer) {
        ClassInfo ownerInfo = requireClass(owner, referrer);
        if (ownerInfo == null || owner.startsWith("[")) return;
        String target = owner + "." + name + desc;
        boolean ownerIsInterface = ownerInfo.isInterface();
        if (opcode == Opcodes.INVOKEINTERFACE && !ownerIsInterface) {
            report("itf-mismatch", target + " invokeinterface on a class", referrer);
        } else if (opcode == Opcodes.INVOKEVIRTUAL && ownerIsInterface) {
            report("itf-mismatch", target + " invokevirtual on an interface", referrer);
        } else if (itf != ownerIsInterface) {
            report("itf-mismatch", target + " " + opcodeName(opcode) + " itf=" + itf
                    + " but owner is " + (ownerIsInterface ? "an interface" : "a class"), referrer);
        }
        Member method;
        if (name.equals("<init>") || name.equals("<clinit>")) {
            method = ownerInfo.methods.get(name + desc);
        } else if (ownerIsInterface) {
            method = findInterfaceMethod(ownerInfo, name, desc);
        } else {
            method = findClassMethod(ownerInfo, name, desc);
        }
        if (method == null) {
            String unknown = name.startsWith("<") ? null : missingSupertype(ownerInfo);
            report(unknown == null ? "missing-method" : "unresolved-method", target
                    + (unknown == null ? "" : " (missing supertype " + unknown + ")"), referrer);
            return;
        }
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        boolean wantsStatic = opcode == Opcodes.INVOKESTATIC;
        if (isStatic != wantsStatic) {
            report("static-mismatch", target + " invoked with " + opcodeName(opcode), referrer);
        }
    }

    private void checkHandle(Handle handle, String referrer) {
        String owner = handle.getOwner();
        switch (handle.getTag()) {
            case Opcodes.H_GETFIELD -> checkField(Opcodes.GETFIELD, owner, handle.getName(),
                    handle.getDesc(), referrer);
            case Opcodes.H_GETSTATIC -> checkField(Opcodes.GETSTATIC, owner, handle.getName(),
                    handle.getDesc(), referrer);
            case Opcodes.H_PUTFIELD -> checkField(Opcodes.PUTFIELD, owner, handle.getName(),
                    handle.getDesc(), referrer);
            case Opcodes.H_PUTSTATIC -> checkField(Opcodes.PUTSTATIC, owner, handle.getName(),
                    handle.getDesc(), referrer);
            case Opcodes.H_INVOKEVIRTUAL -> checkMethod(Opcodes.INVOKEVIRTUAL, owner,
                    handle.getName(), handle.getDesc(), handle.isInterface(), referrer);
            case Opcodes.H_INVOKESTATIC -> checkMethod(Opcodes.INVOKESTATIC, owner,
                    handle.getName(), handle.getDesc(), handle.isInterface(), referrer);
            case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> checkMethod(
                    Opcodes.INVOKESPECIAL, owner, handle.getName(), handle.getDesc(),
                    handle.isInterface(), referrer);
            case Opcodes.H_INVOKEINTERFACE -> checkMethod(Opcodes.INVOKEINTERFACE, owner,
                    handle.getName(), handle.getDesc(), handle.isInterface(), referrer);
            default -> report("unknown-handle", owner + "." + handle.getName(), referrer);
        }
    }

    private void checkConstant(Object value, String referrer) {
        if (value instanceof Type type) {
            if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                requireClass(type.getInternalName(), referrer);
            }
        } else if (value instanceof Handle handle) {
            checkHandle(handle, referrer);
        } else if (value instanceof ConstantDynamic dynamic) {
            checkHandle(dynamic.getBootstrapMethod(), referrer);
            for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                checkConstant(dynamic.getBootstrapMethodArgument(index), referrer);
            }
        }
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.GETSTATIC -> "getstatic";
            case Opcodes.PUTSTATIC -> "putstatic";
            case Opcodes.GETFIELD -> "getfield";
            case Opcodes.PUTFIELD -> "putfield";
            case Opcodes.INVOKEVIRTUAL -> "invokevirtual";
            case Opcodes.INVOKESPECIAL -> "invokespecial";
            case Opcodes.INVOKESTATIC -> "invokestatic";
            case Opcodes.INVOKEINTERFACE -> "invokeinterface";
            default -> "opcode " + opcode;
        };
    }

    // -------------------------------------------------------------- visitors

    private final class SubjectVisitor extends ClassVisitor {
        private String className;

        SubjectVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            className = name;
            if (superName != null) requireClass(superName, name);
            if (interfaces != null) {
                for (String parent : interfaces) requireClass(parent, name);
            }
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            String referrer = className + "." + name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitFieldInsn(int opcode, String owner, String fieldName,
                        String fieldDescriptor) {
                    checkField(opcode, owner, fieldName, fieldDescriptor, referrer);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String methodName,
                        String methodDescriptor, boolean isInterface) {
                    checkMethod(opcode, owner, methodName, methodDescriptor, isInterface, referrer);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    ClassInfo info = requireClass(type, referrer);
                    if (opcode == Opcodes.NEW && info != null && (info.isInterface()
                            || (info.access & Opcodes.ACC_ABSTRACT) != 0)) {
                        report("abstract-new", type, referrer);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    checkConstant(value, referrer);
                }

                @Override
                public void visitMultiANewArrayInsn(String arrayDescriptor, int dimensions) {
                    requireClass(arrayDescriptor, referrer);
                }

                @Override
                public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                        Handle bootstrap, Object... arguments) {
                    checkHandle(bootstrap, referrer);
                    for (Object argument : arguments) checkConstant(argument, referrer);
                }

                @Override
                public void visitTryCatchBlock(org.objectweb.asm.Label start,
                        org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String type) {
                    if (type != null) requireClass(type, referrer);
                }
            };
        }
    }

    private static final class InfoVisitor extends ClassVisitor {
        private final ClassInfo info;

        InfoVisitor(ClassInfo info) {
            super(Opcodes.ASM9);
            this.info = info;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            info.name = name;
            info.access = access;
            info.superName = superName;
            if (interfaces != null) info.interfaces.addAll(List.of(interfaces));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                String signature, Object value) {
            info.fields.put(name + ":" + descriptor, new Member(access));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            Member member = new Member(access);
            info.methods.put(name + descriptor, member);
            info.methodsByName.putIfAbsent(name, member);
            return null;
        }
    }

    // ----------------------------------------------------------------- model

    private static final class ClassInfo {
        String name;
        int access;
        String superName;
        final List<String> interfaces = new ArrayList<>();
        final Map<String, Member> fields = new HashMap<>();
        final Map<String, Member> methods = new HashMap<>();
        final Map<String, Member> methodsByName = new HashMap<>();

        boolean isInterface() {
            return (access & Opcodes.ACC_INTERFACE) != 0;
        }

        /** JVMS 2.9.3: MethodHandle/VarHandle native varargs methods accept any descriptor. */
        boolean signaturePolymorphic(String methodName) {
            if (!name.equals("java/lang/invoke/MethodHandle")
                    && !name.equals("java/lang/invoke/VarHandle")) {
                return false;
            }
            Member member = methodsByName.get(methodName);
            return member != null && (member.access & Opcodes.ACC_NATIVE) != 0
                    && (member.access & Opcodes.ACC_VARARGS) != 0;
        }
    }

    private record Member(int access) {
    }

    private static final class Issue {
        final String key;
        int count;
        final List<String> referrers = new ArrayList<>();

        Issue(String key) {
            this.key = key;
        }
    }

    private interface Root extends AutoCloseable {
        List<String> classNames() throws IOException;

        byte[] read(String internalName);

        @Override
        void close() throws IOException;

        static Root open(Path path) throws IOException {
            if (Files.isDirectory(path)) return new DirectoryRoot(path);
            if (Files.isRegularFile(path)) return new JarRoot(new ZipFile(path.toFile()));
            throw new IOException("class root does not exist: " + path);
        }
    }

    private record JarRoot(ZipFile jar) implements Root {
        @Override
        public List<String> classNames() {
            List<String> names = new ArrayList<>();
            jar.stream().map(ZipEntry::getName)
                    .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/")
                            && !name.endsWith("module-info.class")
                            && !name.endsWith("package-info.class"))
                    .sorted()
                    .forEach(name -> names.add(name.substring(0, name.length() - 6)));
            return names;
        }

        @Override
        public byte[] read(String internalName) {
            ZipEntry entry = jar.getEntry(internalName + ".class");
            if (entry == null) return null;
            try (InputStream stream = jar.getInputStream(entry)) {
                return stream.readAllBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    private record DirectoryRoot(Path directory) implements Root {
        @Override
        public List<String> classNames() throws IOException {
            try (Stream<Path> files = Files.walk(directory)) {
                return files.filter(file -> file.toString().endsWith(".class"))
                        .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                        .filter(name -> !name.startsWith("META-INF/")
                                && !name.endsWith("module-info.class")
                                && !name.endsWith("package-info.class"))
                        .map(name -> name.substring(0, name.length() - 6))
                        .sorted()
                        .toList();
            }
        }

        @Override
        public byte[] read(String internalName) {
            Path file = directory.resolve(internalName + ".class");
            try {
                return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @Override
        public void close() {
        }
    }

    private LinkGapScanner() {
    }
}
