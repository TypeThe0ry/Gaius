package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Minecraft 26.3 ui patches, owned by work package P8.
 *
 * <ul>
 *   <li>{@link #patchClientLevelBreakingSoundVolume}: the browser mining hit sound volume
 *       ({@code (volume + 1) / 8} to {@code / 4}). 26.3 moved it from
 *       {@code MultiPlayerGameMode.continueDestroyBlock} (where MinecraftClientPatcher changes it
 *       on 26.2, and registers the patch as dropped on 26.3) to
 *       {@code ClientLevel.playBreakingSound}, which plays both the first hit and the server's
 *       periodic level event 2020. This reads the ClientLevel that MinecraftClientPatcher's
 *       patchClientLevelBrowserBlockBreakEffects already wrote, so the two patches compose.</li>
 *   <li>{@link #patchVanillaPackResourcesSingleLayer}: 26.3's VanillaPackResourcesBuilder builds
 *       one "full" FixedPathPackResources plus one per layer (the client pushes a jar layer and an
 *       asset-index layer). The Gaius FixedPathPackResources override serves the whole validated
 *       browser asset archive and ignores the builder's paths, so every layer would expose the
 *       complete vanilla pack again: duplicated resource stacks (fonts, sounds, languages,
 *       atlases) and a resource list parsed once per layer. The browser vanilla pack keeps
 *       26.2's shape instead: {@code build} returns the full resources as the only layer.</li>
 * </ul>
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it. Every patch is wrapped in
 * {@code PatchRegistry.run("UiPatches263.<method>", () -> ...)}.
 */
public final class UiPatches263 {
    static final String CLIENT_LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String VANILLA_PACK_BUILDER = "net/minecraft/server/packs/VanillaPackResourcesBuilder";
    static final String VANILLA_PACK = "net/minecraft/server/packs/VanillaPackResources";
    static final String FIXED_PATH_PACK = "net/minecraft/server/packs/FixedPathPackResources";
    static final String FIXED_PATH_BUILDER = FIXED_PATH_PACK + "$Builder";
    static final String PACK_LOCATION = "net/minecraft/server/packs/PackLocationInfo";

    private UiPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        PatchRegistry.run("UiPatches263.patchClientLevelBreakingSoundVolume",
                () -> patchClientLevelBreakingSoundVolume(jar, root));
        PatchRegistry.run("UiPatches263.patchVanillaPackResourcesSingleLayer",
                () -> patchVanillaPackResourcesSingleLayer(jar, root));
        System.out.println("UiPatches263: ClientLevel.playBreakingSound hit volume /8 -> /4;"
                + " VanillaPackResourcesBuilder.build uses the full resources as the only layer");
    }

    /** {@code ClientLevel.playBreakingSound}: {@code ldc 8.0f; fdiv} after getHitSound becomes 4.0f. */
    static void patchClientLevelBreakingSoundVolume(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, CLIENT_LEVEL);
        MethodNode method = find(node, "playBreakingSound",
                "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)V");
        boolean hitSound = false;
        int divisors = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.owner.equals("net/minecraft/world/level/block/SoundType")
                    && call.name.equals("getHitSound")
                    && call.desc.equals("()Lnet/minecraft/sounds/SoundEvent;")) {
                hitSound = true;
                continue;
            }
            if (hitSound
                    && instruction instanceof LdcInsnNode constant
                    && Float.valueOf(8.0f).equals(constant.cst)
                    && nextOpcode(instruction) != null
                    && nextOpcode(instruction).getOpcode() == Opcodes.FDIV) {
                constant.cst = Float.valueOf(4.0f);
                divisors++;
            }
        }
        if (!hitSound || divisors != 1) {
            throw new IllegalStateException("ClientLevel.playBreakingSound hit sound volume"
                    + " patch point was not found (getHitSound=" + hitSound + ", 8.0f divisors="
                    + divisors + ")");
        }
        write(node, root);
    }

    /**
     * {@code VanillaPackResourcesBuilder.build(PackLocationInfo)} becomes
     * {@code full = fullBuilder.build(location); return new VanillaPackResources(full, List.of(full));}.
     * The full builder receives every root and per-type path of every layer (vanilla
     * {@code forLastLayer} pushes to it and to the last layer), so the full resources are a
     * superset of each layer; only cross-layer resource stacking is given up. The browser needs
     * the Gaius FixedPathPackResources override in the jar; build-overlays.sh folds the client
     * overrides in before the patchers run, and quickcheck/profile_263_ui.py and
     * vanilla-pack-layers-263-smoke.mjs check the result (this patcher also runs on the plain
     * vanilla jar in patch-registry-smoke, so it does not insist on the override itself).
     */
    static void patchVanillaPackResourcesSingleLayer(String jar, Path root) throws IOException {
        ClassNode node = read(jar, root, VANILLA_PACK_BUILDER);
        FieldNode fullBuilder = null;
        boolean layers = false;
        for (FieldNode field : node.fields) {
            if (field.name.equals("fullBuilder") && field.desc.equals("L" + FIXED_PATH_BUILDER + ";")) {
                fullBuilder = field;
            }
            if (field.name.equals("layeredBuilders") && field.desc.equals("Ljava/util/List;")) {
                layers = true;
            }
        }
        if (fullBuilder == null || !layers) {
            throw new IllegalStateException(VANILLA_PACK_BUILDER
                    + " has no fullBuilder/layeredBuilders fields; the 26.3 layer shape changed");
        }
        MethodNode build = find(node, "build",
                "(L" + PACK_LOCATION + ";)L" + VANILLA_PACK + ";");
        int fullBuilds = 0;
        int packs = 0;
        for (AbstractInsnNode instruction = build.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(FIXED_PATH_BUILDER)
                    && call.name.equals("build")) {
                fullBuilds++;
            }
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(VANILLA_PACK)
                    && call.name.equals("<init>")
                    && call.desc.equals("(L" + FIXED_PATH_PACK + ";Ljava/util/List;)V")) {
                packs++;
            }
        }
        if (fullBuilds != 1 || packs != 1) {
            throw new IllegalStateException("VanillaPackResourcesBuilder.build shape changed: "
                    + fullBuilds + " FixedPathPackResources$Builder.build calls, " + packs
                    + " VanillaPackResources(FixedPathPackResources, List) constructions");
        }
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, VANILLA_PACK_BUILDER, fullBuilder.name,
                fullBuilder.desc));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FIXED_PATH_BUILDER, "build",
                "(L" + PACK_LOCATION + ";)L" + FIXED_PATH_PACK + ";", false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new TypeInsnNode(Opcodes.NEW, VANILLA_PACK));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/List", "of",
                "(Ljava/lang/Object;)Ljava/util/List;", true));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, VANILLA_PACK, "<init>",
                "(L" + FIXED_PATH_PACK + ";Ljava/util/List;)V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        build.instructions = code;
        build.tryCatchBlocks.clear();
        build.localVariables = null;
        build.visibleLocalVariableAnnotations = null;
        build.invisibleLocalVariableAnnotations = null;
        build.maxLocals = Math.max(build.maxLocals, 3);
        build.maxStack = Math.max(build.maxStack, 4);
        write(node, root);
    }

    /**
     * Reads {@code owner} as the chain has patched it so far: the class an earlier M263 domain
     * wrote under {@code root} first, then the jar (contract C2).
     */
    static ClassNode read(String jar, Path root, String owner) throws IOException {
        Path written = root.resolve(owner + ".class");
        if (Files.isRegularFile(written)) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(written)).accept(node, 0);
            return node;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            var entry = zip.getEntry(owner + ".class");
            if (entry == null) {
                throw new IOException("Missing class entry " + owner + ".class in " + jar);
            }
            ClassNode node = new ClassNode();
            try (var input = zip.getInputStream(entry)) {
                new ClassReader(input.readAllBytes()).accept(node, 0);
            }
            return node;
        }
    }

    static MethodNode find(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + desc + " was not found");
    }

    static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    static void write(ClassNode node, Path root) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = root.resolve(node.name + ".class");
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
