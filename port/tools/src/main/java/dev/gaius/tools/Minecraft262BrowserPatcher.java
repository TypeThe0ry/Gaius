package dev.gaius.tools;

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
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Removes Java 25 and desktop-only paths that remain reachable in Minecraft 26.2. */
public final class Minecraft262BrowserPatcher {
    private static final String JDK_COMPAT = "dev/gaius/browser/BrowserJdkCompat";
    private static final String WORLDGEN_SCHEDULER =
            "dev/gaius/browser/BrowserWorldgenScheduler";
    private static final String CHUNK_GENERATION_TASK =
            "net/minecraft/server/level/ChunkGenerationTask";
    private static final String CHUNK_STATUS =
            "net/minecraft/world/level/chunk/status/ChunkStatus";
    private static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";
    private static final String GENERATION_HOLDER =
            "net/minecraft/server/level/GenerationChunkHolder";
    private static final String BROWSER_LAYER_STATUS = "browserLayerStatus";
    private static final String BROWSER_LAYER_NEEDS_GENERATION =
            "browserLayerNeedsGeneration";
    private static final String BROWSER_LAYER_ACTIVE = "browserLayerActive";
    private static final String BROWSER_LAYER_X = "browserLayerX";
    private static final String BROWSER_LAYER_Z = "browserLayerZ";
    private static final String BROWSER_LAYER_START_Z = "browserLayerStartZ";
    private static final String BROWSER_LAYER_END_X = "browserLayerEndX";
    private static final String BROWSER_LAYER_END_Z = "browserLayerEndZ";
    private static final int BROWSER_HOLDERS_PER_TURN = 16;
    private static final int BROWSER_REGION_FILE_CACHE_SIZE = 16;

