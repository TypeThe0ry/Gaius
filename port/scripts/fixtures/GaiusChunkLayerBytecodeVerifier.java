import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

public final class GaiusChunkLayerBytecodeVerifier {
    private static final String BROWSER_WORLDGEN_SCHEDULER =
            "dev/gaius/browser/BrowserWorldgenScheduler";
    private static final String SCHEDULE_CHUNK_IN_LAYER_DESCRIPTOR =
            "(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Z"
                    + "Lnet/minecraft/server/level/GenerationChunkHolder;)Z";

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static AbstractInsnNode firstExecutable(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getNext();
        }
        return instruction;
    }

    private static AbstractInsnNode nextExecutable(AbstractInsnNode instruction) {
        return instruction == null ? null : firstExecutable(instruction.getNext());
    }

    private static AbstractInsnNode previousExecutable(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        instruction = instruction.getPrevious();
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getPrevious();
        }
        return instruction;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(candidate -> candidate.name.equals(name))
                .findFirst().orElseThrow(() -> new IllegalStateException("missing " + name));
    }

    private static MethodInsnNode firstCall(
            AbstractInsnNode startExclusive, AbstractInsnNode stopExclusive) {
        for (AbstractInsnNode instruction = nextExecutable(startExclusive);
                instruction != null && instruction != stopExclusive;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof MethodInsnNode call) return call;
        }
        return null;
    }

    private static boolean isTerminal(AbstractInsnNode instruction) {
        if (instruction == null) return true;
        return switch (instruction.getOpcode()) {
            case Opcodes.ATHROW,
                    Opcodes.IRETURN,
                    Opcodes.LRETURN,
                    Opcodes.FRETURN,
                    Opcodes.DRETURN,
                    Opcodes.ARETURN,
                    Opcodes.RETURN,
                    Opcodes.RET -> true;
            default -> false;
        };
    }

    private static void addSuccessor(List<AbstractInsnNode> successors,
            AbstractInsnNode instruction) {
        if (instruction != null) successors.add(instruction);
    }

    private static List<AbstractInsnNode> successors(AbstractInsnNode instruction) {
        List<AbstractInsnNode> successors = new ArrayList<>();
        if (instruction instanceof JumpInsnNode jump) {
            addSuccessor(successors, firstExecutable(jump.label));
            if (jump.getOpcode() != Opcodes.GOTO && jump.getOpcode() != Opcodes.JSR) {
                addSuccessor(successors, nextExecutable(instruction));
            }
        } else if (instruction instanceof TableSwitchInsnNode tableSwitch) {
            addSuccessor(successors, firstExecutable(tableSwitch.dflt));
            for (LabelNode label : tableSwitch.labels) {
                addSuccessor(successors, firstExecutable(label));
            }
        } else if (instruction instanceof LookupSwitchInsnNode lookupSwitch) {
            addSuccessor(successors, firstExecutable(lookupSwitch.dflt));
            for (LabelNode label : lookupSwitch.labels) {
                addSuccessor(successors, firstExecutable(label));
            }
        } else if (!isTerminal(instruction)) {
            addSuccessor(successors, nextExecutable(instruction));
        }
        return successors;
    }

    private static boolean isBrowserWorldgenPulse(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && BROWSER_WORLDGEN_SCHEDULER.equals(call.owner)
                && call.name.equals("pulse")
                && call.desc.equals("()V");
    }

    private static boolean everyPathHitsPulse(AbstractInsnNode instruction,
            java.util.IdentityHashMap<AbstractInsnNode, Boolean> memo,
            java.util.Set<AbstractInsnNode> active) {
        if (isBrowserWorldgenPulse(instruction)) return true;
        if (isTerminal(instruction)) return false;
        Boolean cached = memo.get(instruction);
        if (cached != null) return cached;
        if (!active.add(instruction)) return false;
        List<AbstractInsnNode> next = successors(instruction);
        boolean allPathsHit = !next.isEmpty();
        for (AbstractInsnNode successor : next) {
            if (!everyPathHitsPulse(successor, memo, active)) {
                allPathsHit = false;
                break;
            }
        }
        active.remove(instruction);
        memo.put(instruction, allPathsHit);
        return allPathsHit;
    }

    private static void verifySuccessfulHolderPulsePaths(MethodNode layer, String owner) {
        int successfulHolderBranches = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.name.equals("scheduleChunkInLayer")) continue;
            require(call.getOpcode() == Opcodes.INVOKEVIRTUAL
                            && owner.equals(call.owner)
                            && SCHEDULE_CHUNK_IN_LAYER_DESCRIPTOR.equals(call.desc),
                    "scheduleLayer holder submission call shape changed");
            AbstractInsnNode branch = nextExecutable(call);
            require(branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ,
                    "scheduleLayer holder result must branch on success");
            AbstractInsnNode successPath = nextExecutable(branch);
            require(successPath != null,
                    "scheduleLayer successful holder path is empty");
            successfulHolderBranches++;
            require(everyPathHitsPulse(successPath,
                            new java.util.IdentityHashMap<>(),
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>())),
                    "scheduleLayer successful holder path lost BrowserWorldgenScheduler.pulse");
        }
        require(successfulHolderBranches > 0,
                "scheduleLayer holder submission call is missing");
    }

    private static void verifyNoBrowserWorldgenSchedulerCalls(ClassNode node,
            String profile) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call
                        && BROWSER_WORLDGEN_SCHEDULER.equals(call.owner)) {
                    throw new IllegalStateException(profile + " " + node.name
                            + " must not call BrowserWorldgenScheduler ("
                            + call.name + call.desc + ")");
                }
            }
        }
        System.out.println("PROFILE_CALL_SURFACE_OK " + profile + " " + node.name);
    }

    private static void verifyCleanupBlock(LabelNode label, String target) {
        boolean activeCleared = false;
        boolean yieldCleared = false;
        boolean exits = false;
        for (AbstractInsnNode instruction = firstExecutable(label); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                activeCleared = previousExecutable(instruction).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) {
                yieldCleared = previousExecutable(instruction).getOpcode() == Opcodes.ACONST_NULL;
            } else if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                exits = true;
                break;
            }
        }
        require(activeCleared && yieldCleared && exits, target + " cleanup CFG changed");
    }

    private static void verifyLayerBarrierCfg(ClassNode node, String profile) {
        MethodNode run = method(node, "runUntilWait");
        List<FieldInsnNode> activeGets = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) activeGets.add(field);
        }
        require(activeGets.size() == 1, "runUntilWait active gate count changed");
        FieldInsnNode activeGet = activeGets.get(0);
        AbstractInsnNode activeNext = nextExecutable(activeGet);
        require(activeNext instanceof JumpInsnNode, "runUntilWait active gate lost branch");
        JumpInsnNode activeBranch = (JumpInsnNode) activeNext;
        require(activeBranch.getOpcode() == Opcodes.IFEQ,
                "runUntilWait active branch must use IFEQ to vanilla wait");
        MethodInsnNode activeCall = firstCall(activeBranch, activeBranch.label);
        require(activeCall != null && activeCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && activeCall.name.equals("scheduleNextLayer"),
                "runUntilWait active fallthrough must schedule the next holder batch");
        MethodInsnNode waitCall = firstCall(activeBranch.label, null);
        require(waitCall != null && waitCall.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && waitCall.name.equals("waitForScheduledLayer"),
                "runUntilWait inactive target must enter vanilla layer wait");

        FieldInsnNode yieldGet = null;
        for (AbstractInsnNode instruction = nextExecutable(activeCall);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerYield")) {
                yieldGet = field;
                break;
            }
        }
        require(yieldGet != null, "runUntilWait active branch lost batch future");
        AbstractInsnNode yieldNext = nextExecutable(yieldGet);
        require(yieldNext instanceof JumpInsnNode, "runUntilWait batch future lost null branch");
        JumpInsnNode yieldNull = (JumpInsnNode) yieldNext;
        require(yieldNull.getOpcode() == Opcodes.IFNULL && yieldNull.label == activeBranch.label,
                "runUntilWait null batch future must drain through vanilla wait");
        boolean returned = false;
        for (AbstractInsnNode instruction = nextExecutable(yieldNull);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction.getOpcode() == Opcodes.ARETURN) {
                returned = true;
                break;
            }
        }
        require(returned, "runUntilWait non-null batch future must return");

        MethodNode layer = method(node, "scheduleLayer");
        AbstractInsnNode first = firstExecutable(layer.instructions.getFirst());
        AbstractInsnNode second = nextExecutable(first);
        require(first != null && first.getOpcode() == Opcodes.ICONST_0,
                "scheduleLayer batch counter must initialize at entry");
        require(second instanceof VarInsnNode && second.getOpcode() == Opcodes.ISTORE
                        && ((VarInsnNode) second).var == 7,
                "scheduleLayer batch counter must reset local 7 at entry");

        JumpInsnNode resumeBranch = null;
        JumpInsnNode batchBackedge = null;
        int batchBackedges = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    require(resumeBranch == null, "scheduleLayer active resume branch changed");
                    resumeBranch = jump;
                }
            }
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLT) {
                batchBackedge = jump;
                batchBackedges++;
            }
        }
        require(resumeBranch != null, "scheduleLayer active resume branch missing");
        require(batchBackedges == 1 && batchBackedge != null,
                "scheduleLayer must have one bounded batch backedge");
        AbstractInsnNode limit = previousExecutable(batchBackedge);
        AbstractInsnNode count = previousExecutable(limit);
        require(limit instanceof LdcInsnNode && Integer.valueOf(16).equals(((LdcInsnNode) limit).cst),
                "scheduleLayer holder batch limit changed");
        require(count instanceof VarInsnNode && count.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) count).var == 7,
                "scheduleLayer batch guard must read local 7");
        require(batchBackedge.label == resumeBranch.label,
                "scheduleLayer batch guard must resume at the holder body");

        FieldInsnNode finalActiveClear = null;
        JumpInsnNode finalYieldJump = null;
        int finalCandidates = 0;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (!(instruction instanceof FieldInsnNode field)
                    || field.getOpcode() != Opcodes.PUTFIELD
                    || !field.name.equals("browserLayerActive")
                    || previousExecutable(field).getOpcode() != Opcodes.ICONST_0) continue;
            AbstractInsnNode next = nextExecutable(field);
            if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) continue;
            AbstractInsnNode target = firstExecutable(jump.label);
            if (target instanceof TypeInsnNode type
                    && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals("java/util/concurrent/CompletableFuture")) {
                finalActiveClear = field;
                finalYieldJump = jump;
                finalCandidates++;
            }
        }
        require(finalCandidates == 1 && finalActiveClear != null && finalYieldJump != null,
                "scheduleLayer final holder continuation path changed");
        JumpInsnNode finalCoordinateBranch = null;
        for (AbstractInsnNode instruction = previousExecutable(finalActiveClear);
                instruction != null; instruction = previousExecutable(instruction)) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLE) {
                finalCoordinateBranch = jump;
                break;
            }
        }
        require(finalCoordinateBranch != null,
                "scheduleLayer final holder coordinate branch missing");
        AbstractInsnNode continueBatch = firstExecutable(finalCoordinateBranch.label);
        require(continueBatch instanceof VarInsnNode
                        && continueBatch.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) continueBatch).var == 7,
                "scheduleLayer non-final coordinate path must reach the batch guard");
        AbstractInsnNode futureStart = firstExecutable(finalYieldJump.label);
        FieldInsnNode yieldPut = null;
        MethodInsnNode platformSchedule = null;
        for (AbstractInsnNode instruction = futureStart; instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) yieldPut = field;
            if (instruction instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("org/teavm/platform/Platform")
                    && call.name.equals("schedule")) {
                platformSchedule = call;
                break;
            }
        }
        require(yieldPut != null && platformSchedule != null,
                "scheduleLayer final continuation must publish and schedule its future");
        require(previousExecutable(platformSchedule).getOpcode() == Opcodes.ICONST_0,
                "scheduleLayer continuation delay must remain zero");

        JumpInsnNode cancellation = null;
        JumpInsnNode holderRejected = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("markedForCancellation")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    cancellation = jump;
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("scheduleChunkInLayer")) {
                AbstractInsnNode branch = nextExecutable(call);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    holderRejected = jump;
                }
            }
        }
        require(cancellation != null && holderRejected != null
                        && cancellation.label == holderRejected.label,
                "scheduleLayer cancellation and rejected holder must share cleanup");
        verifyCleanupBlock(cancellation.label, "scheduleLayer cancel");

        List<TryCatchBlockNode> throwableHandlers = layer.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)).toList();
        require(throwableHandlers.size() == 1, "scheduleLayer Throwable handler changed");
        AbstractInsnNode handlerStart = firstExecutable(throwableHandlers.get(0).handler);
        require(handlerStart instanceof VarInsnNode && handlerStart.getOpcode() == Opcodes.ASTORE,
                "scheduleLayer Throwable handler must retain the thrown value");
        int throwableLocal = ((VarInsnNode) handlerStart).var;
        boolean handlerActiveCleared = false;
        boolean handlerYieldCleared = false;
        boolean rethrowsSame = false;
        for (AbstractInsnNode instruction = nextExecutable(handlerStart); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                handlerActiveCleared = previousExecutable(field).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerYield")) {
                handlerYieldCleared = previousExecutable(field).getOpcode() == Opcodes.ACONST_NULL;
            } else if (instruction.getOpcode() == Opcodes.ATHROW) {
                AbstractInsnNode loaded = previousExecutable(instruction);
                rethrowsSame = loaded instanceof VarInsnNode && loaded.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) loaded).var == throwableLocal;
                break;
            }
        }
        require(handlerActiveCleared && handlerYieldCleared && rethrowsSame,
                "scheduleLayer Throwable cleanup/rethrow changed");
        if (profile.equals("26.2")) {
            verifySuccessfulHolderPulsePaths(layer, node.name);
            System.out.println("PROFILE_CFG_OK " + profile + " " + node.name);
        }
        System.out.println("CFG_VERIFIER_OK " + node.name);
    }

    private static void verifyNoArtificialLayerYield(ClassNode node) {
        for (FieldNode field : node.fields) {
            require(!field.name.equals("browserLayerYield"),
                    "26.2 ChunkGenerationTask must not contain browserLayerYield");
        }
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field) {
                    require(!field.name.equals("browserLayerYield"),
                            "26.2 bytecode still references browserLayerYield");
                }
                if (!(instruction instanceof MethodInsnNode call)) continue;
                require(!call.owner.equals("dev/gaius/browser/BrowserChunkGenerationYield"),
                        "26.2 bytecode still references BrowserChunkGenerationYield");
                require(!(call.owner.equals("org/teavm/platform/Platform")
                                && call.name.equals("schedule")),
                        "26.2 bytecode still invokes Platform.schedule");
            }
        }
        System.out.println("NO_ARTIFICIAL_LAYER_YIELD_OK " + node.name);
    }

    private static void verifyActiveCleanupBlock(LabelNode label, String target) {
        boolean activeCleared = false;
        boolean exits = false;
        for (AbstractInsnNode instruction = firstExecutable(label); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                activeCleared = previousExecutable(instruction).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                exits = true;
                break;
            }
        }
        require(activeCleared && exits, target + " active cleanup CFG changed");
    }

    private static void verifyLayerBarrierCfg262(ClassNode node) {
        verifyNoArtificialLayerYield(node);
        MethodNode run = method(node, "runUntilWait");

        List<FieldInsnNode> activeGets = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                activeGets.add(field);
            }
        }
        require(activeGets.size() == 1,
                "26.2 runUntilWait active gate count changed");
        FieldInsnNode activeGet = activeGets.get(0);
        AbstractInsnNode activeNext = nextExecutable(activeGet);
        require(activeNext instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.IFEQ,
                "26.2 runUntilWait active gate must use IFEQ to vanilla wait");
        JumpInsnNode activeBranch = (JumpInsnNode) activeNext;
        MethodInsnNode activeSchedule = firstCall(activeBranch, activeBranch.label);
        require(activeSchedule != null
                        && activeSchedule.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && activeSchedule.name.equals("scheduleNextLayer"),
                "26.2 active gate must schedule the current layer before re-entry");

        JumpInsnNode activeResume = null;
        for (AbstractInsnNode instruction = nextExecutable(activeSchedule);
                instruction != null && instruction != activeBranch.label;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO) {
                activeResume = jump;
                break;
            }
            require(!(instruction instanceof MethodInsnNode call
                            && call.name.equals("waitForScheduledLayer")),
                    "26.2 active branch reaches vanilla wait before re-entry");
        }
        require(activeResume != null,
                "26.2 active branch lost forward re-entry edge");

        List<JumpInsnNode> runBackedges = new ArrayList<>();
        for (AbstractInsnNode instruction : run.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.GOTO
                    && run.instructions.indexOf(jump.label)
                            < run.instructions.indexOf(jump)) {
                runBackedges.add(jump);
            }
        }
        require(runBackedges.size() == 1,
                "26.2 runUntilWait must retain exactly one vanilla backward edge");
        JumpInsnNode vanillaBackedge = runBackedges.get(0);
        require(activeResume.label != vanillaBackedge.label
                        && run.instructions.indexOf(activeResume.label)
                                < run.instructions.indexOf(vanillaBackedge),
                "26.2 active resume must target the original edge prologue");
        AbstractInsnNode pulseBeforeBackedge = previousExecutable(vanillaBackedge);
        require(isBrowserWorldgenPulse(pulseBeforeBackedge),
                "26.2 original runUntilWait edge lost its scheduler pulse");
        require(firstExecutable(activeResume.label) == pulseBeforeBackedge,
                "26.2 active branch must jump to the pulse before the vanilla edge");

        AbstractInsnNode vanillaWait = firstCall(activeBranch.label, null);
        require(vanillaWait instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.name.equals("waitForScheduledLayer"),
                "26.2 inactive gate target must enter vanilla layer wait");

        MethodNode layer = method(node, "scheduleLayer");
        AbstractInsnNode first = firstExecutable(layer.instructions.getFirst());
        AbstractInsnNode second = nextExecutable(first);
        require(first != null && first.getOpcode() == Opcodes.ICONST_0,
                "26.2 scheduleLayer batch counter must initialize at entry");
        require(second instanceof VarInsnNode && second.getOpcode() == Opcodes.ISTORE
                        && ((VarInsnNode) second).var == 7,
                "26.2 scheduleLayer batch counter must reset local 7 at entry");

        JumpInsnNode resumeBranch = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("browserLayerActive")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    require(resumeBranch == null,
                            "26.2 scheduleLayer active resume branch changed");
                    resumeBranch = jump;
                }
            }
        }
        require(resumeBranch != null,
                "26.2 scheduleLayer active resume branch missing");

        List<JumpInsnNode> batchGuards = new ArrayList<>();
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLT) {
                batchGuards.add(jump);
            }
        }
        require(batchGuards.size() == 1,
                "26.2 scheduleLayer must retain one holder batch guard");
        JumpInsnNode batchGuard = batchGuards.get(0);
        AbstractInsnNode limit = previousExecutable(batchGuard);
        AbstractInsnNode count = previousExecutable(limit);
        require(limit instanceof LdcInsnNode && Integer.valueOf(16).equals(((LdcInsnNode) limit).cst),
                "26.2 scheduleLayer holder batch limit changed");
        require(count instanceof VarInsnNode && count.getOpcode() == Opcodes.ILOAD
                        && ((VarInsnNode) count).var == 7,
                "26.2 scheduleLayer batch guard must read local 7");
        require(batchGuard.label == resumeBranch.label
                        && layer.instructions.indexOf(batchGuard.label)
                                < layer.instructions.indexOf(batchGuard),
                "26.2 scheduleLayer batch guard must re-enter the holder body");

        List<JumpInsnNode> layerBackedges = new ArrayList<>();
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && layer.instructions.indexOf(jump.label)
                            < layer.instructions.indexOf(jump)) {
                layerBackedges.add(jump);
            }
        }
        require(layerBackedges.size() == 1,
                "26.2 scheduleLayer must retain one method-local re-entry edge");
        JumpInsnNode layerBackedge = layerBackedges.get(0);
        require(layerBackedge.getOpcode() == Opcodes.IF_ICMPLT
                        && layerBackedge.label == resumeBranch.label,
                "26.2 scheduleLayer re-entry edge must resume the holder body");
        AbstractInsnNode fullBatchReturn = nextExecutable(batchGuard);
        require(fullBatchReturn instanceof JumpInsnNode jump
                        && jump.getOpcode() == Opcodes.GOTO
                        && layer.instructions.indexOf(jump.label)
                                > layer.instructions.indexOf(jump),
                "26.2 full holder batch must return with its active cursor");

        JumpInsnNode cancellation = null;
        JumpInsnNode holderRejected = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETFIELD
                    && field.name.equals("markedForCancellation")) {
                AbstractInsnNode branch = nextExecutable(field);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFNE) {
                    cancellation = jump;
                }
            }
            if (instruction instanceof MethodInsnNode call
                    && call.name.equals("scheduleChunkInLayer")) {
                AbstractInsnNode branch = nextExecutable(call);
                if (branch instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    holderRejected = jump;
                }
            }
        }
        require(cancellation != null && holderRejected != null
                        && cancellation.label == holderRejected.label,
                "26.2 cancellation and rejected holder must share cleanup");
        verifyActiveCleanupBlock(cancellation.label, "26.2 scheduleLayer cancel");

        JumpInsnNode finalCoordinateBranch = null;
        for (AbstractInsnNode instruction : layer.instructions) {
            if (instruction instanceof JumpInsnNode jump
                    && jump.getOpcode() == Opcodes.IF_ICMPLE
                    && jump.label != cancellation.label) {
                finalCoordinateBranch = jump;
            }
        }
        require(finalCoordinateBranch != null,
                "26.2 final holder coordinate branch missing");
        boolean finalActiveCleared = false;
        for (AbstractInsnNode instruction = nextExecutable(finalCoordinateBranch);
                instruction != null && instruction != firstExecutable(finalCoordinateBranch.label);
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")
                    && previousExecutable(field).getOpcode() == Opcodes.ICONST_0) {
                finalActiveCleared = true;
                break;
            }
        }
        require(finalActiveCleared,
                "26.2 final holder path must clear active before vanilla wait");

        List<TryCatchBlockNode> throwableHandlers = layer.tryCatchBlocks.stream()
                .filter(block -> "java/lang/Throwable".equals(block.type)).toList();
        require(throwableHandlers.size() == 1,
                "26.2 scheduleLayer Throwable handler changed");
        AbstractInsnNode handlerStart = firstExecutable(throwableHandlers.get(0).handler);
        require(handlerStart instanceof VarInsnNode && handlerStart.getOpcode() == Opcodes.ASTORE,
                "26.2 scheduleLayer Throwable handler must retain the thrown value");
        int throwableLocal = ((VarInsnNode) handlerStart).var;
        boolean handlerActiveCleared = false;
        boolean rethrowsSame = false;
        for (AbstractInsnNode instruction = nextExecutable(handlerStart); instruction != null;
                instruction = nextExecutable(instruction)) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.PUTFIELD
                    && field.name.equals("browserLayerActive")) {
                handlerActiveCleared = previousExecutable(field).getOpcode() == Opcodes.ICONST_0;
            } else if (instruction.getOpcode() == Opcodes.ATHROW) {
                AbstractInsnNode loaded = previousExecutable(instruction);
                rethrowsSame = loaded instanceof VarInsnNode
                        && loaded.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) loaded).var == throwableLocal;
                break;
            }
        }
        require(handlerActiveCleared && rethrowsSame,
                "26.2 scheduleLayer Throwable cleanup/rethrow changed");
        verifySuccessfulHolderPulsePaths(layer, node.name);
        System.out.println("PROFILE_CFG_OK 26.2 " + node.name);
        System.out.println("CFG_VERIFIER_OK " + node.name);
    }

    private static void verify(ZipFile jar, String name, String profile) throws Exception {
        var entry = jar.getEntry(name);
        if (entry == null) {
            throw new IllegalStateException("missing verifier entry: " + name);
        }
        ClassNode node = new ClassNode();
        try (var input = jar.getInputStream(entry)) {
            new ClassReader(input.readAllBytes()).accept(node, 0);
        }
        for (MethodNode method : node.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
        }
        if (name.equals("net/minecraft/server/level/ChunkGenerationTask.class")) {
            if (profile.equals("26.2")) {
                verifyLayerBarrierCfg262(node);
            } else {
                verifyLayerBarrierCfg(node, profile);
            }
        }
        if (profile.equals("1.21.11")) {
            verifyNoBrowserWorldgenSchedulerCalls(node, profile);
        }
        System.out.println("BASIC_VERIFIER_OK " + name);
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2,
                "usage: GaiusChunkLayerBytecodeVerifier <client.jar> <1.21.11|26.2>");
        String profile = args[1];
        require(profile.equals("1.21.11") || profile.equals("26.2"),
                "unsupported Minecraft profile: " + profile);
        try (ZipFile jar = new ZipFile(args[0])) {
            verify(jar, "net/minecraft/server/level/ChunkGenerationTask.class", profile);
            if (profile.equals("1.21.11")) {
                verify(jar, "dev/gaius/browser/BrowserChunkGenerationYield.class", profile);
            } else {
                require(jar.getEntry("dev/gaius/browser/BrowserChunkGenerationYield.class") == null,
                        "26.2 overlay must not emit BrowserChunkGenerationYield.class");
                System.out.println("NO_HELPER_CLASS_OK 26.2");
            }
        }
    }
}
