#!/usr/bin/env node

/**
 * Static/overlay smoke for the browser authlib public-key response patch.
 *
 * The browser build uses Gson to materialize authlib's nested record-like
 * KeySetResponse/KeyData classes.  Their canonical constructors are private
 * and neither class originally had a no-argument constructor, which makes
 * the browser Gson path fail before a multiplayer session can start.  This
 * smoke checks both the patch source contract and, when --class-dir is given,
 * the actual class files emitted by AuthlibBrowserPatcher.  It deliberately
 * does not start Chrome, a Minecraft server, or a network endpoint.
 */

import assert from "node:assert/strict";
import {existsSync, readFileSync} from "node:fs";
import {join, isAbsolute, resolve, relative} from "node:path";
import {fileURLToPath} from "node:url";

const root = fileURLToPath(new URL("../..", import.meta.url));
const args = process.argv.slice(2);

function usage() {
  console.log(`Usage: node port/scripts/authlib-keyset-constructor-smoke.mjs [options]

Options:
  --class-dir <path>  Inspect patched .class files under this directory.
  --json              Print machine-readable JSON after the summary.
  --help              Show this help.

The default run is source/build-script static verification only.  A class
directory may be the AuthlibBrowserPatcher output directory, for example:
  --class-dir _tmp/authlib-patched26/patches
`);
}

if (args.includes("--help")) {
  usage();
  process.exit(0);
}

function fail(message) {
  throw new Error(`Authlib keyset constructor smoke failed: ${message}`);
}

function pathFromRoot(value) {
  const normalized = value.replaceAll("\\", "/");
  return isAbsolute(normalized) ? resolve(normalized) : resolve(root, normalized);
}

let classDir;
let printJson = false;
for (let index = 0; index < args.length; index++) {
  switch (args[index]) {
    case "--class-dir":
      if (!args[index + 1] || args[index + 1].startsWith("--")) {
        fail("--class-dir needs a value");
      }
      classDir = pathFromRoot(args[++index]);
      break;
    case "--json":
      printJson = true;
      break;
    default:
      if (args[index].startsWith("--")) fail(`unknown option ${args[index]}`);
      fail(`unexpected argument ${args[index]}`);
  }
}

const patcherPath = join(root, "port/tools/src/main/java/dev/gaius/tools/AuthlibBrowserPatcher.java");
const overlaysPath = join(root, "port/scripts/build-overlays.sh");
const patcher = readFileSync(patcherPath, "utf8");
const overlays = readFileSync(overlaysPath, "utf8");

const keySetInternal = "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse";
const keyDataInternal = "com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeyData";
const keySetEntry = `${keySetInternal}.class`;
const keyDataEntry = `${keyDataInternal}.class`;

function requireText(text, marker, label) {
  assert.ok(text.includes(marker), `${label} is missing marker: ${marker}`);
}

// Keep these checks deliberately specific: a broad “contains ByteBuffer”
// assertion would pass even if the constructor body or output path regressed.
for (const marker of [
  `KEY_SET_RESPONSE_ENTRY =\n            "${keySetEntry}"`,
  `KEY_DATA_ENTRY =\n            "${keyDataEntry}"`,
  "ensureNoArgsConstructor(",
  '"java/util/Collections"',
  '"emptyList"',
  '"java/nio/ByteBuffer"',
  '"allocate"',
  '"(Ljava/util/List;Ljava/util/List;)V"',
  '"(Ljava/nio/ByteBuffer;)V"',
  `resolve("yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse.class")`,
  `resolve("yggdrasil/YggdrasilServicesKeyInfo$KeyData.class")`,
]) {
  requireText(patcher, marker, "AuthlibBrowserPatcher.java");
}

for (const marker of [
  `-C "$authlib_patch_classes" 'com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeyData.class'`,
  `-C "$authlib_patch_classes" 'com/mojang/authlib/yggdrasil/YggdrasilServicesKeyInfo$KeySetResponse.class'`,
]) {
  requireText(overlays, marker, "build-overlays.sh");
}