    private Minecraft262BrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: Minecraft262BrowserPatcher INPUT_JAR OUTPUT_ROOT");
        }
        String jar = args[0];
        Path root = Path.of(args[1]);
        patchNativeLibrariesBootstrap(jar, root);
        patchPreferredGraphicsApi(jar, root);
        patchVulkanBackend(jar, root);
        patchGlDeviceCapabilities(jar, root);
        patchFramerateLimiter(jar, root);
        patchGraphicsPresetBrowserDistances(jar, root);
        patchChunkGenerationCooperation(jar, root);
        patchDistanceManagerCooperation(jar, root);
        patchServerChunkBroadcastCooperation(jar, root);
        patchRegionFileStorageCache(jar, root);
        patchGlBufferMappedViewRanges(jar, root);
        patchLiveFrameTargeting(jar, root);
        patchSectionRenderTaskRetryYields(jar, root);
        patchSectionRenderEmergencyUpload(jar, root);
        patchStagingBuffer(jar, root);
        patchUberGpuBufferNodeCleanup(jar, root);
        patchTemplateSource(jar, root);
        patchRemoteFriendList(jar, root);
        patchNativeModuleLister(jar, root);
        patchMacosUtil(jar, root);
        patchVulkanDebug(jar, root);
        patchDetailedMemoryDebug(jar, root);
        patchSystemSpecsDebug(jar, root);
        patchFileFixAccess(jar, root);
        patchFileFixerUpperHardLinks(jar, root);
        patchIdentifierResolveAgainst(jar, root);
        patchCopyOnWriteFileSystem(jar, root);
        patchCopyOnWriteProvider(jar, root);
    }

    private static void patchChunkGenerationCooperation(String jar, Path root)
            throws IOException {
        String owner = CHUNK_GENERATION_TASK;
        ClassNode node = read(jar, owner + ".class");

        addPrivateField(node, BROWSER_LAYER_STATUS, "L" + CHUNK_STATUS + ";");
        addPrivateField(node, BROWSER_LAYER_NEEDS_GENERATION, "Z");
        addPrivateField(node, BROWSER_LAYER_ACTIVE, "Z");
        addPrivateField(node, BROWSER_LAYER_X, "I");
        addPrivateField(node, BROWSER_LAYER_Z, "I");
        addPrivateField(node, BROWSER_LAYER_START_Z, "I");
        addPrivateField(node, BROWSER_LAYER_END_X, "I");
        addPrivateField(node, BROWSER_LAYER_END_Z, "I");
        MethodNode runUntilWait = find(
                node,
                "runUntilWait",
                "()Ljava/util/concurrent/CompletableFuture;");
        requireNoServerWorkTurnReset(
                "ChunkGenerationTask.runUntilWait",
                runUntilWait);
        patchRunUntilWaitActiveGate(runUntilWait, owner);
        replaceChunkGenerationScheduleNextLayer(
                find(node, "scheduleNextLayer", "()V"), owner);
        replaceChunkGenerationScheduleLayer(
                find(node, "scheduleLayer",
                        "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z)V"),
                owner);
        instrumentBrowserTaskScope(runUntilWait, "ChunkGenerationTask.runUntilWait", Opcodes.ARETURN);
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.runUntilWait",
                runUntilWait,
                1,
                "pulse");
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.waitForScheduledLayer",
                find(node, "waitForScheduledLayer", "()Ljava/util/concurrent/CompletableFuture;"),
                1,
                "pulse");
        requireWorldgenLoopPulses(
                "ChunkGenerationTask.canLoadWithoutGeneration",
                find(node, "canLoadWithoutGeneration", "()Z"),
                2,
                "pulse");

        // runUntilWait now has a catch-all task-scope finally plus token and
        // Throwable locals.  Recompute StackMapTable frames before emitting
        // the overlaid class; a plain writer leaves verifier frames stale on
        // the ARETURN/ATHROW paths.
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 chunk-generation layer at holder boundaries"
                        + " with method-local scheduler continuation");
    }

    private static void addPrivateField(ClassNode node, String name, String descriptor) {
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(descriptor)) {
                return;
            }
        }
        node.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                name,
                descriptor,
                null,
                null));
    }

    /**
     * Keeps the vanilla layer barrier intact while a browser cursor is active.
     *
     * <p>{@code waitForScheduledLayer()} may only run after every holder in the current layer has
     * been submitted. Waiting after the first cursor batch serializes the layer and can starve
     * progress; it is also unsafe if a pending holder depends on one not submitted yet. Re-enter
     * {@code scheduleNextLayer()} while the cursor is active, using the existing task-layer
     * scheduler pulse between bounded batches; only the completed cursor reaches the vanilla
     * wait/status loop.
     */
    private static void patchRunUntilWaitActiveGate(MethodNode method, String owner) {
        LabelNode continueVanilla = new LabelNode();
        JumpInsnNode originalBackedge = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.GOTO
                    || method.instructions.indexOf(jump.label)
                            >= method.instructions.indexOf(jump)) {
                continue;
            }
            if (originalBackedge != null) {
                throw new IllegalStateException(
                        "ChunkGenerationTask.runUntilWait has multiple vanilla backedges");
            }
            originalBackedge = jump;
        }
        if (originalBackedge == null) {
            throw new IllegalStateException(
                    "ChunkGenerationTask.runUntilWait has no vanilla backedge");
        }
        // The active branch must re-enter the original runUntilWait edge.  The scheduler pulse
        // inserter runs after this method and places exactly one pulse immediately before that
        // edge, so no second artificial future/backedge is introduced here.
        LabelNode activeResume = new LabelNode();
        method.instructions.insertBefore(originalBackedge, activeResume);

        InsnList gate = new InsnList();
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        gate.add(new JumpInsnNode(Opcodes.IFEQ, continueVanilla));
        gate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        gate.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, owner, "scheduleNextLayer", "()V", false));
        gate.add(new JumpInsnNode(Opcodes.GOTO, activeResume));
        gate.add(continueVanilla);
        // Keep the original loop target label in front of the gate.  The vanilla
        // runUntilWait backedge jumps to that label, so inserting before the label
        // would let the backedge bypass the yield check on every subsequent holder.
        AbstractInsnNode first = method.instructions.getFirst();
        if (first instanceof LabelNode entryLabel) {
            method.instructions.insert(entryLabel, gate);
        } else {
            throw new IllegalStateException(
                    "ChunkGenerationTask.runUntilWait has no entry label for active gate");
        }
    }

    private static void replaceChunkGenerationScheduleNextLayer(
            MethodNode method, String owner) {
        String statusDescriptor = "L" + CHUNK_STATUS + ";";
        LabelNode resumeActive = new LabelNode();
        LabelNode freshStatus = new LabelNode();
        LabelNode existingStatus = new LabelNode();
        LabelNode nextStatus = new LabelNode();
        LabelNode computed = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();

        // Resume the same status when a holder cursor was split across browser turns.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, resumeActive));
        code.add(new JumpInsnNode(Opcodes.GOTO, freshStatus));

        code.add(resumeActive);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleLayer",
                "(" + statusDescriptor + "Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));

        code.add(freshStatus);

        // This is the vanilla status-selection logic, kept in the task layer.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.IFNONNULL, existingStatus));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        code.add(existingStatus);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "canLoadWithoutGeneration",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFNE, nextStatus));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "needsGeneration", "Z"));
        code.add(new FieldInsnNode(
                Opcodes.GETSTATIC, CHUNK_STATUS, "EMPTY", statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        // The next generation status is selected exactly as in the named client.
        code.add(nextStatus);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                CHUNK_STATUS,
                "getStatusList",
                "()Ljava/util/List;",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_STATUS, "getIndex", "()I", false));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, CHUNK_STATUS));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, computed));

        code.add(computed);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "needsGeneration", "Z"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleLayer",
                "(" + statusDescriptor + "Z)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, "scheduledStatus", statusDescriptor));
        code.add(done);
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 3, 2);
    }

    /**
     * Replaces the vanilla nested holder scan with a bounded task-layer cursor.  The holder
     * remains the minimum unit of work: no deep generation method is changed.  Each method-local
     * turn submits at most {@link #BROWSER_HOLDERS_PER_TURN} holders before the existing
     * task-layer scheduler pulse; the pulse preserves task-layer budget and telemetry accounting
     * while TeaVM resumes this method.  The final batch reaches the vanilla layer barrier only
     * after its holders have been submitted.
     */
    private static void replaceChunkGenerationScheduleLayer(
            MethodNode method, String owner) {
        String statusDescriptor = "L" + CHUNK_STATUS + ";";
        String holderDescriptor = "L" + GENERATION_HOLDER + ";";
        LabelNode resume = new LabelNode();
        LabelNode cancel = new LabelNode();
        LabelNode successful = new LabelNode();
        LabelNode nextColumn = new LabelNode();
        LabelNode continueBatch = new LabelNode();
        LabelNode normalReturn = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode start = new LabelNode();
        InsnList code = new InsnList();
        code.add(start);
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));

        // Initialize the cursor only for a new status.  On resume, the saved x/z pair
        // already points at the next holder and the status arguments are unchanged.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, resume));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_STATUS, statusDescriptor));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_NEEDS_GENERATION, "Z"));

        // radius = getRadiusForLayer(status, needsGeneration)
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "getRadiusForLayer",
                "(" + statusDescriptor + "Z)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));

        // x = pos.x - radius; endX = pos.x + radius
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "x", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_END_X, "I"));

        // z = pos.z - radius; startZ/endZ are retained for column transitions.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_START_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "pos", "L" + CHUNK_POS + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, CHUNK_POS, "z", "()I", false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_END_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));

        code.add(resume);
        // holder = cache.get(browserLayerX, browserLayerZ)
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "cache", "Lnet/minecraft/util/StaticCache2D;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "net/minecraft/util/StaticCache2D",
                "get",
                "(II)Ljava/lang/Object;",
                false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, GENERATION_HOLDER));
        code.add(new VarInsnNode(Opcodes.ASTORE, 6));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "markedForCancellation", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFNE, cancel));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 6));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                owner,
                "scheduleChunkInLayer",
                "(" + statusDescriptor + "Z" + holderDescriptor + ")Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, cancel));
        code.add(new JumpInsnNode(Opcodes.GOTO, successful));

        code.add(cancel);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        // Keep one inexpensive pulse at the holder boundary for scheduler telemetry.
        code.add(successful);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulse",
                "()V",
                false));
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 7));

        // Advance the cursor.  The final holder marks the layer complete and reaches the
        // vanilla layer barrier; partial batches stay inside this method and use pulse().
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_END_Z, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, nextColumn));

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.IADD));
        code.add(new VarInsnNode(Opcodes.ISTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_X, "I"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_START_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));
        code.add(new VarInsnNode(Opcodes.ILOAD, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, BROWSER_LAYER_END_X, "I"));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLE, continueBatch));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        code.add(nextColumn);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_Z, "I"));

        code.add(continueBatch);
        code.add(new VarInsnNode(Opcodes.ILOAD, 7));
        code.add(new LdcInsnNode(BROWSER_HOLDERS_PER_TURN));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPLT, resume));
        // A full holder batch returns with the cursor active.  The original runUntilWait
        // backedge pulses and re-enters the active gate, which invokes scheduleNextLayer for
        // the next batch.  No synthetic future or callback is attached to the task.
        code.add(new JumpInsnNode(Opcodes.GOTO, normalReturn));

        code.add(normalReturn);
        code.add(tryEnd);
        code.add(new InsnNode(Opcodes.RETURN));
        // A holder or scheduler failure must not leave a live cursor attached to the task.
        code.add(handler);
        code.add(new VarInsnNode(Opcodes.ASTORE, 9));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new FieldInsnNode(
                Opcodes.PUTFIELD, owner, BROWSER_LAYER_ACTIVE, "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 9));
        code.add(new InsnNode(Opcodes.ATHROW));
        replace(method, code, 5, 10);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, tryEnd, handler, "java/lang/Throwable"));
    }

    private static MethodInsnNode browserWorldgenBeginTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "beginTaskWork",
                "(Ljava/lang/String;)I",
                false);
    }

    private static MethodInsnNode browserWorldgenEndTaskWork() {
        return new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "endTaskWork",
                "(I)V",
                false);
    }

    /**
     * Scope only the task-layer method.  A pending future returns before the
     * server's waitUntilNextTick path, so closing the scope on every ARETURN is
     * what keeps that wait's wall-clock idle time out of the scheduler slice.
     */
    private static void instrumentBrowserTaskScope(
            MethodNode method, String target, int returnOpcode) {
        if (method.instructions.getFirst() == null) {
            throw new IllegalStateException(target + " has no instructions");
        }

        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        int taskScopeLocal = method.maxLocals++;
        int throwableLocal = method.maxLocals++;
        InsnList entry = new InsnList();
        entry.add(new LdcInsnNode(target));
        entry.add(browserWorldgenBeginTaskWork());
        entry.add(new VarInsnNode(Opcodes.ISTORE, taskScopeLocal));
        // The catch range starts only after beginTaskWork has initialized the
        // per-invocation token.  This also keeps cleanup valid if the first
        // task-layer instruction throws.
        entry.add(start);
        method.instructions.insertBefore(method.instructions.getFirst(), entry);

        int returns = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != returnOpcode) {
                continue;
            }
            InsnList close = new InsnList();
            close.add(new VarInsnNode(Opcodes.ILOAD, taskScopeLocal));
            close.add(browserWorldgenEndTaskWork());
            method.instructions.insertBefore(instruction, close);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException(target + " has no normal return for task scope");
        }

        InsnList cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, taskScopeLocal));
        cleanup.add(browserWorldgenEndTaskWork());
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 1);
        System.out.println("Instrumented " + target + " active-work scope: returns=" + returns);
    }

    /**
     * The scheduler clock is owned by MinecraftServer's work-turn boundary. Do not let a
     * future 26.2 task patch reset it for every runUntilWait invocation: several tasks can run
     * in one tick and must consume one cumulative pulse budget.
     */
    private static void requireNoServerWorkTurnReset(String target, MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals(WORLDGEN_SCHEDULER)
                    && call.name.equals("beginServerWorkTurn")
                    && call.desc.equals("()V")) {
                throw new IllegalStateException(
                        target + " must not reset the shared server work-turn clock");
            }
        }
    }

    /**
     * Makes the vanilla DistanceManager work queue resumable. The snapshots are essential:
     * worldgen yields pump urgent packets on this same logical server thread, and those packets
     * may enqueue more ticket work. Clearing the live sets before walking their snapshots leaves
     * that new work in the live sets for the next tick instead of invalidating an iterator or
     * clearing it at the end of the pass.
     */
    private static void patchDistanceManagerCooperation(String jar, Path root)
            throws IOException {
        patchLoadingChunkTrackerCooperation(jar, root);

        String owner = "net/minecraft/server/level/DistanceManager";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "runAllUpdates",
                "(Lnet/minecraft/server/level/ChunkMap;)Z");

        snapshotChunkFutureUpdates(method, owner);
        snapshotTicketReleases(method, owner);
        requireWorldgenLoopPulses(
                "DistanceManager.runAllUpdates",
                method,
                3,
                "pulseDistanceManager");

        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 DistanceManager updates, ticket releases, and futures");
    }

    private static void patchLoadingChunkTrackerCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/LoadingChunkTracker";
        String graph = "net/minecraft/world/level/lighting/DynamicGraphMinFixedPoint";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "runDistanceUpdates", "(I)I");

        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode continueWithoutYield = new LabelNode();
        InsnList code = new InsnList();
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFLE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "hasWork",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "distanceManagerUpdateBudget",
                "()I",
                false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Math",
                "min",
                "(II)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "runUpdates",
                "(I)I",
                false));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 4));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new VarInsnNode(Opcodes.ISTORE, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "recordDistanceManagerUpdates",
                "(I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                graph,
                "hasWork",
                "()Z",
                false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new JumpInsnNode(Opcodes.IFLE, done));
        code.add(new VarInsnNode(Opcodes.ILOAD, 4));
        code.add(new JumpInsnNode(Opcodes.IFGT, continueWithoutYield));
        code.add(new JumpInsnNode(Opcodes.GOTO, done));
        code.add(continueWithoutYield);
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseDistanceManager",
                "()V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ILOAD, 1));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 2, 5);
        writeComputeFrames(node, root.resolve(owner + ".class"));
    }

    private static void snapshotChunkFutureUpdates(MethodNode method, String owner) {
        java.util.List<MethodInsnNode> setIterators = new java.util.ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Ljava/util/Iterator;")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("chunksToUpdateFutures")) {
                    setIterators.add(call);
                }
            }
        }
        if (setIterators.size() != 2) {
            throw new IllegalStateException(
                    "DistanceManager chunks-to-update iterator count changed: "
                            + setIterators.size());
        }

        MethodInsnNode originalClear = null;
        MethodInsnNode secondIterator = setIterators.get(1);
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("chunksToUpdateFutures")
                        && method.instructions.indexOf(call)
                                > method.instructions.indexOf(secondIterator)) {
                    originalClear = call;
                    break;
                }
            }
        }
        if (originalClear == null) {
            throw new IllegalStateException("DistanceManager futures clear was not found");
        }

        int snapshotLocal = method.maxLocals++;
        MethodInsnNode first = setIterators.get(0);
        FieldInsnNode firstField = fieldBefore(first);
        AbstractInsnNode firstOwner = previousOpcode(firstField);
        requireReceiverLoad("DistanceManager first futures iterator", firstOwner, firstField);
        InsnList firstSnapshot = new InsnList();
        firstSnapshot.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        firstSnapshot.add(new InsnNode(Opcodes.DUP));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        firstSnapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunksToUpdateFutures", "Ljava/util/Set;"));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(Ljava/util/Collection;)V",
                false));
        firstSnapshot.add(new VarInsnNode(Opcodes.ASTORE, snapshotLocal));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        firstSnapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunksToUpdateFutures", "Ljava/util/Set;"));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "clear",
                "()V",
                true));
        firstSnapshot.add(new VarInsnNode(Opcodes.ALOAD, snapshotLocal));
        firstSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        method.instructions.insertBefore(firstOwner, firstSnapshot);
        method.instructions.remove(first);
        method.instructions.remove(firstField);
        method.instructions.remove(firstOwner);

        MethodInsnNode second = secondIterator;
        FieldInsnNode secondField = fieldBefore(second);
        AbstractInsnNode secondOwner = previousOpcode(secondField);
        requireReceiverLoad("DistanceManager second futures iterator", secondOwner, secondField);
        InsnList secondSnapshot = new InsnList();
        secondSnapshot.add(new VarInsnNode(Opcodes.ALOAD, snapshotLocal));
        secondSnapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true));
        method.instructions.insertBefore(secondOwner, secondSnapshot);
        method.instructions.remove(second);
        method.instructions.remove(secondField);
        method.instructions.remove(secondOwner);

        FieldInsnNode clearField = fieldBefore(originalClear);
        AbstractInsnNode clearOwner = previousOpcode(clearField);
        requireReceiverLoad("DistanceManager futures clear", clearOwner, clearField);
        method.instructions.remove(originalClear);
        method.instructions.remove(clearField);
        method.instructions.remove(clearOwner);
    }

    private static void snapshotTicketReleases(MethodNode method, String owner) {
        MethodInsnNode iterator = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongSet")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Lit/unimi/dsi/fastutil/longs/LongIterator;")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("ticketsToRelease")) {
                    iterator = call;
                    break;
                }
            }
        }
        if (iterator == null) {
            throw new IllegalStateException("DistanceManager ticket iterator was not found");
        }
        FieldInsnNode iteratorField = fieldBefore(iterator);
        AbstractInsnNode iteratorOwner = previousOpcode(iteratorField);
        requireReceiverLoad("DistanceManager ticket iterator", iteratorOwner, iteratorField);
        AbstractInsnNode iteratorStore = nextOpcode(iterator);
        AbstractInsnNode hasNextLoad = nextOpcode(iteratorStore);
        AbstractInsnNode hasNext = nextOpcode(hasNextLoad);
        AbstractInsnNode endJump = nextOpcode(hasNext);
        AbstractInsnNode nextLongLoad = nextOpcode(endJump);
        AbstractInsnNode nextLong = nextOpcode(nextLongLoad);
        AbstractInsnNode ticketStore = nextOpcode(nextLong);
        if (!(iteratorStore instanceof VarInsnNode storeIterator)
                || storeIterator.getOpcode() != Opcodes.ASTORE
                || !(hasNextLoad instanceof VarInsnNode loadIterator)
                || loadIterator.getOpcode() != Opcodes.ALOAD
                || loadIterator.var != storeIterator.var
                || !(hasNext instanceof MethodInsnNode hasNextCall)
                || !hasNextCall.owner.equals("it/unimi/dsi/fastutil/longs/LongIterator")
                || !hasNextCall.name.equals("hasNext")
                || !(endJump instanceof JumpInsnNode end)
                || end.getOpcode() != Opcodes.IFEQ
                || !(nextLongLoad instanceof VarInsnNode loadNext)
                || loadNext.getOpcode() != Opcodes.ALOAD
                || loadNext.var != storeIterator.var
                || !(nextLong instanceof MethodInsnNode nextLongCall)
                || !nextLongCall.owner.equals("it/unimi/dsi/fastutil/longs/LongIterator")
                || !nextLongCall.name.equals("nextLong")
                || !(ticketStore instanceof VarInsnNode storeTicket)
                || storeTicket.getOpcode() != Opcodes.LSTORE) {
            throw new IllegalStateException("DistanceManager ticket loop shape changed");
        }

        JumpInsnNode backedge = null;
        for (AbstractInsnNode instruction = ticketStore.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && method.instructions.indexOf(jump.label)
                            <= method.instructions.indexOf(hasNextLoad)) {
                backedge = jump;
                break;
            }
        }
        if (backedge == null) {
            throw new IllegalStateException("DistanceManager ticket loop backedge was not found");
        }

        int ticketsLocal = method.maxLocals++;
        int ticketIndexLocal = method.maxLocals++;
        InsnList snapshot = new InsnList();
        snapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        snapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "ticketsToRelease",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        snapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "toLongArray",
                "()[J",
                true));
        snapshot.add(new VarInsnNode(Opcodes.ASTORE, ticketsLocal));
        snapshot.add(new VarInsnNode(Opcodes.ALOAD, 0));
        snapshot.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "ticketsToRelease",
                "Lit/unimi/dsi/fastutil/longs/LongSet;"));
        snapshot.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "it/unimi/dsi/fastutil/longs/LongSet",
                "clear",
                "()V",
                true));
        snapshot.add(new InsnNode(Opcodes.ICONST_0));
        snapshot.add(new VarInsnNode(Opcodes.ISTORE, ticketIndexLocal));
        method.instructions.insertBefore(iteratorOwner, snapshot);
        method.instructions.remove(iterator);
        method.instructions.remove(iteratorField);
        method.instructions.remove(iteratorOwner);
        method.instructions.remove(iteratorStore);

        LabelNode loopCheck = new LabelNode();
        InsnList condition = new InsnList();
        condition.add(loopCheck);
        condition.add(new VarInsnNode(Opcodes.ILOAD, ticketIndexLocal));
        condition.add(new VarInsnNode(Opcodes.ALOAD, ticketsLocal));
        condition.add(new InsnNode(Opcodes.ARRAYLENGTH));
        condition.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end.label));
        method.instructions.insertBefore(hasNextLoad, condition);
        method.instructions.remove(hasNextLoad);
        method.instructions.remove(hasNext);
        method.instructions.remove(endJump);

        InsnList ticketLoad = new InsnList();
        ticketLoad.add(new VarInsnNode(Opcodes.ALOAD, ticketsLocal));
        ticketLoad.add(new VarInsnNode(Opcodes.ILOAD, ticketIndexLocal));
        ticketLoad.add(new InsnNode(Opcodes.LALOAD));
        method.instructions.insertBefore(nextLongLoad, ticketLoad);
        method.instructions.remove(nextLongLoad);
        method.instructions.remove(nextLong);

        InsnList advance = new InsnList();
        advance.add(new org.objectweb.asm.tree.IincInsnNode(ticketIndexLocal, 1));
        advance.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseDistanceManager",
                "()V",
                false));
        advance.add(new JumpInsnNode(Opcodes.GOTO, loopCheck));
        method.instructions.insertBefore(backedge, advance);
        method.instructions.remove(backedge);

        MethodInsnNode clear = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("it/unimi/dsi/fastutil/longs/LongSet")
                    && call.name.equals("clear")
                    && call.desc.equals("()V")) {
                FieldInsnNode field = fieldBefore(call);
                if (field != null
                        && field.owner.equals(owner)
                        && field.name.equals("ticketsToRelease")
                        && method.instructions.indexOf(call)
                                > method.instructions.indexOf(end.label)) {
                    clear = call;
                    break;
                }
            }
        }
        if (clear == null) {
            throw new IllegalStateException("DistanceManager ticket clear was not found");
        }
        FieldInsnNode clearField = fieldBefore(clear);
        AbstractInsnNode clearOwner = previousOpcode(clearField);
        requireReceiverLoad("DistanceManager ticket clear", clearOwner, clearField);
        method.instructions.remove(clear);
        method.instructions.remove(clearField);
        method.instructions.remove(clearOwner);
    }

    private static void patchServerChunkBroadcastCooperation(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/server/level/ServerChunkCache";
        String holder = "net/minecraft/server/level/ChunkHolder";
        String levelChunk = "net/minecraft/world/level/chunk/LevelChunk";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "broadcastChangedChunks",
                "(Lnet/minecraft/util/profiling/ProfilerFiller;)V");

        LabelNode loop = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new LdcInsnNode("broadcast"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "push",
                "(Ljava/lang/String;)V",
                true));
        // Snapshot before a cooperative pulse. New dirty holders stay in the live set for next tick.
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunkHoldersToBroadcast", "Ljava/util/Set;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "toArray",
                "()[Ljava/lang/Object;",
                true));
        code.add(new VarInsnNode(Opcodes.ASTORE, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "chunkHoldersToBroadcast", "Ljava/util/Set;"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/Set",
                "clear",
                "()V",
                true));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "beginChunkBroadcast",
                "(I)V",
                false));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 3));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new VarInsnNode(Opcodes.ILOAD, 3));
        code.add(new InsnNode(Opcodes.AALOAD));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, holder));
        code.add(new VarInsnNode(Opcodes.ASTORE, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                holder,
                "getTickingChunk",
                "()Lnet/minecraft/world/level/chunk/LevelChunk;",
                false));
        code.add(new VarInsnNode(Opcodes.ASTORE, 5));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                holder,
                "broadcastChanges",
                "(L" + levelChunk + ";)V",
                false));
        code.add(skip);
        code.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "pulseChunkBroadcast",
                "()V",
                false));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 2));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                WORLDGEN_SCHEDULER,
                "finishChunkBroadcast",
                "(I)V",
                false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/profiling/ProfilerFiller",
                "pop",
                "()V",
                true));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 3, 6);
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println("Made Minecraft 26.2 changed-chunk broadcasts cooperative");
    }

    private static void patchUberGpuBufferNodeCleanup(String jar, Path root)
            throws IOException {
        String owner = "com/mojang/blaze3d/vertex/UberGpuBuffer";
        String pair = "com/mojang/datafixers/util/Pair";
        String allocator = "com/mojang/blaze3d/vertex/TlsfAllocator";
        String heap = owner + "$UberGpuBufferHeap";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "uploadStagedAllocations",
                "(Lcom/mojang/blaze3d/systems/GpuDevice;"
                        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$Uploader;)Z");

        MethodInsnNode finishUpload = null;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("dev/gaius/browser/BrowserRenderScheduler")
                    && call.name.equals("finishUploadBuffer")
                    && call.desc.equals("(Ljava/lang/Object;Ljava/util/Map;Ljava/util/Set;)V")) {
                finishUpload = call;
                break;
            }
        }
        if (finishUpload == null) {
            throw new IllegalStateException(
                    "UberGpuBuffer upload budget patch must run before node cleanup patch");
        }

        MethodInsnNode cleanupIterator = null;
        for (AbstractInsnNode instruction = finishUpload.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/List")
                    && call.name.equals("iterator")
                    && call.desc.equals("()Ljava/util/Iterator;")) {
                cleanupIterator = call;
            }
        }
        if (cleanupIterator == null) {
            throw new IllegalStateException("UberGpuBuffer free-heap iterator was not found");
        }
        FieldInsnNode nodesField = fieldBefore(cleanupIterator);
        AbstractInsnNode cleanupStart = previousOpcode(nodesField);
        requireReceiverLoad("UberGpuBuffer free-heap iterator", cleanupStart, nodesField);

        VarInsnNode resultLoad = null;
        for (AbstractInsnNode instruction = cleanupIterator.getNext(); instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode load
                    && load.getOpcode() == Opcodes.ILOAD
                    && load.var == 3) {
                resultLoad = load;
            }
        }
        if (resultLoad == null) {
            throw new IllegalStateException("UberGpuBuffer upload result load was not found");
        }

        int nodeCountLocal = method.maxLocals++;
        int cursorLocal = method.maxLocals++;
        int scannedLocal = method.maxLocals++;
        int releasedLocal = method.maxLocals++;
        int pairLocal = method.maxLocals++;
        int allocatorLocal = method.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode retain = new LabelNode();
        LabelNode normalized = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode unchangedResult = new LabelNode();
        InsnList cleanup = new InsnList();
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, nodeCountLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "beginUberNodeCleanup",
                "(Ljava/lang/Object;I)I",
                false));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, scannedLocal));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, releasedLocal));
        cleanup.add(loop);
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "shouldCleanUberNode",
                "(Ljava/lang/Object;)Z",
                false));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "get",
                "(I)Ljava/lang/Object;",
                true));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, pair));
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, pairLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, pairLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, pair, "getFirst", "()Ljava/lang/Object;", false));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, allocator));
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, allocatorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, allocatorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, allocator, "isCompletelyFree", "()Z", false));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, retain));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, pairLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, pair, "getSecond", "()Ljava/lang/Object;", false));
        cleanup.add(new TypeInsnNode(Opcodes.CHECKCAST, heap));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                heap,
                "gpuBuffer",
                "Lcom/mojang/blaze3d/buffers/GpuBuffer;"));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/buffers/GpuBuffer",
                "close",
                "()V",
                false));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new FieldInsnNode(
                Opcodes.GETFIELD, owner, "nodes", "Ljava/util/List;"));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "remove",
                "(I)Ljava/lang/Object;",
                true));
        cleanup.add(new InsnNode(Opcodes.POP));
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(nodeCountLocal, -1));
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(releasedLocal, 1));
        cleanup.add(new JumpInsnNode(Opcodes.GOTO, normalized));
        cleanup.add(retain);
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(cursorLocal, 1));
        cleanup.add(normalized);
        cleanup.add(new org.objectweb.asm.tree.IincInsnNode(scannedLocal, 1));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, done));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loop));
        cleanup.add(new InsnNode(Opcodes.ICONST_0));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        cleanup.add(new JumpInsnNode(Opcodes.GOTO, loop));
        cleanup.add(done);
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, releasedLocal));
        cleanup.add(new JumpInsnNode(Opcodes.IFEQ, unchangedResult));
        cleanup.add(new InsnNode(Opcodes.ICONST_1));
        cleanup.add(new VarInsnNode(Opcodes.ISTORE, 3));
        cleanup.add(unchangedResult);
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, nodeCountLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, scannedLocal));
        cleanup.add(new VarInsnNode(Opcodes.ILOAD, releasedLocal));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "finishUberNodeCleanup",
                "(Ljava/lang/Object;IIII)V",
                false));
        method.instructions.insertBefore(cleanupStart, cleanup);
        for (AbstractInsnNode instruction = cleanupStart; instruction != resultLoad;) {
            AbstractInsnNode next = instruction.getNext();
            method.instructions.remove(instruction);
            instruction = next;
        }
        writeComputeFrames(node, root.resolve(owner + ".class"));
        System.out.println("Bounded Minecraft 26.2 UberGpuBuffer heap cleanup");
    }

    private static FieldInsnNode fieldBefore(AbstractInsnNode instruction) {
        AbstractInsnNode previous = previousOpcode(instruction);
        return previous instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETFIELD ? field : null;
    }

    private static void requireReceiverLoad(
            String target, AbstractInsnNode receiver, FieldInsnNode field) {
        if (!(receiver instanceof VarInsnNode load)
                || load.getOpcode() != Opcodes.ALOAD
                || load.var != 0
                || field == null) {
            throw new IllegalStateException(target + " receiver shape changed");
        }
    }

    private static void patchRegionFileStorageCache(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/world/level/chunk/storage/RegionFileStorage";
        ClassNode node = read(jar, owner + ".class");
        MethodNode getRegionFile = find(
                node,
                "getRegionFile",
                "(Lnet/minecraft/world/level/ChunkPos;)"
                        + "Lnet/minecraft/world/level/chunk/storage/RegionFile;");

        int limits = 0;
        for (AbstractInsnNode instruction : getRegionFile.instructions.toArray()) {
            if (instruction instanceof IntInsnNode integer
                    && integer.getOpcode() == Opcodes.SIPUSH
                    && integer.operand == 256) {
                getRegionFile.instructions.set(
                        integer,
                        new IntInsnNode(
                                Opcodes.BIPUSH, BROWSER_REGION_FILE_CACHE_SIZE));
                limits++;
            }
        }
        requireOne("RegionFileStorage 256-entry cache limit", limits);

        int constants = 0;
        for (FieldNode field : node.fields) {
            if (field.name.equals("MAX_CACHE_SIZE")
                    && field.desc.equals("I")
                    && Integer.valueOf(256).equals(field.value)) {
                field.value = BROWSER_REGION_FILE_CACHE_SIZE;
                constants++;
            }
        }
        requireOne("RegionFileStorage MAX_CACHE_SIZE constant", constants);

        write(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 RegionFileStorage cache to "
                        + BROWSER_REGION_FILE_CACHE_SIZE + " open files");
    }

    private static void patchNativeLibrariesBootstrap(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/platform/NativeLibrariesBootstrap";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "loadLibraries", "()V"));
        replaceBoolean(find(node, "isVulkanLoaderAvailable", "()Z"), false);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Disabled desktop native-library bootstrap for Minecraft 26.2");
    }

    private static void patchPreferredGraphicsApi(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/PreferredGraphicsApi";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "getBackendsToTry",
                "()[Lcom/mojang/blaze3d/systems/GpuBackend;");
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new TypeInsnNode(
                Opcodes.ANEWARRAY, "com/mojang/blaze3d/systems/GpuBackend"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new TypeInsnNode(Opcodes.NEW, "com/mojang/blaze3d/opengl/GlBackend"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "com/mojang/blaze3d/opengl/GlBackend",
                "<init>",
                "()V",
                false));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 5, 1);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Forced Minecraft 26.2 browser graphics backend to OpenGL");
    }

    private static void patchVulkanBackend(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vulkan/VulkanBackend";
        String exception = "com/mojang/blaze3d/systems/BackendCreationException";
        String reason = exception + "$Reason";
        ClassNode node = read(jar, owner + ".class");
        replaceVoid(find(node, "<clinit>", "()V"));
        replaceVoid(find(node, "setWindowHints", "()V"));

        MethodNode check = find(node, "checkBackendAvailable", "()L" + exception + ";");
        InsnList unavailable = new InsnList();
        unavailable.add(new TypeInsnNode(Opcodes.NEW, exception));
        unavailable.add(new InsnNode(Opcodes.DUP));
        unavailable.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        unavailable.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        unavailable.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        unavailable.add(new InsnNode(Opcodes.ARETURN));
        replace(check, unavailable, 4, 0);

        MethodNode create = find(node, "createDevice",
                "(JLcom/mojang/blaze3d/shaders/ShaderSource;"
                        + "Lcom/mojang/blaze3d/shaders/GpuDebugOptions;Ljava/lang/Runnable;)"
                        + "Lcom/mojang/blaze3d/systems/GpuDevice;");
        InsnList reject = new InsnList();
        reject.add(new TypeInsnNode(Opcodes.NEW, exception));
        reject.add(new InsnNode(Opcodes.DUP));
        reject.add(new org.objectweb.asm.tree.LdcInsnNode(
                "Vulkan is unavailable in the browser runtime"));
        reject.add(new FieldInsnNode(
                Opcodes.GETSTATIC, reason, "VULKAN_LOADER_MISSING", "L" + reason + ";"));
        reject.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                exception,
                "<init>",
                "(Ljava/lang/String;L" + reason + ";)V",
                false));
        reject.add(new InsnNode(Opcodes.ATHROW));
        replace(create, reject, 4, create.maxLocals);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchGlDeviceCapabilities(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/opengl/GlDevice";
        ClassNode node = read(jar, owner + ".class");
        MethodNode initializer = find(node, "<clinit>", "()V");
        int returns = 0;
        for (AbstractInsnNode instruction = initializer.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction.getOpcode() != Opcodes.RETURN) {
                continue;
            }
            InsnList disable = new InsnList();
            for (String field : new String[] {
                    "USE_GL_ARB_base_instance",
                    "USE_GL_ARB_draw_indirect",
                    "USE_GL_ARB_multi_draw_indirect",
                    "USE_GL_ARB_shader_draw_parameters"
            }) {
                disable.add(new InsnNode(Opcodes.ICONST_0));
                disable.add(new FieldInsnNode(Opcodes.PUTSTATIC, owner, field, "Z"));
            }
            initializer.instructions.insertBefore(instruction, disable);
            returns++;
        }
        if (returns == 0) {
            throw new IllegalStateException("GlDevice initializer return was not found");
        }
        initializer.maxStack = Math.max(initializer.maxStack, 1);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFramerateLimiter(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/FramerateLimiter";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "limitDisplayFPS", "(I)V");
        int removed = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                method.instructions.remove(call);
                removed++;
            }
            instruction = next;
        }
        if (removed == 0) {
            throw new IllegalStateException("FramerateLimiter spin call was not found");
        }
        write(node, root.resolve(owner + ".class"));
    }

    /**
     * Keeps the 26.2 FAST preset inside the browser profile's low-distance budget.
     *
     * <p>This is deliberately a bytecode overlay rather than a change to Options or the
     * singleplayer distance contract.  The vanilla method is an ordinal switch: only the
     * ordinal-zero FAST arm contains the render/simulation option writes with the 8/6
     * constants.  Match that arm, both option getter calls, their receiver/field shape, and
     * the boxed integer stores before changing exactly those two BIPUSH instructions.  A
     * missing, duplicated, or reshaped target fails closed instead of replacing unrelated
     * integer constants in the preset method.</p>
     */
    private static void patchGraphicsPresetBrowserDistances(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/GraphicsPreset";
        String minecraft = "net/minecraft/client/Minecraft";
        String options = "net/minecraft/client/Options";
        String optionInstance = "Lnet/minecraft/client/OptionInstance;";
        String optionScreen = "Lnet/minecraft/client/gui/screens/options/OptionsSubScreen;";
        ClassNode node = read(jar, owner + ".class");
        MethodNode apply = find(node, "apply", "(L" + minecraft + ";)V");

        TableSwitchInsnNode presetSwitch = null;
        int switches = 0;
        for (AbstractInsnNode instruction : apply.instructions.toArray()) {
            if (instruction instanceof TableSwitchInsnNode table
                    && table.min == 0
                    && table.max == 2) {
                presetSwitch = table;
                switches++;
            }
        }
        requireOne("GraphicsPreset.apply ordinal switch", switches);
        if (presetSwitch == null || presetSwitch.labels.size() != 3) {
            throw new IllegalStateException("GraphicsPreset.apply FAST switch arm is missing");
        }

        int fastStart = apply.instructions.indexOf(presetSwitch.labels.get(0));
        if (fastStart < 0) {
            throw new IllegalStateException("GraphicsPreset.apply FAST switch label is missing");
        }
        AbstractInsnNode fastDistance = nextOpcode(presetSwitch.labels.get(0));
        if (!(fastDistance instanceof IntInsnNode integer)
                || integer.getOpcode() != Opcodes.BIPUSH
                || integer.operand != 8) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST arm distance preamble changed");
        }
        AbstractInsnNode fastDistanceLocal = nextOpcode(fastDistance);
        if (!(fastDistanceLocal instanceof VarInsnNode local)
                || local.getOpcode() != Opcodes.ISTORE
                || local.var != 4) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST arm distance local changed");
        }

        int fastEnd = apply.instructions.size();
        for (LabelNode branch : presetSwitch.labels) {
            int index = apply.instructions.indexOf(branch);
            if (index > fastStart && index < fastEnd) {
                fastEnd = index;
            }
        }
        int defaultIndex = apply.instructions.indexOf(presetSwitch.dflt);
        if (defaultIndex > fastStart && defaultIndex < fastEnd) {
            fastEnd = defaultIndex;
        }
        if (fastEnd == apply.instructions.size()) {
            throw new IllegalStateException("GraphicsPreset.apply FAST arm end is missing");
        }

        IntInsnNode renderDistance = findGraphicsPresetDistanceConstant(
                apply,
                fastStart,
                fastEnd,
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "renderDistance",
                8);
        IntInsnNode simulationDistance = findGraphicsPresetDistanceConstant(
                apply,
                fastStart,
                fastEnd,
                options,
                optionInstance,
                optionScreen,
                minecraft,
                owner,
                "simulationDistance",
                6);
        renderDistance.operand = 6;
        simulationDistance.operand = 4;

        write(node, root.resolve(owner + ".class"));
        System.out.println(
                "Bounded Minecraft 26.2 FAST graphics preset distances to render=6 simulation=4");
    }

    private static IntInsnNode findGraphicsPresetDistanceConstant(
            MethodNode method,
            int start,
            int end,
            String options,
            String optionInstance,
            String optionScreen,
            String minecraft,
            String graphicsPreset,
            String getter,
            int expected) {
        int getterCount = 0;
        IntInsnNode constant = null;
        AbstractInsnNode[] instructions = method.instructions.toArray();
        for (int index = start; index < end; index++) {
            AbstractInsnNode instruction = instructions[index];
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(options)
                    || !call.name.equals(getter)
                    || !call.desc.equals("()" + optionInstance)) {
                continue;
            }
            getterCount++;
            AbstractInsnNode optionsField = previousOpcode(call);
            AbstractInsnNode minecraftLoad = previousOpcode(optionsField);
            AbstractInsnNode screenLoad = previousOpcode(minecraftLoad);
            if (!(optionsField instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.GETFIELD
                    || !field.owner.equals(minecraft)
                    || !field.name.equals("options")
                    || !field.desc.equals("L" + options + ";")
                    || !(minecraftLoad instanceof VarInsnNode minecraftReceiver)
                    || minecraftReceiver.getOpcode() != Opcodes.ALOAD
                    || minecraftReceiver.var != 1
                    || !(screenLoad instanceof VarInsnNode screenReceiver)
                    || screenReceiver.getOpcode() != Opcodes.ALOAD
                    || screenReceiver.var != 2) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " receiver shape changed");
            }
            AbstractInsnNode value = nextOpcode(call);
            if (!(value instanceof IntInsnNode integer)
                    || integer.getOpcode() != Opcodes.BIPUSH
                    || integer.operand != expected) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " constant shape changed");
            }
            AbstractInsnNode boxed = nextOpcode(value);
            if (!(boxed instanceof MethodInsnNode box)
                    || box.getOpcode() != Opcodes.INVOKESTATIC
                    || !box.owner.equals("java/lang/Integer")
                    || !box.name.equals("valueOf")
                    || !box.desc.equals("(I)Ljava/lang/Integer;")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " boxing shape changed");
            }
            AbstractInsnNode setter = nextOpcode(boxed);
            if (!(setter instanceof MethodInsnNode set)
                    || set.getOpcode() != Opcodes.INVOKESTATIC
                    || !set.owner.equals(graphicsPreset)
                    || !set.name.equals("set")
                    || !set.desc.equals("(" + optionScreen + optionInstance
                            + "Ljava/lang/Object;)V")) {
                throw new IllegalStateException(
                        "GraphicsPreset.apply FAST " + getter + " setter shape changed");
            }
            constant = integer;
        }
        requireOne("GraphicsPreset.apply FAST " + getter + " target", getterCount);
        if (constant == null) {
            throw new IllegalStateException(
                    "GraphicsPreset.apply FAST " + getter + " target is missing");
        }
        return constant;
    }

    private static void patchGlBufferMappedViewRanges(String jar, Path root) throws IOException {
        String directOwner = "com/mojang/blaze3d/opengl/GlBuffer$Direct";
        String closeOwner = directOwner + "$1";
        String mappedView = "com/mojang/blaze3d/buffers/GpuBufferSlice$MappedView";

        ClassNode direct = read(jar, directOwner + ".class");
        MethodNode map = find(direct, "map", "(JJZZ)L" + mappedView + ";");
        int constructors = 0;
        for (AbstractInsnNode instruction : map.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals(closeOwner)
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(L" + directOwner + ";)V")) {
                continue;
            }
            InsnList range = new InsnList();
            range.add(new VarInsnNode(Opcodes.LLOAD, 1));
            range.add(new VarInsnNode(Opcodes.LLOAD, 3));
            map.instructions.insertBefore(call, range);
            call.desc = "(L" + directOwner + ";JJ)V";
            constructors++;
        }
        requireOne("GlBuffer.Direct mapped-view close constructor", constructors);
        map.maxStack = Math.max(map.maxStack, 8);

        ClassNode close = read(jar, closeOwner + ".class");
        close.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedOffset", "J", null, null));
        close.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "mappedLength", "J", null, null));

        MethodNode constructor = find(close, "<init>", "(L" + directOwner + ";)V");
        constructor.desc = "(L" + directOwner + ";JJ)V";
        InsnList init = new InsnList();
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.add(new FieldInsnNode(
                Opcodes.PUTFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new InsnNode(Opcodes.ICONST_0));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "closed", "Z"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.LLOAD, 2));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "mappedOffset", "J"));
        init.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.add(new VarInsnNode(Opcodes.LLOAD, 4));
        init.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "mappedLength", "J"));
        init.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, init, 3, 6);

        MethodNode run = find(close, "run", "()V");
        LabelNode active = new LabelNode();
        LabelNode unmap = new LabelNode();
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "closed", "Z"));
        code.add(new JumpInsnNode(Opcodes.IFEQ, active));
        code.add(new InsnNode(Opcodes.RETURN));
        code.add(active);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new FieldInsnNode(Opcodes.PUTFIELD, closeOwner, "closed", "Z"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, directOwner, "mappingFlags", "I"));
        code.add(new IntInsnNode(Opcodes.BIPUSH, 16));
        code.add(new InsnNode(Opcodes.IAND));
        code.add(new JumpInsnNode(Opcodes.IFEQ, unmap));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                directOwner,
                "dsa",
                "Lcom/mojang/blaze3d/opengl/DirectStateAccess;"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "handle", "()I", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "mappedOffset", "J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, closeOwner, "mappedLength", "J"));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "usage", "()I", false));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "com/mojang/blaze3d/opengl/DirectStateAccess",
                "flushMappedBufferRange",
                "(IJJI)V",
                false));
        code.add(unmap);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(
                Opcodes.GETFIELD, closeOwner, "this$0", "L" + directOwner + ";"));
        code.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, directOwner, "unmap", "()V", false));
        code.add(new InsnNode(Opcodes.RETURN));
        replace(run, code, 7, 1);

        writeComputeFrames(direct, root.resolve(directOwner + ".class"));
        writeComputeFrames(close, root.resolve(closeOwner + ".class"));
        System.out.println("Patched GlBuffer mapped views to flush only their written ranges");
    }

    private static void patchLiveFrameTargeting(String jar, Path root)
            throws IOException {
        String minecraftOwner = "net/minecraft/client/Minecraft";
        ClassNode minecraft = read(jar, minecraftOwner + ".class");
        MethodNode renderFrame = find(minecraft, "renderFrame", "(Z)V");
        int deferred = 0;
        for (AbstractInsnNode instruction = renderFrame.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(minecraftOwner)
                    && call.name.equals("pick")
                    && call.desc.equals("(F)V")) {
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserTargeting";
                call.name = "deferFramePick";
                call.desc = "(Lnet/minecraft/client/Minecraft;F)V";
                call.itf = false;
                deferred++;
            }
        }
        if (deferred != 1) {
            throw new IllegalStateException(
                    "Minecraft.renderFrame targeting deferral changed: " + deferred);
        }
        write(minecraft, root.resolve(minecraftOwner + ".class"));

        String owner = "net/minecraft/client/renderer/GameRenderer";
        ClassNode node = read(jar, owner + ".class");
        MethodNode extract = find(
                node,
                "extract",
                "(Lnet/minecraft/client/DeltaTracker;Z)V");
        MethodInsnNode extractCamera = null;
        MethodInsnNode extractLevel = null;
        for (AbstractInsnNode instruction = extract.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals(owner)
                    && call.name.equals("extractCamera")
                    && call.desc.equals("(Lnet/minecraft/client/DeltaTracker;FF)V")) {
                if (extractCamera != null) {
                    throw new IllegalStateException(
                            "GameRenderer.extract has multiple extractCamera calls");
                }
                extractCamera = call;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                    && call.owner.equals("net/minecraft/client/renderer/extract/LevelExtractor")
                    && call.name.equals("extract")
                    && call.desc.equals("(Lnet/minecraft/client/DeltaTracker;"
                            + "Lnet/minecraft/client/Camera;F)V")) {
                if (extractLevel != null) {
                    throw new IllegalStateException(
                            "GameRenderer.extract has multiple level extraction calls");
                }
                extractLevel = call;
            }
        }
        if (extractCamera == null) {
            throw new IllegalStateException(
                    "GameRenderer.extract current camera observation point was not found");
        }
        if (extractLevel == null) {
            throw new IllegalStateException(
                    "GameRenderer.extract current level extraction point was not found");
        }
        AbstractInsnNode worldPartialTick = extractLevel.getPrevious();
        while (worldPartialTick != null && worldPartialTick.getOpcode() < 0) {
            worldPartialTick = worldPartialTick.getPrevious();
        }
        if (!(worldPartialTick instanceof VarInsnNode worldPartialTickLoad)
                || worldPartialTickLoad.getOpcode() != Opcodes.FLOAD) {
            throw new IllegalStateException(
                    "GameRenderer.extract world partial tick load was not found");
        }
        AbstractInsnNode cameraPartialTick = extractCamera.getPrevious();
        while (cameraPartialTick != null && cameraPartialTick.getOpcode() < 0) {
            cameraPartialTick = cameraPartialTick.getPrevious();
        }
        if (!(cameraPartialTick instanceof VarInsnNode cameraPartialTickLoad)
                || cameraPartialTickLoad.getOpcode() != Opcodes.FLOAD
                || cameraPartialTickLoad.var == worldPartialTickLoad.var) {
            throw new IllegalStateException(
                    "GameRenderer.extract camera partial tick load was not found");
        }

        InsnList refresh = new InsnList();
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "minecraft",
                "Lnet/minecraft/client/Minecraft;"));
        refresh.add(new VarInsnNode(Opcodes.ALOAD, 0));
        refresh.add(new FieldInsnNode(
                Opcodes.GETFIELD,
                owner,
                "mainCamera",
                "Lnet/minecraft/client/Camera;"));
        refresh.add(new VarInsnNode(Opcodes.FLOAD, cameraPartialTickLoad.var));
        refresh.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserTargeting",
                "refreshFramePick",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/Camera;F)V",
                false));
        extract.instructions.insert(extractCamera, refresh);
        extract.maxStack = Math.max(extract.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Moved Minecraft 26.2 frame targeting after camera extraction");
    }

    private static void patchSectionRenderTaskRetryYields(String jar, Path root)
            throws IOException {
        String prefix = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection$";
        for (String task : new String[] {"CompileTask", "ResortTransparencyTask"}) {
            String owner = prefix + task;
            ClassNode node = read(jar, owner + ".class");
            MethodNode method = find(node, "doTask",
                    "(Lnet/minecraft/client/renderer/SectionBufferBuilderPack;)"
                            + "Lnet/minecraft/client/renderer/chunk/"
                            + "SectionRenderDispatcher$RenderSection$SectionTask$"
                            + "SectionTaskResult;");
            int expectedResultLocal = task.equals("CompileTask") ? 12 : 9;
            int patched = replaceRenderThreadRetryWithYield(method, expectedResultLocal);
            int cancelled = replaceSpinWaitWithBoundedCancellation(method, owner);
            int cleared = clearUploadRetryOnReturns(method);
            addUploadRetryExceptionCleanup(method);
            requireOne(owner + " upload retry yield", patched);
            requireOne(owner + " bounded upload retry cancellation", cancelled);
            if (cleared < 2) {
                throw new IllegalStateException(owner + " return cleanup changed: " + cleared);
            }
            method.maxStack = Math.max(method.maxStack, 3);
            writeComputeFrames(node, root.resolve(owner + ".class"));
        }
    }

    private static int replaceRenderThreadRetryWithYield(
            MethodNode method, int expectedResultLocal) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("com/mojang/blaze3d/systems/RenderSystem")
                    || !call.name.equals("isOnRenderThread")
                    || !call.desc.equals("()Z")
                    || !(nextOpcode(call) instanceof JumpInsnNode retry)
                    || retry.getOpcode() != Opcodes.IFNE) {
                continue;
            }
            AbstractInsnNode uploadResultJump = previousOpcode(call);
            AbstractInsnNode uploadResultLoad = previousOpcode(uploadResultJump);
            int callIndex = method.instructions.indexOf(call);
            int retryIndex = method.instructions.indexOf(retry.label);
            if (!(uploadResultJump instanceof JumpInsnNode uploadSucceeded)
                    || uploadSucceeded.getOpcode() != Opcodes.IFNE
                    || !(uploadResultLoad instanceof VarInsnNode resultLoad)
                    || resultLoad.getOpcode() != Opcodes.ILOAD
                    || resultLoad.var != expectedResultLocal
                    || retryIndex < 0
                    || retryIndex >= callIndex) {
                throw new IllegalStateException(
                        method.name + " upload retry control-flow shape changed");
            }
            method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
            call.owner = "dev/gaius/browser/BrowserRenderScheduler";
            call.name = "awaitUploadRetry";
            call.desc = "(Ljava/lang/Object;)Z";
            patched++;
        }
        return patched;
    }

    private static int replaceSpinWaitWithBoundedCancellation(
            MethodNode method, String owner) {
        int replaced = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null;) {
            AbstractInsnNode next = instruction.getNext();
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/lang/Thread")
                    && call.name.equals("onSpinWait")
                    && call.desc.equals("()V")) {
                InsnList cancel = new InsnList();
                cancel.add(new VarInsnNode(Opcodes.ALOAD, 0));
                cancel.add(new MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL,
                        owner,
                        "cancel",
                        "()V",
                        false));
                method.instructions.insertBefore(call, cancel);
                method.instructions.remove(call);
                replaced++;
            }
            instruction = next;
        }
        return replaced;
    }

    private static int clearUploadRetryOnReturns(MethodNode method) {
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction.getOpcode() != Opcodes.ARETURN) {
                continue;
            }
            InsnList clear = new InsnList();
            clear.add(new VarInsnNode(Opcodes.ALOAD, 0));
            clear.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "clearUploadRetry",
                    "(Ljava/lang/Object;)V",
                    false));
            method.instructions.insertBefore(instruction, clear);
            patched++;
        }
        return patched;
    }

    private static void addUploadRetryExceptionCleanup(MethodNode method) {
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();
        method.instructions.insertBefore(method.instructions.getFirst(), start);
        int throwableLocal = method.maxLocals++;
        InsnList cleanup = new InsnList();
        cleanup.add(end);
        cleanup.add(handler);
        cleanup.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, 0));
        cleanup.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserRenderScheduler",
                "clearUploadRetry",
                "(Ljava/lang/Object;)V",
                false));
        cleanup.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        cleanup.add(new InsnNode(Opcodes.ATHROW));
        method.instructions.add(cleanup);
        method.tryCatchBlocks.add(new TryCatchBlockNode(
                start, end, handler, "java/lang/Throwable"));
        method.maxStack = Math.max(method.maxStack, 1);
    }

    private static void patchSectionRenderEmergencyUpload(String jar, Path root)
            throws IOException {
        String owner = "net/minecraft/client/renderer/chunk/"
                + "SectionRenderDispatcher$RenderSection";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(
                node,
                "addSectionBuffersToUberBuffer",
                "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;"
                        + "Lnet/minecraft/client/renderer/chunk/CompiledSectionMesh;"
                        + "Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;)Z");
        int patched = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(
                            "net/minecraft/client/renderer/chunk/SectionRenderDispatcher")
                    || !call.name.equals("uploadTerrainBuffersToGpu")
                    || !call.desc.equals("()V")) {
                continue;
            }
            method.instructions.insertBefore(call, new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserRenderScheduler",
                    "requestEmergencyUpload",
                    "()V",
                    false));
            patched++;
        }
        requireOne("RenderSection staging-capacity emergency upload", patched);
        write(node, root.resolve(owner + ".class"));
        System.out.println("Guarded current section staging retries with one progress upload");
    }

    private static void patchStagingBuffer(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vertex/StagingBuffer$Cpu";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "copyTo",
                "(Lcom/mojang/blaze3d/systems/CommandEncoder;"
                        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;JJJ)V");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/nio/ByteBuffer",
                "slice",
                "(II)Ljava/nio/ByteBuffer;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "slice",
                "(Ljava/nio/ByteBuffer;II)Ljava/nio/ByteBuffer;");
        requireOne("StagingBuffer ByteBuffer.slice", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchTemplateSource(String jar, Path root) throws IOException {
        String owner =
                "net/minecraft/world/level/levelgen/structure/templatesystem/loader/TemplateSource";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "readTextStructure",
                "(Ljava/io/InputStream;)Lnet/minecraft/nbt/CompoundTag;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEVIRTUAL,
                "java/io/Reader",
                "readAllAsString",
                "()Ljava/lang/String;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "readAll",
                "(Ljava/io/Reader;)Ljava/lang/String;");
        requireOne("TemplateSource Reader.readAllAsString", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchRemoteFriendList(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler";
        ClassNode node = read(jar, owner + ".class");
        MethodNode constructor = find(node, "<init>",
                "(Lcom/mojang/authlib/yggdrasil/FriendsService;"
                        + "Lnet/minecraft/client/Minecraft;)V");
        int types = 0;
        int calls = 0;
        for (AbstractInsnNode instruction = constructor.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/CopyOnWriteArraySet")) {
                type.desc = "java/util/HashSet";
                types++;
            } else if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && call.owner.equals("java/util/concurrent/CopyOnWriteArraySet")
                    && call.name.equals("<init>")
                    && call.desc.equals("()V")) {
                call.owner = "java/util/HashSet";
                calls++;
            }
        }
        requireOne("RemoteFriendListUpdateHandler set allocation", types);
        requireOne("RemoteFriendListUpdateHandler set constructor", calls);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchNativeModuleLister(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/NativeModuleLister";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "tryGetModuleVersion",
                "(Ljava/lang/String;)Ljava/util/Optional;");
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Optional",
                "empty",
                "()Ljava/util/Optional;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 1);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchMacosUtil(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/platform/MacosUtil";
        ClassNode node = read(jar, owner + ".class");
        for (String descriptor : new String[] {
                "(Lcom/mojang/blaze3d/platform/Window;)Ljava/util/Optional;",
                "(J)Ljava/util/Optional;"
        }) {
            MethodNode method = find(node, "getNsWindow", descriptor);
            InsnList code = new InsnList();
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Optional",
                    "empty",
                    "()Ljava/util/Optional;",
                    false));
            code.add(new InsnNode(Opcodes.ARETURN));
            replace(method, code, 1, descriptor.equals("(J)Ljava/util/Optional;") ? 2 : 1);
        }
        replaceVoid(find(node, "setWindowColorSpaceForOpenGLBecauseGLFWDoesnt", "(J)V"));
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchVulkanDebug(String jar, Path root) throws IOException {
        String owner = "com/mojang/blaze3d/vulkan/VulkanDebug";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "create",
                "(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/blaze3d/vulkan/VulkanDebug;");
        InsnList code = new InsnList();
        code.add(new TypeInsnNode(Opcodes.NEW, owner + "$Disabled"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, owner + "$Disabled", "<init>", "()V", false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 2, 4);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchDetailedMemoryDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntryDetailedMemory";
        ClassNode node = read(jar, owner + ".class");
        node.fields.removeIf(field -> field.name.equals("memoryBean")
                && field.desc.equals("Ljava/lang/management/MemoryMXBean;"));

        MethodNode constructor = find(node, "<init>", "()V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 1, 1);

        replaceVoid(find(node, "display",
                "(Lnet/minecraft/client/gui/components/debug/DebugScreenDisplayer;"
                        + "Lnet/minecraft/world/level/Level;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;"
                        + "Lnet/minecraft/world/level/chunk/LevelChunk;)V"));
        replaceBoolean(find(node, "isAllowed", "(Z)Z"), false);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchSystemSpecsDebug(String jar, Path root) throws IOException {
        String owner = "net/minecraft/client/gui/components/debug/DebugEntrySystemSpecs";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "firstLine", "(Ljava/lang/String;)Ljava/lang/String;");
        InsnList code = new InsnList();
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "firstLine",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false));
        code.add(new InsnNode(Opcodes.ARETURN));
        replace(method, code, 1, 2);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFileFixAccess(String jar, Path root) throws IOException {
        String providerOwner = "net/minecraft/util/filefix/access/FileAccessProvider";
        ClassNode provider = read(jar, providerOwner + ".class");
        boolean removedField = provider.fields.removeIf(field -> field.name.equals("baseDirectory")
                && field.desc.equals("Ljava/lang/ScopedValue;"));
        if (!removedField) {
            throw new IllegalStateException("FileAccessProvider ScopedValue field was not found");
        }
        provider.fields.add(new FieldNode(
                Opcodes.ACC_PRIVATE,
                "gaius$baseDirectory",
                "Ljava/nio/file/Path;",
                null,
                null));
        MethodNode constructor = find(provider, "<init>", "(I)V");
        InsnList constructorCode = new InsnList();
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        constructorCode.add(new InsnNode(Opcodes.DUP));
        constructorCode.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "accessedFiles", "Ljava/util/List;"));
        constructorCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructorCode.add(new VarInsnNode(Opcodes.ILOAD, 1));
        constructorCode.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "dataVersion", "I"));
        constructorCode.add(new InsnNode(Opcodes.RETURN));
        replace(constructor, constructorCode, 3, 2);

        provider.methods.remove(find(provider, "baseDirectory", "()Ljava/lang/ScopedValue;"));
        MethodNode setter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                null,
                null);
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setter.instructions.add(new FieldInsnNode(
                Opcodes.PUTFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        setter.instructions.add(new InsnNode(Opcodes.RETURN));
        setter.maxStack = 2;
        setter.maxLocals = 2;
        provider.methods.add(setter);
        MethodNode getter = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "gaius$getBaseDirectory",
                "()Ljava/nio/file/Path;",
                null,
                null);
        getter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getter.instructions.add(new FieldInsnNode(
                Opcodes.GETFIELD, providerOwner, "gaius$baseDirectory", "Ljava/nio/file/Path;"));
        getter.instructions.add(new InsnNode(Opcodes.ARETURN));
        getter.maxStack = 1;
        getter.maxLocals = 1;
        provider.methods.add(getter);
        write(provider, root.resolve(providerOwner + ".class"));

        String accessOwner = "net/minecraft/util/filefix/access/FileAccess";
        ClassNode access = read(jar, accessOwner + ".class");
        MethodNode get = find(access, "get", "()Ljava/util/List;");
        boolean patchedGet = false;
        for (AbstractInsnNode instruction = get.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                    || !call.owner.equals(providerOwner)
                    || !call.name.equals("baseDirectory")
                    || !call.desc.equals("()Ljava/lang/ScopedValue;")) {
                continue;
            }
            AbstractInsnNode scopedGet = nextOpcode(call);
            AbstractInsnNode pathCast = nextOpcode(scopedGet);
            if (!(scopedGet instanceof MethodInsnNode getCall)
                    || !getCall.owner.equals("java/lang/ScopedValue")
                    || !getCall.name.equals("get")
                    || !getCall.desc.equals("()Ljava/lang/Object;")
                    || !(pathCast instanceof TypeInsnNode cast)
                    || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals("java/nio/file/Path")) {
                throw new IllegalStateException("FileAccess ScopedValue read shape changed");
            }
            call.name = "gaius$getBaseDirectory";
            call.desc = "()Ljava/nio/file/Path;";
            get.instructions.remove(scopedGet);
            get.instructions.remove(pathCast);
            patchedGet = true;
            break;
        }
        if (!patchedGet) {
            throw new IllegalStateException("FileAccess base-directory read was not found");
        }
        write(access, root.resolve(accessOwner + ".class"));

        String modifyOwner = "net/minecraft/util/filefix/operations/ModifyContent";
        ClassNode modify = read(jar, modifyOwner + ".class");
        MethodNode fix = find(modify, "fix",
                "(Ljava/nio/file/Path;Lnet/minecraft/util/worldupdate/UpgradeProgress;)V");
        InsnList fixCode = new InsnList();
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 1));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                providerOwner,
                "gaius$setBaseDirectory",
                "(Ljava/nio/file/Path;)V",
                false));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fixFunction",
                "Lnet/minecraft/util/filefix/operations/ModifyContent$FixFunction;"));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 2));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEINTERFACE,
                "net/minecraft/util/filefix/operations/ModifyContent$FixFunction",
                "run",
                "(Lnet/minecraft/util/worldupdate/UpgradeProgress;)V",
                true));
        fixCode.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fixCode.add(new FieldInsnNode(
                Opcodes.GETFIELD, modifyOwner, "fileAccessProvider",
                "L" + providerOwner + ";"));
        fixCode.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL, providerOwner, "close", "()V", false));
        fixCode.add(new InsnNode(Opcodes.RETURN));
        replace(fix, fixCode, 2, 3);
        write(modify, root.resolve(modifyOwner + ".class"));
        System.out.println("Replaced Minecraft 26.2 ScopedValue file-fix context");
    }

    private static void patchCopyOnWriteFileSystem(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFileSystem";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "hardLinkFiles", "(Ljava/util/List;)V");
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("java/nio/file/Files")
                    && call.name.equals("createLink")
                    && call.desc.equals(
                            "(Ljava/nio/file/Path;Ljava/nio/file/Path;)Ljava/nio/file/Path;")) {
                InsnList prepareCopy = new InsnList();
                prepareCopy.add(new InsnNode(Opcodes.SWAP));
                prepareCopy.add(new InsnNode(Opcodes.ICONST_0));
                prepareCopy.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/nio/file/CopyOption"));
                method.instructions.insertBefore(call, prepareCopy);
                call.name = "copy";
                call.desc = "(Ljava/nio/file/Path;Ljava/nio/file/Path;"
                        + "[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;";
                replacements++;
            }
        }
        if (replacements == 0) {
            throw new IllegalStateException("CopyOnWriteFileSystem hard-link calls were not found");
        }
        method.maxStack = Math.max(method.maxStack, 3);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchFileFixerUpperHardLinks(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/FileFixerUpper";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "supportsHardLinks", "(Ljava/nio/file/Path;)Z");
        replaceBoolean(method, false);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchIdentifierResolveAgainst(String jar, Path root) throws IOException {
        String owner = "net/minecraft/resources/Identifier";
        ClassNode node = read(jar, owner + ".class");
        MethodNode method = find(node, "resolveAgainst",
                "(Ljava/nio/file/Path;)Ljava/nio/file/Path;");
        int replaced = replaceCall(
                method,
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "resolve",
                "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                Opcodes.INVOKESTATIC,
                JDK_COMPAT,
                "resolve",
                "(Ljava/nio/file/Path;Ljava/lang/String;[Ljava/lang/String;)"
                        + "Ljava/nio/file/Path;");
        requireOne("Identifier Path.resolve(String, String[])", replaced);
        write(node, root.resolve(owner + ".class"));
    }

    private static void patchCopyOnWriteProvider(String jar, Path root) throws IOException {
        String owner = "net/minecraft/util/filefix/virtualfilesystem/CopyOnWriteFSProvider";
        ClassNode node = read(jar, owner + ".class");
        MethodNode move = find(node, "move",
                "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V");
        TypeInsnNode unsupported = null;
        for (AbstractInsnNode instruction = move.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/nio/file/AtomicMoveNotSupportedException")) {
                unsupported = type;
                break;
            }
        }
        if (unsupported == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move branch was not found");
        }
        JumpInsnNode branch = null;
        for (AbstractInsnNode instruction = unsupported.getPrevious(); instruction != null;
                instruction = instruction.getPrevious()) {
            if (instruction instanceof JumpInsnNode jump) {
                branch = jump;
                break;
            }
        }
        if (branch == null) {
            throw new IllegalStateException("CopyOnWriteFSProvider atomic-move jump was not found");
        }
        AbstractInsnNode cursor = branch.getNext();
        while (cursor != null && cursor != branch.label) {
            AbstractInsnNode next = cursor.getNext();
            move.instructions.remove(cursor);
            cursor = next;
        }
        move.instructions.set(branch, new InsnNode(Opcodes.POP));

        MethodNode initializer = find(node, "<clinit>", "()V");
        InsnList initializerCode = new InsnList();
        initializerCode.add(new InsnNode(Opcodes.ACONST_NULL));
        initializerCode.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                owner,
                "DUMMY_DIRECTORY_VIEW",
                "Ljava/nio/file/attribute/BasicFileAttributeView;"));
        initializerCode.add(new InsnNode(Opcodes.RETURN));
        replace(initializer, initializerCode, 1, 0);
        write(node, root.resolve(owner + ".class"));
    }

    private static int replaceCall(
            MethodNode method,
            int oldOpcode,
            String oldOwner,
            String oldName,
            String oldDescriptor,
            int newOpcode,
            String newOwner,
            String newName,
            String newDescriptor) {
        int replacements = 0;
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == oldOpcode
                    && call.owner.equals(oldOwner)
                    && call.name.equals(oldName)
                    && call.desc.equals(oldDescriptor)) {
                call.setOpcode(newOpcode);
                call.owner = newOwner;
                call.name = newName;
                call.desc = newDescriptor;
                call.itf = false;
                replacements++;
            }
        }
        return replacements;
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getNext();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction == null ? null : instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private static void replaceVoid(MethodNode method) {
        InsnList code = new InsnList();
        code.add(new InsnNode(Opcodes.RETURN));
        replace(method, code, 0, method.maxLocals);
    }

    private static void replaceBoolean(MethodNode method, boolean value) {
        InsnList code = new InsnList();
        code.add(new InsnNode(value ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        code.add(new InsnNode(Opcodes.IRETURN));
        replace(method, code, 1, method.maxLocals);
    }

    private static void replace(
            MethodNode method, InsnList code, int maxStack, int maxLocals) {
        method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
        method.instructions = code;
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static void requireOne(String target, int count) {
        if (count != 1) {
            throw new IllegalStateException(target + " changed: " + count);
        }
    }

    private static void requireWorldgenLoopPulses(
            String target, MethodNode method, int expectedPulses, String pulseMethod) {
        int pulses = 0;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof JumpInsnNode jump
                    && method.instructions.indexOf(jump.label)
                            < method.instructions.indexOf(instruction)) {
                AbstractInsnNode previous = previousOpcode(jump);
                if (!(previous instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals(WORLDGEN_SCHEDULER)
                        || !call.name.equals(pulseMethod)
                        || !call.desc.equals("()V")) {
                    method.instructions.insertBefore(
                            jump,
                            new MethodInsnNode(
                                    Opcodes.INVOKESTATIC,
                                    WORLDGEN_SCHEDULER,
                                    pulseMethod,
                                    "()V",
                                    false));
                }
                pulses++;
            }
        }
        if (pulses != expectedPulses) {
            throw new IllegalStateException(
                    target + " loop backedges changed: " + pulses
                            + " (expected " + expectedPulses + ")");
        }
    }

    private static MethodNode find(ClassNode node, String name, String descriptor) {
        return node.methods.stream()
                .filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        node.name + "." + name + descriptor + " was not found"));
    }

    private static ClassNode read(String jarPath, String entryName) throws IOException {
        byte[] bytes;
        try (ZipFile jar = new ZipFile(jarPath)) {
            var entry = jar.getEntry(entryName);
            if (entry == null) {
                throw new IllegalStateException(entryName + " was not found");
            }
            try (var stream = jar.getInputStream(entry)) {
                bytes = stream.readAllBytes();
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
        ClassWriter writer = new ClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
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
