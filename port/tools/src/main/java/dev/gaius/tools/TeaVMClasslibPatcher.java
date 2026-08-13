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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
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
                        "(Ljava/lang/String;)Lorg/teavm/classlib/java/net/TURL;"),
                constantFalseInstance("desiredAssertionStatus", "()Z")
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
                instanceDelegate("getState",
                        "()Lorg/teavm/classlib/java/lang/TThread$State;",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "threadState",
                        "(Lorg/teavm/classlib/java/lang/TThread;)"
                                + "Lorg/teavm/classlib/java/lang/TThread$State;"),
                staticDelegate("sleep", "(Ljava/time/Duration;)V",
                        "org/teavm/classlib/java/lang/TModernRuntimeSupport",
                        "sleep"),
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
                        "from"),
                instanceDelegate("toInstant", "()Ljava/time/Instant;",
                        "org/teavm/classlib/java/util/TDateModernSupport",
                        "toInstant",
                        "(Lorg/teavm/classlib/java/util/TDate;)Ljava/time/Instant;")
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
                        "unmodifiableSortedMap"),
                staticDelegate("unmodifiableSequencedSet",
                        "(Ljava/util/SequencedSet;)Ljava/util/SequencedSet;",
                        "org/teavm/classlib/java/util/TCollectionsModernSupport",
                        "unmodifiableSequencedSet")
        });
        patches.put("org/teavm/classlib/java/util/TSpliterators", new MethodSpec[] {
                staticDelegate("emptySpliterator",
                        "()Lorg/teavm/classlib/java/util/TSpliterator;",
                        "org/teavm/classlib/java/util/TCollectionsModernSupport",
                        "emptySpliterator"),
                staticDelegate("iterator",
                        "(Lorg/teavm/classlib/java/util/TSpliterator;)"
                                + "Ljava/util/Iterator;",
                        "org/teavm/classlib/java/util/TSpliteratorsModernSupport",
                        "iterator"),
                staticDelegate("iterator",
                        "(Lorg/teavm/classlib/java/util/TSpliterator$OfInt;)"
                                + "Ljava/util/PrimitiveIterator$OfInt;",
                        "org/teavm/classlib/java/util/TSpliteratorsModernSupport",
                        "iterator"),
                staticDelegate("spliterator",
                        "([DIII)Lorg/teavm/classlib/java/util/TSpliterator$OfDouble;",
                        "org/teavm/classlib/java/util/TSpliteratorsModernSupport",
                        "spliterator")
        });
        patches.put("org/teavm/classlib/java/util/TBase64", new MethodSpec[] {
                staticDelegate("getMimeEncoder",
                        "(I[B)Lorg/teavm/classlib/java/util/TBase64$Encoder;",
                        "org/teavm/classlib/java/util/TBase64ModernSupport",
                        "getMimeEncoder"),
                staticDelegate("getMimeDecoder",
                        "()Lorg/teavm/classlib/java/util/TBase64$Decoder;",
                        "org/teavm/classlib/java/util/TBase64",
                        "getDecoder")
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
        patches.put("org/teavm/classlib/java/util/zip/TInflater", new MethodSpec[] {
                instanceDelegate("setInput", "(Ljava/nio/ByteBuffer;)V",
                        "org/teavm/classlib/java/util/zip/TZipModernSupport",
                        "setInput",
                        "(Lorg/teavm/classlib/java/util/zip/TInflater;Ljava/nio/ByteBuffer;)V"),
                instanceDelegate("inflate", "(Ljava/nio/ByteBuffer;)I",
                        "org/teavm/classlib/java/util/zip/TZipModernSupport",
                        "inflate",
                        "(Lorg/teavm/classlib/java/util/zip/TInflater;Ljava/nio/ByteBuffer;)I")
        });

        for (Map.Entry<String, MethodSpec[]> patch : patches.entrySet()) {
            patchClass(jar, root, patch.getKey(), patch.getValue());
        }
        patchThrowableGetSuppressed(jar, root);
        patchDefaultFileSystemProviderStreams(jar, root);
        patchZipFileRawInflaterPadding(jar, root);
    }

    private static void patchDefaultFileSystemProviderStreams(String jarPath, Path root) throws IOException {
        String className = "org/teavm/classlib/java/nio/file/impl/TDefaultFileSystemProvider";
        String defaultPathClass = "org/teavm/classlib/java/nio/file/impl/TDefaultPath";
        ClassNode node = readClass(jarPath, className);
        patchDefaultFileSystemProviderInputStream(node, defaultPathClass);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("newOutputStream")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "[Lorg/teavm/classlib/java/nio/file/TOpenOption;)Ljava/io/OutputStream;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TDefaultFileSystemProvider.newOutputStream was not found"));

        int defaultPathLocal = -1;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.CHECKCAST
                    && typeInsn.desc.equals(defaultPathClass)) {
                AbstractInsnNode next = insn.getNext();
                while (next != null && next.getOpcode() < 0) {
                    next = next.getNext();
                }
                if (next instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ASTORE) {
                    defaultPathLocal = varInsn.var;
                    break;
                }
            }
        }
        if (defaultPathLocal < 0) {
            throw new IllegalStateException(
                    "TDefaultFileSystemProvider.newOutputStream defaultPath local was not found");
        }
        insertMaterializeForOpen(method, defaultPathClass, defaultPathLocal, "newOutputStream");

        int truncateLocal = 6;
        boolean truncateLocalUsed = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode varInsn
                    && varInsn.getOpcode() == Opcodes.ILOAD
                    && varInsn.var == truncateLocal) {
                truncateLocalUsed = true;
                break;
            }
        }
        if (!truncateLocalUsed) {
            throw new IllegalStateException(
                    "TDefaultFileSystemProvider.newOutputStream truncate local was not found");
        }

        boolean patched = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("org/teavm/classlib/java/io/TFileOutputStream")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Lorg/teavm/runtime/fs/VirtualFileAccessor;)V")) {
                continue;
            }
            AbstractInsnNode accessorLoad = call.getPrevious();
            while (accessorLoad != null && accessorLoad.getOpcode() < 0) {
                accessorLoad = accessorLoad.getPrevious();
            }
            if (!(accessorLoad instanceof VarInsnNode varInsn) || varInsn.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException(
                        "TDefaultFileSystemProvider.newOutputStream TFileOutputStream accessor load was not found");
            }
            AbstractInsnNode duplicate = previousExecutable(accessorLoad);
            AbstractInsnNode streamAllocation = previousExecutable(duplicate);
            if (duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !(streamAllocation instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals("org/teavm/classlib/java/io/TFileOutputStream")) {
                throw new IllegalStateException(
                        "TDefaultFileSystemProvider.newOutputStream TFileOutputStream allocation was not found");
            }
            InsnList pathLoad = new InsnList();
            pathLoad.add(new VarInsnNode(Opcodes.ALOAD, defaultPathLocal));
            pathLoad.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    defaultPathClass,
                    "pathString",
                    "Ljava/lang/String;"));
            method.instructions.insertBefore(accessorLoad, pathLoad);
            method.instructions.insert(accessorLoad, new VarInsnNode(Opcodes.ILOAD, truncateLocal));
            call.desc = "(Ljava/lang/String;Lorg/teavm/runtime/fs/VirtualFileAccessor;Z)V";
            method.maxStack = Math.max(method.maxStack, 6);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException(
                    "TDefaultFileSystemProvider.newOutputStream TFileOutputStream constructor call was not found");
        }
        patchDefaultFileSystemProviderDelete(node, defaultPathClass);
        patchDefaultFileSystemProviderCopy(node, defaultPathClass);
        patchDefaultFileSystemProviderMove(node, defaultPathClass);
        writeClass(root, className, node);
    }

    private static void patchDefaultFileSystemProviderInputStream(
            ClassNode node, String defaultPathClass) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("newInputStream")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "[Lorg/teavm/classlib/java/nio/file/TOpenOption;)Ljava/io/InputStream;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "TDefaultFileSystemProvider.newInputStream was not found"));
        int defaultPathLocal = findDefaultPathLocal(method, defaultPathClass, "newInputStream");
        insertMaterializeForOpen(method, defaultPathClass, defaultPathLocal, "newInputStream");

        boolean patched = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("org/teavm/classlib/java/io/TFileInputStream")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Lorg/teavm/runtime/fs/VirtualFileAccessor;)V")) {
                continue;
            }
            AbstractInsnNode accessorLoad = previousExecutable(call);
            if (!(accessorLoad instanceof VarInsnNode varInsn)
                    || varInsn.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException(
                        "TDefaultFileSystemProvider.newInputStream accessor load was not found");
            }
            InsnList pathLoad = new InsnList();
            pathLoad.add(new VarInsnNode(Opcodes.ALOAD, defaultPathLocal));
            pathLoad.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    defaultPathClass,
                    "pathString",
                    "Ljava/lang/String;"));
            method.instructions.insertBefore(accessorLoad, pathLoad);
            call.desc = "(Ljava/lang/String;Lorg/teavm/runtime/fs/VirtualFileAccessor;)V";
            method.maxStack = Math.max(method.maxStack, 5);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException(
                    "TDefaultFileSystemProvider.newInputStream TFileInputStream constructor call was not found");
        }
    }

    private static int findDefaultPathLocal(
            MethodNode method, String defaultPathClass, String operation) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof TypeInsnNode typeInsn
                    && typeInsn.getOpcode() == Opcodes.CHECKCAST
                    && typeInsn.desc.equals(defaultPathClass)) {
                AbstractInsnNode next = nextExecutable(insn);
                if (next instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ASTORE) {
                    return varInsn.var;
                }
            }
        }
        throw new IllegalStateException(
                "TDefaultFileSystemProvider." + operation + " defaultPath local was not found");
    }

    private static void insertMaterializeForOpen(
            MethodNode method, String defaultPathClass, int defaultPathLocal, String operation) {
        AbstractInsnNode pathStore = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof TypeInsnNode typeInsn)
                    || typeInsn.getOpcode() != Opcodes.CHECKCAST
                    || !typeInsn.desc.equals(defaultPathClass)) {
                continue;
            }
            AbstractInsnNode next = nextExecutable(insn);
            if (next instanceof VarInsnNode varInsn
                    && varInsn.getOpcode() == Opcodes.ASTORE
                    && varInsn.var == defaultPathLocal) {
                pathStore = next;
                break;
            }
        }
        if (pathStore == null) {
            throw new IllegalStateException(
                    "TDefaultFileSystemProvider." + operation + " path store was not found");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, defaultPathLocal));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                defaultPathClass,
                "pathString",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "materializeForOpen",
                "(Ljava/lang/String;)V",
                false));
        method.instructions.insert(pathStore, code);
        method.maxStack = Math.max(method.maxStack, 5);
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn == null ? null : insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode insn) {
        AbstractInsnNode next = insn == null ? null : insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static void patchZipFileRawInflaterPadding(String jarPath, Path root) throws IOException {
        String className = "org/teavm/classlib/java/util/zip/TZipFile";
        String streamClass = className + "$RAFStream";
        ClassNode node = readClass(jarPath, className);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("getInputStream")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/util/zip/TZipEntry;)"
                                + "Ljava/io/InputStream;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TZipFile.getInputStream was not found"));

        boolean patched = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(className + "$ZipInflaterInputStream")) {
                continue;
            }
            AbstractInsnNode duplicate = nextExecutable(allocation);
            AbstractInsnNode streamLoad = nextExecutable(duplicate);
            if (duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !(streamLoad instanceof VarInsnNode varInsn)
                    || varInsn.getOpcode() != Opcodes.ALOAD) {
                throw new IllegalStateException("TZipFile.getInputStream RAFStream load was not found");
            }
            InsnList padding = new InsnList();
            padding.add(new VarInsnNode(Opcodes.ALOAD, varInsn.var));
            padding.add(new VarInsnNode(Opcodes.ALOAD, varInsn.var));
            padding.add(new FieldInsnNode(Opcodes.GETFIELD, streamClass, "mLength", "J"));
            padding.add(new InsnNode(Opcodes.LCONST_1));
            padding.add(new InsnNode(Opcodes.LADD));
            padding.add(new FieldInsnNode(Opcodes.PUTFIELD, streamClass, "mLength", "J"));
            method.instructions.insertBefore(allocation, padding);
            method.maxStack = Math.max(method.maxStack, 5);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("TZipFile.getInputStream raw inflater allocation was not found");
        }

        // ZIP permits the local-header name to differ from the central-directory name. TeaVM
        // used the central entry's nameLen when finding the compressed bytes, which shifted the
        // stream for server packs that intentionally use a shorter local name.
        MethodInsnNode rafConstructor = null;
        VarInsnNode streamStore = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(streamClass)
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/io/RandomAccessFile;J)V")) {
                continue;
            }
            LdcInsnNode offsetConstant = null;
            boolean streamAllocationFound = false;
            for (AbstractInsnNode cursor = previousExecutable(call);
                    cursor != null;
                    cursor = previousExecutable(cursor)) {
                if (cursor instanceof LdcInsnNode constant
                        && constant.cst instanceof Long value
                        && value == 28L) {
                    offsetConstant = constant;
                }
                if (cursor instanceof TypeInsnNode allocation
                        && allocation.getOpcode() == Opcodes.NEW
                        && allocation.desc.equals(streamClass)) {
                    streamAllocationFound = true;
                    break;
                }
            }
            if (!streamAllocationFound || offsetConstant == null) {
                continue;
            }
            AbstractInsnNode stored = nextExecutable(call);
            if (!(stored instanceof VarInsnNode candidate)
                    || candidate.getOpcode() != Opcodes.ASTORE) {
                throw new IllegalStateException("TZipFile.getInputStream RAFStream store was not found");
            }
            offsetConstant.cst = 26L;
            rafConstructor = call;
            streamStore = candidate;
            break;
        }
        if (rafConstructor == null || streamStore == null) {
            throw new IllegalStateException("TZipFile.getInputStream local-header offset was not found");
        }

        int localNameLength = method.maxLocals++;
        InsnList readLocalNameLength = new InsnList();
        readLocalNameLength.add(new VarInsnNode(Opcodes.ALOAD, 0));
        readLocalNameLength.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                className,
                "ler",
                "Lorg/teavm/classlib/java/util/zip/TZipEntry$LittleEndianReader;"));
        readLocalNameLength.add(new VarInsnNode(Opcodes.ALOAD, streamStore.var));
        readLocalNameLength.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "org/teavm/classlib/java/util/zip/TZipEntry$LittleEndianReader",
                "readShortLE",
                "(Ljava/io/InputStream;)I",
                false));
        readLocalNameLength.add(new VarInsnNode(Opcodes.ISTORE, localNameLength));
        method.instructions.insert(streamStore, readLocalNameLength);

        boolean localNameApplied = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals("org/teavm/classlib/java/util/zip/TZipEntry")
                    || !field.name.equals("nameLen")
                    || !field.desc.equals("I")) {
                continue;
            }
            AbstractInsnNode entryLoad = previousExecutable(field);
            if (!(entryLoad instanceof VarInsnNode load)
                    || load.getOpcode() != Opcodes.ALOAD
                    || load.var != 1) {
                throw new IllegalStateException("TZipFile.getInputStream central nameLen load was not found");
            }
            method.instructions.insertBefore(entryLoad,
                    new VarInsnNode(Opcodes.ILOAD, localNameLength));
            method.instructions.remove(field);
            method.instructions.remove(entryLoad);
            localNameApplied = true;
            break;
        }
        if (!localNameApplied) {
            throw new IllegalStateException("TZipFile.getInputStream local nameLen replacement was not found");
        }
        method.maxStack = Math.max(method.maxStack, 5);
        writeClass(root, className, node);
    }

    private static void patchDefaultFileSystemProviderDelete(ClassNode node, String defaultPathClass) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("delete")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/nio/file/TPath;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TDefaultFileSystemProvider.delete was not found"));
        AbstractInsnNode lastReturn = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                lastReturn = insn;
            }
        }
        if (lastReturn == null) {
            throw new IllegalStateException("TDefaultFileSystemProvider.delete success return was not found");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                defaultPathClass,
                "pathString",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "syncDelete",
                "(Ljava/lang/String;)V",
                false));
        method.instructions.insertBefore(lastReturn, code);
        method.maxStack = Math.max(method.maxStack, 5);
    }

    private static void patchDefaultFileSystemProviderCopy(ClassNode node, String defaultPathClass) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("copy")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "[Lorg/teavm/classlib/java/nio/file/TCopyOption;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TDefaultFileSystemProvider.copy was not found"));
        AbstractInsnNode lastReturn = null;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                lastReturn = insn;
            }
        }
        if (lastReturn == null) {
            throw new IllegalStateException("TDefaultFileSystemProvider.copy success return was not found");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                defaultPathClass,
                "pathString",
                "Ljava/lang/String;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserFilePersistence",
                "syncFile",
                "(Ljava/lang/String;)Z",
                false));
        code.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(lastReturn, code);
        method.maxStack = Math.max(method.maxStack, 5);
    }

    private static void patchDefaultFileSystemProviderMove(ClassNode node, String defaultPathClass) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("move")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "Lorg/teavm/classlib/java/nio/file/TPath;"
                                + "[Lorg/teavm/classlib/java/nio/file/TCopyOption;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TDefaultFileSystemProvider.move was not found"));
        insertMaterializeForOpen(method, defaultPathClass, 5, "move");
        boolean patched = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !call.owner.equals("org/teavm/runtime/fs/VirtualFile")
                    || !call.name.equals("adopt")
                    || !call.desc.equals("(Lorg/teavm/runtime/fs/VirtualFile;Ljava/lang/String;)Z")) {
                continue;
            }
            AbstractInsnNode pop = nextOpcodeInsn(call);
            if (pop == null || pop.getOpcode() != Opcodes.POP) {
                throw new IllegalStateException("TDefaultFileSystemProvider.move adopt POP was not found");
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 5));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    defaultPathClass,
                    "pathString",
                    "Ljava/lang/String;"));
            code.add(new VarInsnNode(Opcodes.ALOAD, 6));
            code.add(new FieldInsnNode(
                    Opcodes.GETFIELD,
                    defaultPathClass,
                    "pathString",
                    "Ljava/lang/String;"));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserFilePersistence",
                    "syncMove",
                    "(Ljava/lang/String;Ljava/lang/String;)V",
                    false));
            method.instructions.insert(pop, code);
            method.maxStack = Math.max(method.maxStack, 5);
            patched = true;
        }
        if (!patched) {
            throw new IllegalStateException("TDefaultFileSystemProvider.move adopt call was not found");
        }
    }

    private static AbstractInsnNode nextOpcodeInsn(AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static void patchThrowableGetSuppressed(String jarPath, Path root) throws IOException {
        String className = "org/teavm/classlib/java/lang/TThrowable";
        ClassNode node = readClass(jarPath, className);
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("getSuppressed0")
                        && candidate.desc.equals("()[Lorg/teavm/classlib/java/lang/TThrowable;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TThrowable.getSuppressed0 was not found"));

        LabelNode notNull = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        code.add(new InsnNode(Opcodes.POP));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, className));
        code.add(new InsnNode(Opcodes.ARETURN));
        code.add(notNull);
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/teavm/classlib/java/util/TArrays",
                "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new InsnNode(Opcodes.ARETURN));
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 2;
        method.maxLocals = 1;

        patchThrowableAddSuppressed(node, className);
        writeClass(root, className, node);
    }

    private static void patchThrowableAddSuppressed(ClassNode node, String className) {
        MethodNode method = node.methods.stream()
                .filter(candidate -> candidate.name.equals("addSuppressed")
                        && candidate.desc.equals("(Lorg/teavm/classlib/java/lang/TThrowable;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("TThrowable.addSuppressed was not found"));
        LabelNode enabled = new LabelNode();
        LabelNode hasSuppressed = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, className, "suppressionEnabled", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, enabled));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(enabled);

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, hasSuppressed));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, className));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new InsnNode(Opcodes.RETURN));

        code.add(hasSuppressed);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "org/teavm/classlib/java/util/TArrays",
                "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(
                Opcodes.CHECKCAST,
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                className,
                "suppressed",
                "[Lorg/teavm/classlib/java/lang/TThrowable;"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new InsnNode(Opcodes.RETURN));
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.maxStack = 5;
        method.maxLocals = 2;
    }

    private static void patchClass(
            String jarPath, Path root, String className, MethodSpec[] methods) throws IOException {
        ClassNode node = readClass(jarPath, className);
        for (MethodSpec spec : methods) {
            boolean exists = node.methods.stream()
                    .anyMatch(method -> method.name.equals(spec.name) && method.desc.equals(spec.desc));
            if (exists && spec.name.equals("desiredAssertionStatus") && spec.desc.equals("()Z")) {
                node.methods.removeIf(method -> method.name.equals(spec.name) && method.desc.equals(spec.desc));
                node.methods.add(spec.create());
            } else if (!exists) {
                node.methods.add(spec.create());
            }
        }
        writeClass(root, className, node);
    }

    private static ClassNode readClass(String jarPath, String className) throws IOException {
        byte[] input;
        try (ZipFile jar = new ZipFile(jarPath)) {
            try (var stream = jar.getInputStream(jar.getEntry(className + ".class"))) {
                input = stream.readAllBytes();
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        return node;
    }

    private static void writeClass(Path root, String className, ClassNode node) throws IOException {
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