function u1(bytes, offset) {
  assert.ok(offset < bytes.length, "truncated class file");
  return bytes[offset];
}

function u2(bytes, offset) {
  assert.ok(offset + 2 <= bytes.length, "truncated class file");
  return bytes.readUInt16BE(offset);
}

function u4(bytes, offset) {
  assert.ok(offset + 4 <= bytes.length, "truncated class file");
  return bytes.readUInt32BE(offset);
}

function parseClass(bytes, label) {
  assert.equal(bytes.readUInt32BE(0), 0xcafebabe, `${label} is not a JVM class file`);
  let offset = 8;
  const cpCount = u2(bytes, offset);
  offset += 2;
  const cp = Array(cpCount).fill(null);
  for (let index = 1; index < cpCount; index++) {
    const tag = u1(bytes, offset++);
    switch (tag) {
      case 1: {
        const length = u2(bytes, offset);
        offset += 2;
        assert.ok(offset + length <= bytes.length, `${label} has truncated UTF-8 constant`);
        cp[index] = {tag, value: bytes.toString("utf8", offset, offset + length)};
        offset += length;
        break;
      }
      case 3:
      case 4:
        cp[index] = {tag};
        offset += 4;
        break;
      case 5:
      case 6:
        cp[index] = {tag};
        offset += 8;
        index++;
        break;
      case 7:
      case 8:
      case 16:
      case 19:
      case 20:
        cp[index] = {tag, index: u2(bytes, offset)};
        offset += 2;
        break;
      case 9:
      case 10:
      case 11:
      case 12:
      case 17:
      case 18:
        cp[index] = {tag, first: u2(bytes, offset), second: u2(bytes, offset + 2)};
        offset += 4;
        break;
      case 15:
        cp[index] = {tag, kind: u1(bytes, offset), index: u2(bytes, offset + 1)};
        offset += 3;
        break;
      default:
        fail(`${label} has unsupported constant-pool tag ${tag}`);
    }
  }

  const utf8 = index => cp[index]?.tag === 1 ? cp[index].value : undefined;
  const className = index => {
    const entry = cp[index];
    return entry?.tag === 7 ? utf8(entry.index) : undefined;
  };
  const nameAndType = index => {
    const entry = cp[index];
    return entry?.tag === 12
      ? {name: utf8(entry.first), descriptor: utf8(entry.second)}
      : undefined;
  };
  const methodRef = index => {
    const entry = cp[index];
    if (!entry || ![10, 11].includes(entry.tag)) return undefined;
    const member = nameAndType(entry.second);
    return member ? {index, owner: className(entry.first), ...member} : undefined;
  };

  // access_flags, this_class, super_class, interfaces
  const thisClassIndex = u2(bytes, offset + 2);
  offset += 6;
  const interfaceCount = u2(bytes, offset);
  offset += 2 + interfaceCount * 2;

  function skipAttributes(count) {
    for (let index = 0; index < count; index++) {
      offset += 2;
      const length = u4(bytes, offset);
      offset += 4 + length;
      assert.ok(offset <= bytes.length, `${label} has a truncated attribute`);
    }
  }

  const fieldCount = u2(bytes, offset);
  offset += 2;
  for (let index = 0; index < fieldCount; index++) {
    offset += 6;
    const attributes = u2(bytes, offset);
    offset += 2;
    skipAttributes(attributes);
  }

  const methods = [];
  const methodCount = u2(bytes, offset);
  offset += 2;
  for (let index = 0; index < methodCount; index++) {
    const access = u2(bytes, offset);
    const name = utf8(u2(bytes, offset + 2));
    const descriptor = utf8(u2(bytes, offset + 4));
    const attributes = u2(bytes, offset + 6);
    offset += 8;
    let code;
    for (let attribute = 0; attribute < attributes; attribute++) {
      const attributeName = utf8(u2(bytes, offset));
      const length = u4(bytes, offset + 2);
      offset += 6;
      if (attributeName !== "Code") {
        offset += length;
        continue;
      }
      const codeStart = offset;
      const maxStack = u2(bytes, offset);
      const maxLocals = u2(bytes, offset + 2);
      const codeLength = u4(bytes, offset + 4);
      const codeStartBytes = offset + 8;
      code = {
        maxStack,
        maxLocals,
        bytes: bytes.subarray(codeStartBytes, codeStartBytes + codeLength),
      };
      // exception_table_length plus nested Code attributes
      let nested = codeStartBytes + codeLength;
      const exceptionCount = u2(bytes, nested);
      nested += 2 + exceptionCount * 8;
      const nestedCount = u2(bytes, nested);
      nested += 2;
      for (let nestedIndex = 0; nestedIndex < nestedCount; nestedIndex++) {
        const nestedLength = u4(bytes, nested + 2);
        nested += 6 + nestedLength;
      }
      assert.equal(nested - codeStart, length, `${label} Code attribute length mismatch`);
      offset = codeStart + length;
    }
    methods.push({access, name, descriptor, code});
  }

  return {
    thisClass: className(thisClassIndex),
    methods,
    methodRefs: cp.map((_, index) => methodRef(index)).filter(Boolean),
  };
}

