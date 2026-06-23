package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Adds modern JDK methods missing from TeaVM 0.15's class library. */
public final class TeaVMClasslibPatcher {
    private TeaVMClasslibPatcher() {
    }

    public static void main(String[] args) throws IOException {
        String jar = args[0];
        Path root = Path.of(args[1]);
        Map<String, MethodSpec[]> patches = new LinkedHashMap<>();
        patches.put("org/teavm/classlib/java/lang/TCharacter", new MethodSpec[] {
                staticDelegate("toString", "(I)Ljava/lang/String;",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "characterToString"),
                staticDelegate("codePointOf", "(Ljava/lang/String;)I",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "codePointOf")
        });
        patches.put("org/teavm/classlib/java/lang/TInteger", new MethodSpec[] {
                staticDelegate("parseUnsignedInt", "(Ljava/lang/String;I)I",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "parseUnsignedInt")
        });
        patches.put("org/teavm/classlib/java/lang/TLong", new MethodSpec[] {
                staticDelegate("parseUnsignedLong", "(Ljava/lang/String;I)J",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "parseUnsignedLong")
        });
        patches.put("org/teavm/classlib/java/lang/TMath", new MethodSpec[] {
                staticDelegate("fma", "(FFF)F",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport", "fma"),
                staticDelegate("fma", "(DDD)D",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport", "fma")
        });
        patches.put("org/teavm/classlib/java/lang/TClass", new MethodSpec[] {
                instanceDelegate("isAnonymousClass", "()Z",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "isAnonymousClass",
                        "(Lorg/teavm/classlib/java/lang/TClass;)Z"),
                instanceDelegate("getGenericSuperclass",
                        "()Lorg/teavm/classlib/java/lang/reflect/TType;",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "genericSuperclass",
                        "(Lorg/teavm/classlib/java/lang/TClass;)"
                                + "Lorg/teavm/classlib/java/lang/reflect/TType;"),
                instanceDelegate("getGenericInterfaces",
                        "()[Lorg/teavm/classlib/java/lang/reflect/TType;",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "genericInterfaces",
                        "(Lorg/teavm/classlib/java/lang/TClass;)"
                                + "[Lorg/teavm/classlib/java/lang/reflect/TType;"),
                constantNull("getSigners", "()[Ljava/lang/Object;"),
                constantNull("getResource",
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/net/TURL;")
        });
        patches.put("org/teavm/classlib/java/lang/TPackage", new MethodSpec[] {
                constantNull("getSpecificationVersion", "()Ljava/lang/String;"),
                constantNull("getImplementationVersion", "()Ljava/lang/String;")
        });
        patches.put("org/teavm/classlib/java/lang/TRuntime", new MethodSpec[] {
                noOpInstance("addShutdownHook",
                        "(Lorg/teavm/classlib/java/lang/TThread;)V"),
                constantFalseInstance("removeShutdownHook",
                        "(Lorg/teavm/classlib/java/lang/TThread;)Z"),
                instanceDelegate("maxMemory", "()J",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "maxMemory",
                        "(Lorg/teavm/classlib/java/lang/TRuntime;)J")
        });
        patches.put("org/teavm/classlib/java/lang/TSystem", new MethodSpec[] {
                noOpStatic("exit", "(I)V"),
                staticDelegate("getenv",
                        "()Lorg/teavm/classlib/java/util/TMap;",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "getenv")
        });
        patches.put("org/teavm/classlib/java/lang/TThread", new MethodSpec[] {
                noOpStatic("dumpStack", "()V"),
                noOpInstance("setContextClassLoader",
                        "(Lorg/teavm/classlib/java/lang/TClassLoader;)V"),
                instanceDelegate("threadId", "()J",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "threadId",
                        "(Lorg/teavm/classlib/java/lang/TThread;)J"),
                threadGroupConstructor()
        });
        patches.put("org/teavm/classlib/java/lang/TClassLoader", new MethodSpec[] {
                instanceDelegate("loadClass",
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/lang/TClass;",
                        "org/teavm/classlib/java/lang/TClassLoaderModernSupport",
                        "loadClass",
                        "(Lorg/teavm/classlib/java/lang/TClassLoader;Ljava/lang/String;)"
                                + "Lorg/teavm/classlib/java/lang/TClass;"),
                instanceDelegate("getResources",
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/util/TEnumeration;",
                        "org/teavm/classlib/java/lang/TClassLoaderModernSupport",
                        "getResources",
                        "(Lorg/teavm/classlib/java/lang/TClassLoader;Ljava/lang/String;)"
                                + "Lorg/teavm/classlib/java/util/TEnumeration;"),
                constantNull("getResource",
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/net/TURL;")
        });
        patches.put("org/teavm/classlib/java/util/regex/TPattern", new MethodSpec[] {
                instanceDelegate("asPredicate", "()Ljava/util/function/Predicate;",
                        "org/teavm/classlib/java/util/regex/TPatternModernSupport",
                        "asPredicate",
                        "(Lorg/teavm/classlib/java/util/regex/TPattern;)"
                                + "Ljava/util/function/Predicate;")
        });
        patches.put("org/teavm/classlib/java/util/TLocale", new MethodSpec[] {
                staticDelegate("forLanguageTag",
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/util/TLocale;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "forLanguageTag"),
                instanceDelegate("getScript", "()Ljava/lang/String;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getScript",
                        "(Lorg/teavm/classlib/java/util/TLocale;)Ljava/lang/String;"),
                instanceDelegate("getExtension", "(C)Ljava/lang/String;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getExtension",
                        "(Lorg/teavm/classlib/java/util/TLocale;C)Ljava/lang/String;"),
                instanceDelegate("getExtensionKeys",
                        "()Lorg/teavm/classlib/java/util/TSet;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getExtensionKeys",
                        "(Lorg/teavm/classlib/java/util/TLocale;)"
                                + "Lorg/teavm/classlib/java/util/TSet;"),
                instanceDelegate("getUnicodeLocaleAttributes",
                        "()Lorg/teavm/classlib/java/util/TSet;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getUnicodeLocaleAttributes",
                        "(Lorg/teavm/classlib/java/util/TLocale;)"
                                + "Lorg/teavm/classlib/java/util/TSet;"),
                instanceDelegate("getUnicodeLocaleKeys",
                        "()Lorg/teavm/classlib/java/util/TSet;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getUnicodeLocaleKeys",
                        "(Lorg/teavm/classlib/java/util/TLocale;)"
                                + "Lorg/teavm/classlib/java/util/TSet;"),
                instanceDelegate("getUnicodeLocaleType", "(Ljava/lang/String;)Ljava/lang/String;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getUnicodeLocaleType",
                        "(Lorg/teavm/classlib/java/util/TLocale;Ljava/lang/String;)"
                                + "Ljava/lang/String;"),
                instanceDelegate("getISO3Country", "()Ljava/lang/String;",
                        "org/teavm/classlib/java/util/TLocaleModernSupport",
                        "getISO3Country",
                        "(Lorg/teavm/classlib/java/util/TLocale;)Ljava/lang/String;")
        });
        patches.put("org/teavm/classlib/java/util/TDate", new MethodSpec[] {
                staticDelegate("from",
                        "(Ljava/time/Instant;)Lorg/teavm/classlib/java/util/TDate;",
                        "org/teavm/classlib/java/util/TDateModernSupport",
                        "from")
        });
        patches.put("org/teavm/classlib/java/io/TFile", new MethodSpec[] {
                instanceDelegate("toPath",
                        "()Lorg/teavm/classlib/java/nio/file/TPath;",
                        "org/teavm/classlib/java/io/TFileModernSupport",
                        "toPath",
                        "(Lorg/teavm/classlib/java/io/TFile;)"
                                + "Lorg/teavm/classlib/java/nio/file/TPath;")
        });
        patches.put("org/teavm/classlib/java/util/TCollections", new MethodSpec[] {
                staticDelegate("unmodifiableSortedSet",
                        "(Lorg/teavm/classlib/java/util/TSortedSet;)"
                                + "Lorg/teavm/classlib/java/util/TSortedSet;",
                        "org/teavm/classlib/java/util/TCollectionsModernSupport",
                        "unmodifiableSortedSet"),
                staticDelegate("unmodifiableSortedMap",
                        "(Lorg/teavm/classlib/java/util/TSortedMap;)"
                                + "Lorg/teavm/classlib/java/util/TSortedMap;",
                        "org/teavm/classlib/java/util/TCollectionsModernSupport",
                        "unmodifiableSortedMap")
        });
        patches.put("org/teavm/classlib/java/util/TSpliterators", new MethodSpec[] {
                staticDelegate("emptySpliterator",
                        "()Lorg/teavm/classlib/java/util/TSpliterator;",
                        "org/teavm/classlib/java/util/TCollectionsModernSupport",
                        "emptySpliterator")
        });
        patches.put("org/teavm/classlib/java/io/TBufferedReader", new MethodSpec[] {
                instanceDelegate("transferTo",
                        "(Lorg/teavm/classlib/java/io/TWriter;)J",
                        "org/teavm/classlib/java/io/TReaderModernSupport",
                        "transferTo",
                        "(Lorg/teavm/classlib/java/io/TBufferedReader;"
                                + "Lorg/teavm/classlib/java/io/TWriter;)J")
        });
        patches.put("org/teavm/classlib/java/util/concurrent/TTimeUnit", new MethodSpec[] {
                instanceDelegate("convert", "(Ljava/time/Duration;)J",
                        "org/teavm/classlib/java/util/concurrent/TTimeUnitModernSupport",
                        "convert",
                        "(Lorg/teavm/classlib/java/util/concurrent/TTimeUnit;"
                                + "Ljava/time/Duration;)J")
        });
        patches.put("org/teavm/classlib/java/net/TURL", new MethodSpec[] {
                instanceDelegate("openConnection",
                        "(Lorg/teavm/classlib/java/net/TProxy;)"
                                + "Lorg/teavm/classlib/java/net/TURLConnection;",
                        "org/teavm/classlib/java/net/TUrlModernSupport",
                        "openConnection",
                        "(Lorg/teavm/classlib/java/net/TURL;"
                                + "Ljava/lang/Object;)"
                                + "Lorg/teavm/classlib/java/net/TURLConnection;")
        });
        patches.put("org/teavm/classlib/java/net/THttpURLConnection", new MethodSpec[] {
                instanceDelegate("getContentLengthLong", "()J",
                        "org/teavm/classlib/java/net/TUrlModernSupport",
                        "getContentLengthLong",
                        "(Lorg/teavm/classlib/java/net/THttpURLConnection;)J")
        });
        patches.put("org/teavm/classlib/java/nio/file/TFileSystem", new MethodSpec[] {
                instanceDelegate("getPathMatcher",
                        "(Ljava/lang/String;)"
                                + "Lorg/teavm/classlib/java/nio/file/TPathMatcher;",
                        "org/teavm/classlib/java/nio/file/TFileSystemModernSupport",
                        "getPathMatcher",
                        "(Lorg/teavm/classlib/java/nio/file/TFileSystem;Ljava/lang/String;)"
                                + "Lorg/teavm/classlib/java/nio/file/TPathMatcher;")
        });
        patches.put("org/teavm/classlib/java/nio/file/TFiles", new MethodSpec[] {
                staticDelegate("newByteChannel",
                        "(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "Lorg/teavm/classlib/java/util/TSet;"
                                + "[Lorg/teavm/classlib/java/nio/file/attribute/TFileAttribute;)"
                                + "Lorg/teavm/classlib/java/nio/channels/TSeekableByteChannel;",
                        "org/teavm/classlib/java/nio/file/TFilesModernSupport",
                        "newByteChannel"),
                staticDelegate("setLastModifiedTime",
                        "(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "Lorg/teavm/classlib/java/nio/file/attribute/TFileTime;)"
                                + "Lorg/teavm/classlib/java/nio/file/TPath;",
                        "org/teavm/classlib/java/nio/file/TFilesModernSupport",
                        "setLastModifiedTime"),
                staticDelegate("getFileStore",
                        "(Lorg/teavm/classlib/java/nio/file/TPath;)"
                                + "Lorg/teavm/classlib/java/nio/file/TFileStore;",
                        "org/teavm/classlib/java/nio/file/TFilesModernSupport",
                        "getFileStore")
        });
        patches.put("org/teavm/classlib/java/util/stream/TStreamSupport", new MethodSpec[] {
                staticDelegate("intStream",
                        "(Lorg/teavm/classlib/java/util/TSpliterator$OfInt;Z)"
                                + "Lorg/teavm/classlib/java/util/stream/TIntStream;",
                        "org/teavm/classlib/java/util/stream/TPrimitiveStreamSupport",
                        "intStream"),
                staticDelegate("longStream",
                        "(Lorg/teavm/classlib/java/util/TSpliterator$OfLong;Z)"
                                + "Lorg/teavm/classlib/java/util/stream/TLongStream;",
                        "org/teavm/classlib/java/util/stream/TPrimitiveStreamSupport",
                        "longStream")
        });

        for (Map.Entry<String, MethodSpec[]> patch : patches.entrySet()) {
            patchClass(jar, root, patch.getKey(), patch.getValue());
        }
    }

    private static void patchClass(
            String jarPath, Path root, String className, MethodSpec[] methods) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(jarPath)) {
            try (var stream = jar.getInputStream(jar.getEntry(className + ".class"))) {
                input = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        for (MethodSpec spec : methods) {
            boolean exists = node.methods.stream()
                    .anyMatch(method -> method.name.equals(spec.name) && method.desc.equals(spec.desc));
            if (!exists) {
                node.methods.add(spec.create());
            }
        }
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(className + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static MethodSpec staticDelegate(
            String name, String desc, String owner, String target) {
        return new MethodSpec(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, owner, target, desc);
    }

    private static MethodSpec instanceDelegate(
            String name, String desc, String owner, String target, String targetDesc) {
        return new MethodSpec(Opcodes.ACC_PUBLIC, name, desc, owner, target, targetDesc);
    }

    private static MethodSpec noOpStatic(String name, String desc) {
        return new MethodSpec(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null, null);
    }

    private static MethodSpec noOpInstance(String name, String desc) {
        return new MethodSpec(Opcodes.ACC_PUBLIC, name, desc, null, null, null);
    }

    private static MethodSpec constantFalseInstance(String name, String desc) {
        return new MethodSpec(Opcodes.ACC_PUBLIC, name, desc, null, "false", null);
    }

    private static MethodSpec constantNull(String name, String desc) {
        return new MethodSpec(Opcodes.ACC_PUBLIC, name, desc, null, "null", null);
    }

    private static MethodSpec threadGroupConstructor() {
        return new MethodSpec(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "(Lorg/teavm/classlib/java/lang/TThreadGroup;"
                        + "Lorg/teavm/classlib/java/lang/TRunnable;Ljava/lang/String;)V",
                null,
                "threadGroupConstructor",
                null);
    }

    private record MethodSpec(
            int access, String name, String desc, String owner, String target, String targetDesc) {
        MethodNode create() {
            MethodNode method = new MethodNode(access, name, desc, null, null);
            InsnList code = method.instructions;
            int local = 0;
            if ((access & Opcodes.ACC_STATIC) == 0) {
                if (owner != null) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                }
                local = 1;
            }
            if (owner == null) {
                if ("threadGroupConstructor".equals(target)) {
                    code.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    code.add(new VarInsnNode(Opcodes.ALOAD, 3));
                    code.add(new MethodInsnNode(
                            Opcodes.INVOKESPECIAL,
                            "org/teavm/classlib/java/lang/TThread",
                            "<init>",
                            "(Lorg/teavm/classlib/java/lang/TRunnable;Ljava/lang/String;)V",
                            false));
                    code.add(new InsnNode(Opcodes.RETURN));
                } else if ("null".equals(target)) {
                    code.add(new InsnNode(Opcodes.ACONST_NULL));
                    code.add(new InsnNode(Opcodes.ARETURN));
                } else if ("false".equals(target)) {
                    code.add(new InsnNode(Opcodes.ICONST_0));
                    code.add(new InsnNode(Opcodes.IRETURN));
                } else {
                    code.add(new InsnNode(Opcodes.RETURN));
                }
                method.maxStack = "threadGroupConstructor".equals(target) ? 3 : 1;
                method.maxLocals = Math.max(local, Type.getArgumentsAndReturnSizes(desc) >> 2);
                return method;
            }
            for (Type argument : Type.getArgumentTypes(desc)) {
                code.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
                local += argument.getSize();
            }
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC, owner, target, targetDesc, false));
            Type result = Type.getReturnType(desc);
            code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
            method.maxStack = Math.max(2, local);
            method.maxLocals = local;
            return method;
        }
    }
}
