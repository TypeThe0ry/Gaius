package dev.gaius.tools.m263;

import dev.gaius.tools.ModernSymbols;
import dev.gaius.tools.PatchRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Minecraft 26.3 worldgen patches, owned by work package P6 (migration plan D6).
 *
 * <p>26.3 merged NOISE, SURFACE and CARVERS into one TERRAIN step and compiles each density
 * graph once per RandomState into float samplers that evaluate whole volumes. The 26.2
 * performance patches for the old density runtime are registered as dropped by
 * MinecraftClientPatcher/Minecraft262BrowserPatcher; the cooperation and correctness patches
 * that have a 1:1 target were ported in place there. This class holds the 26.3-only parts:
 * <ul>
 *   <li>{@link #patchCarvingMaskDeepPulses}: carved blocks are now applied by walking a
 *       CarvingMask ({@code visit}/{@code visitSegment}) after all carvers ran, so the deep
 *       pulses of the 26.2 carver loop move there;</li>
 *   <li>{@link #patchMaterialRuleContextIntCounters}: int shadows of MaterialRuleContext's long
 *       update counters, read by the two lazy condition classes (TeaVM emulates long);</li>
 *   <li>{@link #verifyNonSuspendingWorldgenKernels}: the compiled density kernels, the noise
 *       synthesizers and the RandomState/DensityFunctionCompiler lock regions must never reach a
 *       cooperative pulse or scheduler call. Gaius' TReentrantLock only counts holds and does
 *       not exclude, so a task suspended inside compileLock or the buffer-pool lock would let
 *       another task corrupt the shared state; a pulse in a kernel would also turn every
 *       sampler into a TeaVM async method.</li>
 * </ul>
 *
 * <p>Contract C2: {@code Minecraft263BrowserPatcher} calls {@link #apply} at the tail of the
 * modern chain, after MinecraftClientPatcher, MinecraftChunkDrawTelemetryPatcher,
 * Minecraft262BrowserPatcher and MinecraftServerWorkerPatcher, in the order Render, Input,
 * Terrain, Worldgen, Server, Ui. {@code jar} is the chain's current client jar, {@code root} the
 * directory whose classes are merged back into it.
 *
 * <p>The guard also runs standalone on a finished client jar:
 * {@code java dev.gaius.tools.m263.WorldgenPatches263 --verify-non-suspending <jar>}.
 */
public final class WorldgenPatches263 {
    static final String CARVING_MASK = "net/minecraft/world/level/chunk/CarvingMask";
    static final String MATERIAL_RULE_CONTEXT =
            "net/minecraft/world/level/levelgen/material/MaterialRuleContext";
    static final String DEEP_CHECKPOINT = "dev/gaius/browser/BrowserWorldgenDeepCheckpoint";

    /** Counter fields added to MaterialRuleContext (incremented with lastUpdateXZ/lastUpdateY). */
    static final String BROWSER_UPDATE_XZ = "browserUpdateXZ";
    static final String BROWSER_UPDATE_Y = "browserUpdateY";

    /**
     * Owners whose methods suspend the calling task (TeaVM async) or hand control to the
     * cooperative worldgen scheduler. None of them may be called from a guarded class.
     */
    static final List<String> SUSPENDING_OWNERS = List.of(
            "dev/gaius/browser/BrowserWorldgenScheduler",
            DEEP_CHECKPOINT,
            "dev/gaius/browser/BrowserWorldgenDispatcherScheduler",
            "dev/gaius/browser/BrowserChunkGenerationYield",
            "dev/gaius/browser/BrowserFuturePump",
            "dev/gaius/browser/BrowserStartupScheduler",
            "dev/gaius/browser/BrowserRenderScheduler",
            "dev/gaius/browser/BrowserIntegratedServerMain",
            "dev/gaius/browser/BrowserCooperativeExecutor",
            "org/teavm/platform/Platform",
            "java/util/concurrent/locks/LockSupport");

    /** Package prefixes that are guarded as a whole. */
    static final List<String> GUARDED_PACKAGES = List.of(
            "net/minecraft/world/level/levelgen/densityfunction/",
            "net/minecraft/world/level/levelgen/synth/");

    /**
     * Single classes (with their nested classes) that are guarded as a whole: the Beardifier
     * density sampler, the NoiseChunk holder, and RandomState, whose buffer-pool lock regions
     * (acquire/release/garbageCollect) and sampler lookups must not suspend.
     */
    static final List<String> GUARDED_CLASSES = List.of(
            "net/minecraft/world/level/levelgen/Beardifier",
            "net/minecraft/world/level/levelgen/NoiseChunk",
            "net/minecraft/world/level/levelgen/RandomState");

    /** Guarded classes that must exist in a 26.3 jar, so the guard cannot pass vacuously. */
    static final List<String> REQUIRED_GUARDED_CLASSES = List.of(
            "net/minecraft/world/level/levelgen/densityfunction/DensityFunctionCompiler",
            "net/minecraft/world/level/levelgen/densityfunction/op/InterpolatedFunction$Sampler",
            "net/minecraft/world/level/levelgen/synth/PerlinNoise",
            "net/minecraft/world/level/levelgen/synth/NoiseStack",
            "net/minecraft/world/level/levelgen/Beardifier",
            "net/minecraft/world/level/levelgen/NoiseChunk",
            "net/minecraft/world/level/levelgen/RandomState");

    /** How many calls deep the transitive part of the guard follows calls out of guarded code. */
    static final int TRANSITIVE_DEPTH = 6;

    private WorldgenPatches263() {
    }

    public static void apply(String jar, Path root, ModernSymbols symbols) throws IOException {
        if (!symbols.hasEntry(CARVING_MASK + ".class")
                || !symbols.hasEntry(MATERIAL_RULE_CONTEXT + ".class")) {
            throw new IllegalStateException("WorldgenPatches263: " + jar
                    + " has no 26.3 CarvingMask/MaterialRuleContext (" + symbols.summary() + ")");
        }
        System.out.println("WorldgenPatches263: 26.3 worldgen patches and non-suspending guard (P6)");
        PatchRegistry.run("WorldgenPatches263.patchCarvingMaskDeepPulses",
                () -> patchCarvingMaskDeepPulses(jar, root));
        PatchRegistry.run("WorldgenPatches263.patchMaterialRuleContextIntCounters",
                () -> patchMaterialRuleContextIntCounters(jar, root));
        PatchRegistry.run("WorldgenPatches263.verifyNonSuspendingWorldgenKernels",
                () -> verifyNonSuspendingWorldgenKernels(jar, root));
    }

    /**
     * Adds a deep pulse to the back-edge of {@code CarvingMask.visit} (one per carved segment)
     * and of {@code visitSegment} (one per carved column). Each column runs the aquifer and the
     * material rules for every carved block through NoiseBasedChunkGenerator's
     * {@code lambda$applyCarvingMask$0}; 26.2 pulsed the equivalent loop inside the carvers.
     */
    static void patchCarvingMaskDeepPulses(String jar, Path root) throws IOException {
        ClassNode node = readCurrent(jar, root, CARVING_MASK);
        String visitor = "L" + CARVING_MASK + "$Visitor;";
        MethodNode visit = find(node, "visit", "(" + visitor + ")V");
        MethodNode visitSegment = find(node, "visitSegment", "(" + visitor + "II)V");
        for (MethodNode method : List.of(visit, visitSegment)) {
            String label = "CarvingMask." + method.name;
            requireNoSuspendingCalls(label, method);
            int pulses = insertDeepPulsesAtBackEdges(method);
            if (pulses != 1) {
                throw new IllegalStateException(
                        label + " back-edges changed: " + pulses + " (expected 1)");
            }
        }
        // A pulse before a back-edge jump neither adds a branch target nor touches the stack, so
        // the vanilla stack map frames stay valid.
        write(node, root.resolve(CARVING_MASK + ".class"));
        System.out.println("Patched 26.3 CarvingMask.visit/visitSegment with deep worldgen pulses");
    }

    /**
     * MaterialRuleContext counts XZ and Y updates in two {@code long} fields that the lazy
     * conditions compare on every rule test; TeaVM emulates {@code long}, so this adds int
     * shadows incremented next to the vanilla counters and replaces
     * {@code LazyXZCondition.test()} and {@code LazyYCondition.test()} with int comparisons
     * over a primitive cached result. The vanilla long counters keep running unchanged for the
     * other readers (the $1/$2 noise suppliers and the surface-depth/min-surface caches).
     *
     * <p>Semantics: a condition recomputes exactly when its counter moved since its last
     * computation, as vanilla does. The only difference is after {@code compute()} threw:
     * vanilla then reports "Update triggered but the result is null" on the next test of the
     * same update, while this recomputes.
     */
    static void patchMaterialRuleContextIntCounters(String jar, Path root) throws IOException {
        ClassNode context = readCurrent(jar, root, MATERIAL_RULE_CONTEXT);
        requireOnlyCounterWriters(context);
        for (String name : List.of(BROWSER_UPDATE_XZ, BROWSER_UPDATE_Y)) {
            if (context.fields.stream().anyMatch(field -> field.name.equals(name))) {
                throw new IllegalStateException(
                        "MaterialRuleContext." + name + " already exists");
            }
            context.fields.add(new FieldNode(Opcodes.ACC_SYNTHETIC, name, "I", null, null));
        }
        MethodNode updateXZ = find(context, "updateXZ", "(IIII)V");
        requireLongCounterIncrement(updateXZ, "lastUpdateXZ");
        requireLongCounterIncrement(updateXZ, "lastUpdateY");
        InsnList xzCounters = new InsnList();
        appendIntFieldIncrement(xzCounters, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_XZ);
        appendIntFieldIncrement(xzCounters, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_Y);
        updateXZ.instructions.insert(xzCounters);

        MethodNode updateY = find(context, "updateY", "(IIII)V");
        requireLongCounterIncrement(updateY, "lastUpdateY");
        InsnList yCounter = new InsnList();
        appendIntFieldIncrement(yCounter, MATERIAL_RULE_CONTEXT, BROWSER_UPDATE_Y);
        updateY.instructions.insert(yCounter);
        write(context, root.resolve(MATERIAL_RULE_CONTEXT + ".class"));

        replaceLazyConditionTest(jar, root, "$LazyXZCondition", "lastUpdateXZ", BROWSER_UPDATE_XZ);
        replaceLazyConditionTest(jar, root, "$LazyYCondition", "lastUpdateY", BROWSER_UPDATE_Y);
        System.out.println("Patched 26.3 MaterialRuleContext lazy conditions with int counters");
    }

    private static void requireOnlyCounterWriters(ClassNode context) {
        for (MethodNode method : context.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.PUTFIELD
                        && field.owner.equals(MATERIAL_RULE_CONTEXT)
                        && (field.name.equals("lastUpdateXZ") || field.name.equals("lastUpdateY"))
                        && !method.name.equals("<init>")
                        && !method.name.equals("updateXZ")
                        && !method.name.equals("updateY")) {
                    throw new IllegalStateException("MaterialRuleContext." + method.name
                            + method.desc + " also writes " + field.name
                            + "; the int shadow counters would drift");
                }
            }
        }
    }

    private static void requireLongCounterIncrement(MethodNode method, String field) {
        int increments = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode put
                    && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(MATERIAL_RULE_CONTEXT)
                    && put.name.equals(field)
                    && put.desc.equals("J")
                    && previousReal(put) instanceof InsnNode add
                    && add.getOpcode() == Opcodes.LADD
                    && previousReal(add) instanceof InsnNode one
                    && one.getOpcode() == Opcodes.LCONST_1) {
                increments++;
            }
        }
        if (increments != 1) {
            throw new IllegalStateException("MaterialRuleContext." + method.name
                    + " increments " + field + " " + increments + " times (expected 1)");
        }
    }

    private static void replaceLazyConditionTest(
            String jar, Path root, String suffix, String longCounter, String intCounter)
            throws IOException {
        String owner = MATERIAL_RULE_CONTEXT + suffix;
        ClassNode node = readCurrent(jar, root, owner);
        if ((node.access & Opcodes.ACC_ABSTRACT) == 0) {
            throw new IllegalStateException(owner + " is expected to be abstract");
        }
        MethodNode test = find(node, "test", "()Z");
        int counterReads = 0;
        int computeCalls = 0;
        for (AbstractInsnNode instruction : test.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.owner.equals(MATERIAL_RULE_CONTEXT)
                    && field.name.equals(longCounter)
                    && field.desc.equals("J")) {
                counterReads++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals("compute")
                    && call.desc.equals("()Z")) {
                computeCalls++;
            }
        }
        if (counterReads != 1 || computeCalls != 1) {
            throw new IllegalStateException(owner + ".test() shape changed: counter reads="
                    + counterReads + ", compute calls=" + computeCalls);
        }
        find(node, "compute", "()Z");
        boolean contextField = node.fields.stream().anyMatch(field ->
                field.name.equals("context") && field.desc.equals("L" + MATERIAL_RULE_CONTEXT + ";"));
        if (!contextField) {
            throw new IllegalStateException(owner + ".context field changed");
        }
        String lastUpdate = "browserLastUpdate";
        String result = "browserResult";
        String initialized = "browserResultInitialized";
        for (String name : List.of(lastUpdate, result, initialized)) {
            if (node.fields.stream().anyMatch(field -> field.name.equals(name))) {
                throw new IllegalStateException(owner + "." + name + " already exists");
            }
        }
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                lastUpdate, "I", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                result, "Z", null, null));
        node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC,
                initialized, "Z", null, null));

        LabelNode refresh = new LabelNode();
        LabelNode cached = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, "context",
                "L" + MATERIAL_RULE_CONTEXT + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, MATERIAL_RULE_CONTEXT, intCounter, "I"));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, initialized, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, refresh));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, lastUpdate, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, cached));
        code.add(refresh);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, lastUpdate, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "compute", "()Z", false));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, result, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, initialized, "Z"));
        code.add(cached);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, result, "Z"));
        code.add(new InsnNode(Opcodes.IRETURN));
        test.instructions = code;
        test.tryCatchBlocks.clear();
        if (test.localVariables != null) {
            test.localVariables.clear();
        }
        test.maxStack = 3;
        test.maxLocals = 2;
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    /**
     * Fails when a guarded class calls a suspending owner directly, or reaches one within
     * {@link #TRANSITIVE_DEPTH} calls through code of this jar (class-hierarchy analysis for
     * virtual and interface calls, lambda bodies for invokedynamic). Calls into classes outside
     * the jar are not followed. Reads the chain jar with the classes already written to
     * {@code root} taking precedence.
     */
    static void verifyNonSuspendingWorldgenKernels(String jar, Path root) throws IOException {
        ClassIndex index = ClassIndex.load(jar, root);
        GuardReport report = checkNonSuspending(index);
        report.print(System.out);
        if (!report.violations.isEmpty()) {
            throw new IllegalStateException("Non-suspending worldgen guard failed: "
                    + report.violations.size() + " violation(s), first: "
                    + report.violations.get(0));
        }
    }

    /**
     * Standalone entry point for build scripts and quick-check modules.
     * {@code --verify-non-suspending <jar>} runs the guard; {@code --self-test <jar>} checks
     * that the guard catches a pulse injected into a density kernel and one injected into a
     * method a kernel reaches, and that the unmodified jar passes.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2
                || !(args[0].equals("--verify-non-suspending") || args[0].equals("--self-test"))) {
            System.err.println("usage: WorldgenPatches263 --verify-non-suspending|--self-test "
                    + "<client.jar>");
            System.exit(2);
        }
        ClassIndex index = ClassIndex.load(args[1], null);
        GuardReport report = checkNonSuspending(index);
        report.print(System.out);
        if (args[0].equals("--self-test")) {
            System.exit(selfTest(index, report) ? 0 : 1);
        }
        System.exit(report.violations.isEmpty() ? 0 : 1);
    }

    private static boolean selfTest(ClassIndex index, GuardReport clean) {
        if (!clean.violations.isEmpty()) {
            System.out.println("WORLDGEN_GUARD_SELF_TEST FAIL unmodified jar has violations");
            return false;
        }
        // Direct: a pulse at the start of the first concrete method of a density sampler.
        MethodNode kernel = null;
        String kernelKey = null;
        for (ClassNode node : index.classes.values()) {
            if (!node.name.startsWith(GUARDED_PACKAGES.get(0) + "op/")) {
                continue;
            }
            for (MethodNode method : node.methods) {
                if (method.name.equals("sampleVolume") && method.instructions.size() > 0) {
                    kernel = method;
                    kernelKey = node.name + "." + method.name + method.desc;
                    break;
                }
            }
            if (kernel != null) {
                break;
            }
        }
        if (kernel == null) {
            System.out.println("WORLDGEN_GUARD_SELF_TEST FAIL no density sampler found");
            return false;
        }
        MethodInsnNode pulse = new MethodInsnNode(
                Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false);
        kernel.instructions.insert(pulse);
        GuardReport direct = checkNonSuspending(index);
        kernel.instructions.remove(pulse);
        String kernelName = kernelKey;
        boolean directCaught = direct.violations.stream().anyMatch(violation ->
                violation.startsWith("direct: ") && violation.contains(kernelName));
        // Transitive: a pulse in a method outside the guard that a guarded method calls.
        String outsideKey = null;
        for (String candidate : clean.reachedOutside) {
            MethodNode method = index.method(candidate);
            if (method != null && method.instructions.size() > 0) {
                outsideKey = candidate;
                break;
            }
        }
        boolean transitiveCaught = false;
        if (outsideKey != null) {
            MethodNode outside = index.method(outsideKey);
            MethodInsnNode outsidePulse = new MethodInsnNode(
                    Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false);
            outside.instructions.insert(outsidePulse);
            GuardReport transitive = checkNonSuspending(index);
            outside.instructions.remove(outsidePulse);
            String target = outsideKey;
            transitiveCaught = transitive.violations.stream().anyMatch(violation ->
                    violation.startsWith("transitive: ") && violation.contains(target));
        }
        boolean cleanAgain = checkNonSuspending(index).violations.isEmpty();
        System.out.println("WORLDGEN_GUARD_SELF_TEST direct=" + directCaught + " (" + kernelKey
                + ") transitive=" + transitiveCaught + " (" + outsideKey + ") restored="
                + cleanAgain);
        boolean passed = directCaught && transitiveCaught && cleanAgain;
        System.out.println("WORLDGEN_GUARD_SELF_TEST " + (passed ? "PASS" : "FAIL"));
        return passed;
    }

    static boolean guarded(String className) {
        for (String prefix : GUARDED_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        for (String owner : GUARDED_CLASSES) {
            if (className.equals(owner) || className.startsWith(owner + "$")) {
                return true;
            }
        }
        return false;
    }

    static boolean suspending(String owner) {
        return SUSPENDING_OWNERS.contains(owner);
    }

    static GuardReport checkNonSuspending(ClassIndex index) {
        GuardReport report = new GuardReport();
        for (String required : REQUIRED_GUARDED_CLASSES) {
            if (!index.classes.containsKey(required)) {
                report.violations.add("required guarded class missing: " + required);
            }
        }
        List<String> roots = new ArrayList<>();
        for (ClassNode node : index.classes.values()) {
            if (!guarded(node.name)) {
                continue;
            }
            report.guardedClasses++;
            for (MethodNode method : node.methods) {
                report.guardedMethods++;
                String key = node.name + "." + method.name + method.desc;
                roots.add(key);
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call && suspending(call.owner)) {
                        report.violations.add("direct: " + key + " calls "
                                + call.owner + "." + call.name + call.desc);
                    }
                }
            }
        }
        // Transitive part: breadth-first from every guarded method.
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String rootKey : roots) {
            parent.put(rootKey, null);
            depth.put(rootKey, 0);
            queue.add(rootKey);
        }
        Set<String> reported = new HashSet<>();
        while (!queue.isEmpty()) {
            String key = queue.removeFirst();
            int level = depth.get(key);
            MethodNode method = index.method(key);
            if (method == null) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                for (String target : index.callTargets(instruction)) {
                    if (target.startsWith("!")) {
                        String owner = target.substring(1);
                        if (level > 0 && reported.add(key)) {
                            report.violations.add("transitive: " + path(parent, key)
                                    + " -> " + owner);
                        }
                        continue;
                    }
                    report.followedCalls++;
                    if (depth.containsKey(target) || level + 1 > TRANSITIVE_DEPTH) {
                        continue;
                    }
                    depth.put(target, level + 1);
                    parent.put(target, key);
                    queue.addLast(target);
                    if (!guarded(target.substring(0, target.indexOf('.')))) {
                        report.reachedOutside.add(target);
                    }
                }
            }
        }
        report.reachedMethods = depth.size();
        return report;
    }

    private static String path(Map<String, String> parent, String key) {
        List<String> chain = new ArrayList<>();
        for (String cursor = key; cursor != null; cursor = parent.get(cursor)) {
            chain.add(cursor);
        }
        Collections.reverse(chain);
        return String.join(" -> ", chain);
    }

    /** Result of {@link #checkNonSuspending}. */
    static final class GuardReport {
        final List<String> violations = new ArrayList<>();
        final Set<String> reachedOutside = new TreeSet<>();
        int guardedClasses;
        int guardedMethods;
        int reachedMethods;
        long followedCalls;

        void print(java.io.PrintStream out) {
            out.println("WORLDGEN_NON_SUSPENDING_GUARD guardedClasses=" + guardedClasses
                    + " guardedMethods=" + guardedMethods + " reachedMethods=" + reachedMethods
                    + " reachedOutsideGuard=" + reachedOutside.size()
                    + " depth=" + TRANSITIVE_DEPTH + " violations=" + violations.size());
            for (String violation : violations) {
                out.println("WORLDGEN_NON_SUSPENDING_VIOLATION " + violation);
            }
        }
    }

    /**
     * The classes of a client jar (optionally overlaid by patched class files), with the
     * subtype relation needed to resolve virtual and interface calls.
     */
    static final class ClassIndex {
        final Map<String, ClassNode> classes = new LinkedHashMap<>();
        final Map<String, List<String>> subtypes = new HashMap<>();
        private final Map<String, MethodNode> methods = new HashMap<>();
        private final Map<String, List<String>> virtualTargets = new HashMap<>();

        static ClassIndex load(String jar, Path overlay) throws IOException {
            ClassIndex index = new ClassIndex();
            try (ZipFile zip = new ZipFile(jar)) {
                for (ZipEntry entry : Collections.list(zip.entries())) {
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    String className = name.substring(0, name.length() - ".class".length());
                    Path patched = overlay == null ? null : overlay.resolve(name);
                    byte[] bytes;
                    if (patched != null && Files.isRegularFile(patched)) {
                        bytes = Files.readAllBytes(patched);
                    } else {
                        try (InputStream input = zip.getInputStream(entry)) {
                            bytes = input.readAllBytes();
                        }
                    }
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG
                            | ClassReader.SKIP_FRAMES);
                    index.classes.put(className, node);
                }
            }
            for (ClassNode node : index.classes.values()) {
                if (node.superName != null) {
                    index.subtypes.computeIfAbsent(node.superName, key -> new ArrayList<>())
                            .add(node.name);
                }
                for (String iface : node.interfaces) {
                    index.subtypes.computeIfAbsent(iface, key -> new ArrayList<>()).add(node.name);
                }
                for (MethodNode method : node.methods) {
                    index.methods.put(node.name + "." + method.name + method.desc, method);
                }
            }
            return index;
        }

        MethodNode method(String key) {
            return methods.get(key);
        }

        /**
         * The methods of this jar a call instruction may reach; {@code "!owner"} marks a call to
         * a suspending owner.
         */
        List<String> callTargets(AbstractInsnNode instruction) {
            if (instruction instanceof MethodInsnNode call) {
                if (suspending(call.owner)) {
                    return List.of("!" + call.owner + "." + call.name + call.desc);
                }
                if (!classes.containsKey(call.owner)) {
                    return List.of();
                }
                String signature = call.name + call.desc;
                return switch (call.getOpcode()) {
                    case Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> {
                        String declared = resolveUp(call.owner, signature);
                        yield declared == null ? List.of() : List.of(declared);
                    }
                    default -> virtualTargets(call.owner, signature);
                };
            }
            if (instruction instanceof InvokeDynamicInsnNode indy) {
                List<String> targets = new ArrayList<>();
                for (Object argument : indy.bsmArgs) {
                    if (argument instanceof Handle handle) {
                        if (suspending(handle.getOwner())) {
                            targets.add("!" + handle.getOwner() + "." + handle.getName()
                                    + handle.getDesc());
                        } else if (methods.containsKey(handle.getOwner() + "."
                                + handle.getName() + handle.getDesc())) {
                            targets.add(handle.getOwner() + "." + handle.getName()
                                    + handle.getDesc());
                        }
                    }
                }
                return targets;
            }
            return List.of();
        }

        private String resolveUp(String owner, String signature) {
            for (String cursor = owner; cursor != null; ) {
                String key = cursor + "." + signature;
                if (methods.containsKey(key)) {
                    return key;
                }
                ClassNode node = classes.get(cursor);
                cursor = node == null ? null : node.superName;
            }
            return null;
        }

        private List<String> virtualTargets(String owner, String signature) {
            if (signature.startsWith("equals(") || signature.startsWith("hashCode(")
                    || signature.startsWith("toString(")) {
                return List.of();
            }
            String cacheKey = owner + "." + signature;
            List<String> cached = virtualTargets.get(cacheKey);
            if (cached != null) {
                return cached;
            }
            Set<String> targets = new TreeSet<>();
            String inherited = resolveUp(owner, signature);
            if (inherited != null) {
                targets.add(inherited);
            }
            Deque<String> pending = new ArrayDeque<>(subtypes.getOrDefault(owner, List.of()));
            Set<String> seen = new HashSet<>();
            while (!pending.isEmpty()) {
                String type = pending.removeFirst();
                if (!seen.add(type)) {
                    continue;
                }
                String key = type + "." + signature;
                MethodNode method = methods.get(key);
                if (method != null && (method.access & Opcodes.ACC_ABSTRACT) == 0) {
                    targets.add(key);
                }
                pending.addAll(subtypes.getOrDefault(type, List.of()));
            }
            List<String> result = List.copyOf(targets);
            virtualTargets.put(cacheKey, result);
            return result;
        }
    }

    private static void requireNoSuspendingCalls(String label, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && suspending(call.owner)) {
                throw new IllegalStateException(label + " already calls " + call.owner + "."
                        + call.name);
            }
        }
    }

    private static int insertDeepPulsesAtBackEdges(MethodNode method) {
        int pulses = 0;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = 0; index < instructions.length; index++) {
            if (instructions[index] instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label) < index) {
                method.instructions.insertBefore(jump, new MethodInsnNode(
                        Opcodes.INVOKESTATIC, DEEP_CHECKPOINT, "pulse", "()V", false));
                pulses++;
            }
        }
        return pulses;
    }

    private static void appendIntFieldIncrement(InsnList code, String owner, String field) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, "I"));
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException(node.name + "." + name + descriptor + " was not found");
    }

    /** Reads {@code owner} as the chain has patched it so far (root file first, then jar). */
    private static ClassNode readCurrent(String jar, Path root, String owner) throws IOException {
        Path patched = root.resolve(owner + ".class");
        byte[] bytes;
        if (Files.isRegularFile(patched)) {
            bytes = Files.readAllBytes(patched);
        } else {
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(owner + ".class");
                if (entry == null) {
                    throw new IOException("Missing class entry " + owner + ".class in " + jar);
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void write(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }

    private static void writeComputeFrames(ClassNode node, Path output) throws IOException {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        node.accept(writer);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