function findClassFile(directory, internalName) {
  const candidates = [
    join(directory, `${internalName}.class`),
    join(directory, `${internalName.replace("com/mojang/authlib/", "")}.class`),
  ];
  return candidates.find(existsSync);
}

function inspectClass(directory, internalName, canonicalDescriptor) {
  const path = findClassFile(directory, internalName);
  assert.ok(path, `${internalName}.class not found under ${directory}`);
  const parsed = parseClass(readFileSync(path), relative(root, path));
  assert.equal(parsed.thisClass, internalName, `${path} has unexpected this_class`);
  const constructor = parsed.methods.find(method =>
    method.name === "<init>" && method.descriptor === "()V");
  assert.ok(constructor, `${path} has no no-argument constructor`);
  assert.ok((constructor.access & 0x0001) !== 0, `${path} no-argument constructor is not public`);
  const canonical = parsed.methodRefs.some(ref =>
    ref.owner === internalName && ref.name === "<init>" && ref.descriptor === canonicalDescriptor);
  assert.ok(canonical, `${path} has no canonical constructor reference ${canonicalDescriptor}`);
  const invokespecial = [];
  const code = constructor.code?.bytes ?? Buffer.alloc(0);
  for (let index = 0; index + 2 < code.length; index++) {
    if (code[index] !== 0xb7) continue;
    const constantPoolIndex = code.readUInt16BE(index + 1);
    const ref = parsed.methodRefs.find(candidate => candidate.index === constantPoolIndex
      && candidate.owner === internalName
      && candidate.name === "<init>" && candidate.descriptor === canonicalDescriptor);
    if (ref) invokespecial.push(ref.descriptor);
  }
  assert.ok(invokespecial.length > 0,
    `${path} no-argument constructor does not contain invokespecial canonical call`);
  return {
    path,
    noArgsPublic: true,
    canonicalDescriptor,
    invokespecialCanonical: true,
    codeBytes: code.length,
  };
}

const result = {
  schema: "gaius.authlib-keyset-constructor-smoke.v1",
  ok: true,
  static: {
    patcher: patcherPath,
    overlays: overlaysPath,
    keySetEntry,
    keyDataEntry,
    jarEntriesQuoted: true,
  },
  classEvidence: null,
};

if (classDir) {
  result.classEvidence = {
    classDir,
    keySetResponse: inspectClass(classDir, keySetInternal, "(Ljava/util/List;Ljava/util/List;)V"),
    keyData: inspectClass(classDir, keyDataInternal, "(Ljava/nio/ByteBuffer;)V"),
  };
}

console.log(`Authlib keyset constructor smoke passed (static${classDir ? "+class" : ""})`);
if (printJson) console.log(JSON.stringify(result, null, 2));
