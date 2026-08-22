package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Replaces anonymous Gson TypeTokens whose generic superclass is unavailable in TeaVM. */
public final class GsonTypeTokenClientPatcher {
    private GsonTypeTokenClientPatcher() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[1]);

        ClassNode options = read(args[0], "net/minecraft/client/Options.class");
        replaceAnonymousTypeTokenConstruction(
                options,
                "net/minecraft/client/Options$1",
                "java/util/List",
                "java/lang/String");
        write(options, root.resolve("net/minecraft/client/Options.class"));

        ClassNode sounds = read(args[0], "net/minecraft/client/sounds/SoundManager.class");
        replaceAnonymousTypeTokenConstruction(
                sounds,
                "net/minecraft/client/sounds/SoundManager$1",
                "java/util/Map",
                "java/lang/String",
                "net/minecraft/client/resources/sounds/SoundEventRegistration");
        write(sounds, root.resolve("net/minecraft/client/sounds/SoundManager.class"));

        Path clientJar = Path.of(args[0]);
        // Resolve the configured version's library tree from the overlay jar name
        // (client-named-<version>-gaius.jar) so no version-specific paths are hard-coded.
        Path versionLibraries = workLibrariesFor(clientJar);
        Path gsonJar = findJar(versionLibraries,
                "com/google/code/gson/gson");
        GsonBrowserPatcher.main(new String[] {
                gsonJar.toString(),
                root.resolve("com/google/gson/reflect/TypeToken.class").toString()
        });

        Path guavaJar = findJar(versionLibraries,
                "com/google/guava/guava");
        Path dataFixerJar = findJar(versionLibraries,
                "com/mojang/datafixerupper");
        GuavaTypeTokenBrowserPatcher.main(new String[] {
                guavaJar.toString(), dataFixerJar.toString(), root.toString()
        });

        System.out.println("Patched browser Gson TypeToken initializers");
    }

    /**
     * Resolves port/work/<version>/libraries from the overlay client jar name.
     *
     * The historical overlay root is port/work/overlays, while isolated
     * profiles use port/work/overlays/<version>.  Prefer the candidate that
     * contains the version's datafixerupper dependency so the patcher never
     * accidentally treats an overlay directory as the vanilla library tree.
     */
    private static Path workLibrariesFor(Path clientJar) {
        String name = clientJar.getFileName().toString();
        String prefix = "client-named-";
        String suffix = "-gaius.jar";
        if (!name.startsWith(prefix) || !name.endsWith(suffix)) {
            throw new IllegalArgumentException("Unexpected overlay jar name: " + name);
        }
        String version = name.substring(prefix.length(), name.length() - suffix.length());
        Path overlayParent = clientJar.getParent().getParent();
        Path historical = overlayParent.resolve(version).resolve("libraries");
        Path isolated = overlayParent.getParent().resolve(version).resolve("libraries");
        if (Files.isDirectory(historical.resolve("com/mojang/datafixerupper"))) {
            return historical;
        }
        if (Files.isDirectory(isolated.resolve("com/mojang/datafixerupper"))) {
            return isolated;
        }
        return historical;
    }

    /** Resolves a library JAR under the version library tree without hard-coded versions. */
    private static Path findJar(Path overlayLibraries, String relativeDirectory)
            throws IOException {
        Path directory = overlayLibraries.resolve(relativeDirectory);
        if (!Files.isDirectory(directory)) {
            throw new IOException("Library directory is missing: " + directory);
        }
        try (var stream = Files.walk(directory)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "No JAR found under " + directory));
        }
    }

    private static void replaceAnonymousTypeTokenConstruction(
            ClassNode node, String anonymousOwner, String rawType, String... typeArguments) {
        MethodNode initializer = find(node, "<clinit>", "()V");
        int replacements = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof TypeInsnNode allocation)
                    || allocation.getOpcode() != Opcodes.NEW
                    || !allocation.desc.equals(anonymousOwner)) {
                continue;
            }
            AbstractInsnNode duplicate = nextRealInstruction(allocation);
            AbstractInsnNode constructor = nextRealInstruction(duplicate);
            if (duplicate == null
                    || duplicate.getOpcode() != Opcodes.DUP
                    || !(constructor instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(anonymousOwner)
                    || !call.name.equals("<init>")
                    || !call.desc.equals("()V")) {
                throw new IllegalStateException(
                        "Unexpected anonymous TypeToken construction in " + node.name);
            }

            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(Opcodes.ACONST_NULL));
            replacement.add(new LdcInsnNode(Type.getObjectType(rawType)));
            replacement.add(pushSmallInteger(typeArguments.length));
            replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/reflect/Type"));
            for (int i = 0; i < typeArguments.length; i++) {
                replacement.add(new InsnNode(Opcodes.DUP));
                replacement.add(pushSmallInteger(i));
                replacement.add(new LdcInsnNode(Type.getObjectType(typeArguments[i])));
                replacement.add(new InsnNode(Opcodes.AASTORE));
            }
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/google/gson/internal/GsonTypes",
                    "newParameterizedTypeWithOwner",
                    "(Ljava/lang/reflect/Type;Ljava/lang/Class;[Ljava/lang/reflect/Type;)"
                            + "Ljava/lang/reflect/ParameterizedType;",
                    false));
            replacement.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "com/google/gson/reflect/TypeToken",
                    "get",
                    "(Ljava/lang/reflect/Type;)Lcom/google/gson/reflect/TypeToken;",
                    false));
            initializer.instructions.insertBefore(allocation, replacement);
            initializer.instructions.remove(allocation);
            initializer.instructions.remove(duplicate);
            initializer.instructions.remove(constructor);
            replacements++;
        }
        if (replacements != 1) {
            throw new IllegalStateException(
                    "Expected one anonymous TypeToken construction in " + node.name
                            + ", got " + replacements);
        }
        initializer.maxStack = Math.max(initializer.maxStack, 6);
    }

    private static InsnNode pushSmallInteger(int value) {
        if (value < 0 || value > 5) {
            throw new IllegalArgumentException("Small integer is out of range: " + value);
        }
        return new InsnNode(Opcodes.ICONST_0 + value);
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction == null ? null : instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(name + descriptor + " was not found in " + node.name);
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " was not found");
            }
            ClassNode node = new ClassNode();
            try (var stream = jar.getInputStream(entry)) {
                new ClassReader(stream).accept(node, 0);
            }
            return node;
        }
    }

    private static void write(ClassNode node, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.write(output, writer.toByteArray());
    }
}
