package dev.gaius.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Routes authlib HTTP traffic through the browser bridge to avoid CORS failures. */
public final class AuthlibBrowserPatcher {
    private static final String ENTRY =
            "com/mojang/authlib/minecraft/client/MinecraftClient.class";
    private static final String SESSION_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilMinecraftSessionService.class";
    private static final String TEXTURE_ENTRY =
            "com/mojang/authlib/minecraft/MinecraftProfileTexture.class";
    private static final String TEXTURES_PAYLOAD_ENTRY =
            "com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload.class";
    private static final String KEY_INFO_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo.class";
    private static final String KEY_SET_RESPONSE_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse.class";
    private static final String KEY_DATA_ENTRY =
            "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeyData.class";

    private AuthlibBrowserPatcher() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "usage: AuthlibBrowserPatcher INPUT_JAR OUTPUT_CLASS");
        }
        byte[] input;
        byte[] sessionInput;
        byte[] textureInput;
        byte[] texturesPayloadInput;
        byte[] keyInfoInput;
        byte[] keySetResponseInput;
        byte[] keyDataInput;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(ENTRY);
            if (entry == null) {
                throw new IllegalStateException(ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(entry)) {
                input = stream.readAllBytes();
            }
            var sessionEntry = jar.getEntry(SESSION_ENTRY);
            if (sessionEntry == null) {
                throw new IllegalStateException(SESSION_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(sessionEntry)) {
                sessionInput = stream.readAllBytes();
            }
            var textureEntry = jar.getEntry(TEXTURE_ENTRY);
            if (textureEntry == null) {
                throw new IllegalStateException(TEXTURE_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(textureEntry)) {
                textureInput = stream.readAllBytes();
            }
            var texturesPayloadEntry = jar.getEntry(TEXTURES_PAYLOAD_ENTRY);
            if (texturesPayloadEntry == null) {
                throw new IllegalStateException(TEXTURES_PAYLOAD_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(texturesPayloadEntry)) {
                texturesPayloadInput = stream.readAllBytes();
            }
            var keyInfoEntry = jar.getEntry(KEY_INFO_ENTRY);
            if (keyInfoEntry == null) {
                throw new IllegalStateException(KEY_INFO_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(keyInfoEntry)) {
                keyInfoInput = stream.readAllBytes();
            }
            var keySetResponseEntry = jar.getEntry(KEY_SET_RESPONSE_ENTRY);
            if (keySetResponseEntry == null) {
                throw new IllegalStateException(KEY_SET_RESPONSE_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(keySetResponseEntry)) {
                keySetResponseInput = stream.readAllBytes();
            }
            var keyDataEntry = jar.getEntry(KEY_DATA_ENTRY);
            if (keyDataEntry == null) {
                throw new IllegalStateException(KEY_DATA_ENTRY + " not found in " + args[0]);
            }
            try (var stream = jar.getInputStream(keyDataEntry)) {
                keyDataInput = stream.readAllBytes();
            }
        }

        ClassNode node = new ClassNode();
        new ClassReader(input).accept(node, 0);
        boolean found = false;
        boolean removedJavaProxy = false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("createUrlConnection")
                    || !method.desc.equals(
                            "(Ljava/net/URL;)Ljava/net/HttpURLConnection;")) {
                continue;
            }
            InsnList code = new InsnList();
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserHttpProxy",
                    "proxyAuthentication",
                    "(Ljava/net/URL;)Ljava/net/URL;",
                    false));
            code.add(new VarInsnNode(Opcodes.ASTORE, 1));
            method.instructions.insert(code);
            method.maxStack = Math.max(method.maxStack, 1);
            for (var instruction = method.instructions.getFirst();
                    instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("java/net/URL")
                        || !call.name.equals("openConnection")
                        || !call.desc.equals("(Ljava/net/Proxy;)Ljava/net/URLConnection;")) {
                    continue;
                }
                method.instructions.insertBefore(call, new org.objectweb.asm.tree.InsnNode(
                        Opcodes.POP));
                call.desc = "()Ljava/net/URLConnection;";
                removedJavaProxy = true;
            }
            found = true;
        }
        if (!found || !removedJavaProxy) {
            throw new IllegalStateException(
                    "MinecraftClient browser connection patch points were not found: method="
                            + found + " proxy=" + removedJavaProxy);
        }

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        Path output = Path.of(args[1]);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());

        ClassNode sessionNode = new ClassNode();
        new ClassReader(sessionInput).accept(sessionNode, 0);
        MethodNode constructor = sessionNode.methods.stream()
                .filter(method -> method.name.equals("<init>")
                        && method.desc.equals("(Lcom/mojang/authlib/yggdrasil/ServicesKeySet;"
                                + "Ljava/net/Proxy;Lcom/mojang/authlib/Environment;)V"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("authlib session constructor was not found"));
        boolean registered = false;
        for (var instruction = constructor.instructions.getFirst(); instruction != null;
                instruction = instruction.getNext()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("com/google/gson/GsonBuilder")
                    || !call.name.equals("registerTypeAdapter")) {
                continue;
            }
            InsnList registration = new InsnList();
            registration.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
            registration.add(new org.objectweb.asm.tree.LdcInsnNode(
                    org.objectweb.asm.Type.getType(
                            "Lcom/mojang/authlib/minecraft/MinecraftProfileTexture;")));
            registration.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "dev/gaius/browser/BrowserAuthlibGson",
                    "textureDeserializer",
                    "()Lcom/google/gson/JsonDeserializer;",
                    false));
            registration.add(new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "com/google/gson/GsonBuilder",
                    "registerTypeHierarchyAdapter",
                    "(Ljava/lang/Class;Ljava/lang/Object;)Lcom/google/gson/GsonBuilder;",
                    false));
            registration.add(new org.objectweb.asm.tree.InsnNode(Opcodes.POP));
            var insertionPoint = instruction.getPrevious();
            while (insertionPoint != null) {
                if (insertionPoint instanceof org.objectweb.asm.tree.LdcInsnNode constant
                        && constant.cst instanceof org.objectweb.asm.Type type
                        && type.getClassName().equals("java.util.UUID")) {
                    break;
                }
                insertionPoint = insertionPoint.getPrevious();
            }
            if (insertionPoint == null) {
                throw new IllegalStateException("authlib Gson UUID registration anchor was not found");
            }
            constructor.instructions.insertBefore(insertionPoint, registration);
            registered = true;
            break;
        }
        if (!registered) {
            throw new IllegalStateException("authlib Gson registration patch point was not found");
        }
        boolean decodedTextures = false;
        for (MethodNode method : sessionNode.methods) {
            if (!method.name.equals("unpackTextures")
                    || !method.desc.equals("(Lcom/mojang/authlib/properties/Property;)"
                            + "Lcom/mojang/authlib/minecraft/MinecraftProfileTextures;")) {
                continue;
            }
            for (var instruction = method.instructions.getFirst(); instruction != null;
                    instruction = instruction.getNext()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("com/google/gson/Gson")
                        || !call.name.equals("fromJson")
                        || !call.desc.equals("(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;")) {
                    continue;
                }
                var payloadClass = instruction.getPrevious();
                var json = payloadClass != null ? payloadClass.getPrevious() : null;
                var gson = json != null ? json.getPrevious() : null;
                var owner = gson != null ? gson.getPrevious() : null;
                if (!(payloadClass instanceof org.objectweb.asm.tree.LdcInsnNode constant)
                        || !(constant.cst instanceof org.objectweb.asm.Type type)
                        || !type.getClassName().equals(
                                "com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload")
                        || !(json instanceof VarInsnNode)
                        || !(gson instanceof org.objectweb.asm.tree.FieldInsnNode)
                        || !(owner instanceof VarInsnNode)) {
                    continue;
                }
                method.instructions.remove(owner);
                method.instructions.remove(gson);
                method.instructions.remove(payloadClass);
                call.setOpcode(Opcodes.INVOKESTATIC);
                call.owner = "dev/gaius/browser/BrowserAuthlibGson";
                call.name = "decodeTextures";
                call.desc = "(Ljava/lang/String;)Lcom/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload;";
                call.itf = false;
                decodedTextures = true;
                break;
            }
        }
        if (!decodedTextures) {
            throw new IllegalStateException("authlib texture Gson decode patch point was not found");
        }
        constructor.maxStack = Math.max(constructor.maxStack, 3);
        ClassWriter sessionWriter = new ClassWriter(0);
        sessionNode.accept(sessionWriter);
        Path sessionOutput = output.getParent().getParent().getParent()
                .resolve("yggdrasil/YggdrasilMinecraftSessionService.class");
        Files.createDirectories(sessionOutput.getParent());
        Files.write(sessionOutput, sessionWriter.toByteArray());

        ClassNode textureNode = new ClassNode();
        new ClassReader(textureInput).accept(textureNode, 0);
        boolean hasNoArgsConstructor = textureNode.methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"));
        if (!hasNoArgsConstructor) {
            MethodNode noArgsConstructor = new MethodNode(
                    Opcodes.ACC_PUBLIC,
                    "<init>",
                    "()V",
                    null,
                    null);
            noArgsConstructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            noArgsConstructor.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
            noArgsConstructor.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/util/Collections",
                    "emptyMap",
                    "()Ljava/util/Map;",
                    false));
            noArgsConstructor.instructions.add(new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    TEXTURE_ENTRY.substring(0, TEXTURE_ENTRY.length() - ".class".length()),
                    "<init>",
                    "(Ljava/lang/String;Ljava/util/Map;)V",
                    false));
            noArgsConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            noArgsConstructor.maxStack = 3;
            noArgsConstructor.maxLocals = 1;
            textureNode.methods.add(noArgsConstructor);
        }
        ClassWriter textureWriter = new ClassWriter(0);
        textureNode.accept(textureWriter);
        Path textureOutput = output.getParent().getParent().resolve("MinecraftProfileTexture.class");
        Files.createDirectories(textureOutput.getParent());
        Files.write(textureOutput, textureWriter.toByteArray());

        ClassNode payloadNode = new ClassNode();
        new ClassReader(texturesPayloadInput).accept(payloadNode, 0);
        boolean payloadHasNoArgs = payloadNode.methods.stream()
                .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"));
        if (!payloadHasNoArgs) {
            MethodNode payloadConstructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            payloadConstructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LCONST_0));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ACONST_NULL));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.LdcInsnNode(""));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
            payloadConstructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/util/Collections", "emptyMap", "()Ljava/util/Map;", false));
            payloadConstructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "com/mojang/authlib/yggdrasil/response/MinecraftTexturesPayload", "<init>",
                    "(JLjava/util/UUID;Ljava/lang/String;ZLjava/util/Map;)V", false));
            payloadConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
            payloadConstructor.maxStack = 7;
            payloadConstructor.maxLocals = 1;
            payloadNode.methods.add(payloadConstructor);
        }
        ClassWriter payloadWriter = new ClassWriter(0);
        payloadNode.accept(payloadWriter);
        Path payloadOutput = output.getParent().getParent().getParent()
                .resolve("yggdrasil/response/MinecraftTexturesPayload.class");
        Files.createDirectories(payloadOutput.getParent());
        Files.write(payloadOutput, payloadWriter.toByteArray());

        ClassNode keyInfoNode = new ClassNode();
        new ClassReader(keyInfoInput).accept(keyInfoNode, 0);
        MethodNode parseKeyInfo = keyInfoNode.methods.stream()
                .filter(method -> method.name.equals("parse")
                        && method.desc.equals("([B)Lcom/mojang/authlib/yggdrasil/ServicesKeyInfo;"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "authlib services key parse method was not found"));
        parseKeyInfo.instructions.clear();
        parseKeyInfo.tryCatchBlocks.clear();
        parseKeyInfo.localVariables = null;
        parseKeyInfo.visibleTypeAnnotations = null;
        parseKeyInfo.invisibleTypeAnnotations = null;
        InsnList browserKeyParse = new InsnList();
        browserKeyParse.add(new org.objectweb.asm.tree.TypeInsnNode(
                Opcodes.NEW,
                KEY_INFO_ENTRY.substring(0, KEY_INFO_ENTRY.length() - ".class".length())));
        browserKeyParse.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP));
        browserKeyParse.add(new VarInsnNode(Opcodes.ALOAD, 0));
        browserKeyParse.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "dev/gaius/browser/BrowserCrypto",
                "parseRsaPublicKey",
                "([B)Ljava/security/PublicKey;",
                false));
        browserKeyParse.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                KEY_INFO_ENTRY.substring(0, KEY_INFO_ENTRY.length() - ".class".length()),
                "<init>",
                "(Ljava/security/PublicKey;)V",
                false));
        browserKeyParse.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        parseKeyInfo.instructions.add(browserKeyParse);
        parseKeyInfo.maxStack = 3;
        parseKeyInfo.maxLocals = 1;
        ClassWriter keyInfoWriter = new ClassWriter(0);
        keyInfoNode.accept(keyInfoWriter);
        Path keyInfoOutput = output.getParent().getParent().getParent()
                .resolve("yggdrasil/YggdrasilServicesKeyInfo.class");
        Files.createDirectories(keyInfoOutput.getParent());
        Files.write(keyInfoOutput, keyInfoWriter.toByteArray());

        ClassNode keySetResponseNode = new ClassNode();
        new ClassReader(keySetResponseInput).accept(keySetResponseNode, 0);
        ensureNoArgsConstructor(
                keySetResponseNode,
                keySetResponseNode.methods.stream()
                        .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"))
                        ? null
                        : keySetResponseNoArgsBody(),
                3);
        ClassWriter keySetResponseWriter = new ClassWriter(0);
        keySetResponseNode.accept(keySetResponseWriter);
        Path keySetResponseOutput = output.getParent().getParent().getParent()
                .resolve("yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse.class");
        Files.createDirectories(keySetResponseOutput.getParent());
        Files.write(keySetResponseOutput, keySetResponseWriter.toByteArray());

        ClassNode keyDataNode = new ClassNode();
        new ClassReader(keyDataInput).accept(keyDataNode, 0);
        ensureNoArgsConstructor(
                keyDataNode,
                keyDataNode.methods.stream()
                        .anyMatch(method -> method.name.equals("<init>") && method.desc.equals("()V"))
                        ? null
                        : keyDataNoArgsBody(),
                2);
        ClassWriter keyDataWriter = new ClassWriter(0);
        keyDataNode.accept(keyDataWriter);
        Path keyDataOutput = output.getParent().getParent().getParent()
                .resolve("yggdrasil/YggdrasilServicesKeyInfo$KeyData.class");
        Files.createDirectories(keyDataOutput.getParent());
        Files.write(keyDataOutput, keyDataWriter.toByteArray());
    }

    private static void ensureNoArgsConstructor(
            ClassNode node, InsnList body, int maxStack) {
        if (body == null) {
            return;
        }
        MethodNode noArgsConstructor = new MethodNode(
                Opcodes.ACC_PUBLIC,
                "<init>",
                "()V",
                null,
                null);
        noArgsConstructor.instructions.add(body);
        noArgsConstructor.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        noArgsConstructor.maxStack = maxStack;
        noArgsConstructor.maxLocals = 1;
        node.methods.add(noArgsConstructor);
    }

    private static InsnList keySetResponseNoArgsBody() {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Collections",
                "emptyList",
                "()Ljava/util/List;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                KEY_SET_RESPONSE_ENTRY.substring(0, KEY_SET_RESPONSE_ENTRY.length() - ".class".length()),
                "<init>",
                "(Ljava/util/List;Ljava/util/List;)V",
                false));
        return body;
    }

    private static InsnList keyDataNoArgsBody() {
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/nio/ByteBuffer",
                "allocate",
                "(I)Ljava/nio/ByteBuffer;",
                false));
        body.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                KEY_DATA_ENTRY.substring(0, KEY_DATA_ENTRY.length() - ".class".length()),
                "<init>",
                "(Ljava/nio/ByteBuffer;)V",
                false));
        return body;
    }
}
