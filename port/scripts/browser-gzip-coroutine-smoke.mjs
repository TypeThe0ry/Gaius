import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdir, mkdtemp, readFile, readdir, rm, writeFile} from 'node:fs/promises';
import {homedir, tmpdir} from 'node:os';
import {delimiter, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {gzipSync} from 'node:zlib';

// Compile the real BrowserGzip with TeaVM and call it through a synchronous
// JavaScript callback. Minimal NBT types isolate the coroutine boundary; the
// decompressor is the real TeaVM GZIPInputStream, not a JS mock.
const root = fileURLToPath(new URL('../../', import.meta.url));
const input = resolve(process.argv[2] || join(root, 'port/src/main/java/dev/gaius/browser/BrowserGzip.java'));
const repository = process.env.M2_REPO || join(homedir(), '.m2', 'repository');
const jars = [];
for (const artifact of await readdir(join(repository, 'org', 'teavm'))) {
  const directory = join(repository, 'org', 'teavm', artifact, '0.15.0');
  try {
    for (const name of await readdir(directory)) {
      if (name.endsWith('.jar') && !name.includes('-sources') && !name.includes('-javadoc')) jars.push(join(directory, name));
    }
  } catch { /* only installed TeaVM 0.15 artifacts */ }
}
assert.ok(jars.some(name => name.includes('teavm-tooling')), 'TeaVM 0.15 tooling must be installed');
jars.push(join(repository, 'com', 'jcraft', 'jzlib', '1.1.3', 'jzlib-1.1.3.jar'));
const suffix = process.platform === 'win32' ? '.exe' : '';
const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `java${suffix}`) : 'java';
const javac = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', `javac${suffix}`) : 'javac';
const temp = await mkdtemp(join(tmpdir(), 'gaius-gzip-coroutine-'));
const payload = Buffer.from('NBT callback gzip fixture '.repeat(2048));
const compressed = gzipSync(payload);
const javaBytes = [...compressed].map(value => value > 127 ? value - 256 : value).join(',');
try {
  const sources = {
    'dev/gaius/browser/BrowserGzip.java': await readFile(input, 'utf8'),
    'net/minecraft/nbt/CompoundTag.java': `package net.minecraft.nbt; public class CompoundTag { public byte[] bytes; public CompoundTag(byte[] b) { bytes=b; } }`,
    'net/minecraft/nbt/NbtAccounter.java': `package net.minecraft.nbt; public class NbtAccounter { public static NbtAccounter unlimitedHeap() { return new NbtAccounter(); } }`,
    'net/minecraft/nbt/NbtIo.java': `package net.minecraft.nbt;
      import java.io.*; import java.util.zip.GZIPInputStream;
      public class NbtIo {
        public static CompoundTag readCompressed(InputStream in,NbtAccounter a) throws IOException {
          return new CompoundTag(new GZIPInputStream(in).readAllBytes());
        }
        public static CompoundTag read(DataInputStream in,NbtAccounter a) throws IOException {
          return new CompoundTag(in.readAllBytes());
        }
      }`,
    'GzipCallbackFixture.java': `
      import java.io.*; import dev.gaius.browser.BrowserGzip; import org.teavm.jso.*;
      public class GzipCallbackFixture {
        @JSFunctor interface Reader extends JSObject { int read(); }
        @JSBody(params="reader",script="globalThis.readGzipFixture=reader;") static native void install(Reader reader);
        public static void main(String[] args) {
          install(()-> {
            try {
              byte[] bytes=BrowserGzip.readCompressedNbt(new ByteArrayInputStream(new byte[]{${javaBytes}})).bytes;
              if(bytes.length!=${payload.length}) throw new AssertionError("length");
              String expected="NBT callback gzip fixture ";
              for(int i=0;i<bytes.length;i++) if((bytes[i]&255)!=expected.charAt(i%expected.length())) throw new AssertionError("byte "+i);
              byte[] damaged=new byte[]{${javaBytes}};
              damaged[damaged.length-8]^=1;
              try {
                BrowserGzip.readCompressedNbt(new ByteArrayInputStream(damaged));
                throw new AssertionError("Bad gzip CRC was accepted");
              } catch(IOException expectedError) { }
              try {
                BrowserGzip.readCompressedNbt(new ByteArrayInputStream(damaged,0,damaged.length/2));
                throw new AssertionError("Truncated gzip was accepted");
              } catch(IOException expectedError) { }
              return bytes.length;
            } catch(IOException e) { throw new RuntimeException(e); }
          });
        }
      }`,
    'CompileGzipFixture.java': `
      import java.io.File; import org.teavm.tooling.*; import org.teavm.backend.javascript.JSModuleType;
      public class CompileGzipFixture {
        public static void main(String[] args) throws Exception {
          TeaVMTool tool=new TeaVMTool(); tool.setMainClass("GzipCallbackFixture");
          tool.setTargetDirectory(new File(args[0])); tool.setTargetFileName("fixture.cjs");
          tool.setJsModuleType(JSModuleType.COMMON_JS); tool.setObfuscated(false);
          tool.setClassLoader(ClassLoader.getSystemClassLoader()); tool.generate();
          for(var problem: tool.getProblemProvider().getSevereProblems())
            System.err.println(problem.getText()+" "+java.util.Arrays.toString(problem.getParams()));
          if(!tool.getProblemProvider().getSevereProblems().isEmpty()) throw new AssertionError("TeaVM compilation failed");
        }
      }`,
  };
  const files = [];
  for (const [name, content] of Object.entries(sources)) {
    const file = join(temp, name); await mkdir(resolve(file, '..'), {recursive:true});
    await writeFile(file, content); files.push(file);
  }
  const cp = [temp, ...jars].join(delimiter);
  execFileSync(javac, ['--release', '21', '-cp', cp, '-d', temp, ...files], {stdio:'pipe'});
  execFileSync(java, ['-Xmx1g', '-cp', cp, 'CompileGzipFixture', temp], {encoding:'utf8', timeout:120000});
  const runner = join(temp, 'run.cjs');
  await writeFile(runner, `require('./fixture.cjs').main([], function(error) {
    if(error) { console.error(error); process.exitCode=1; return; }
    setImmediate(function() {
      const count=globalThis.readGzipFixture();
      if(count!==${payload.length}) throw Error('Callback returned before gzip finished: '+count);
      console.log('TEAVM_GZIP_SYNC_CALLBACK_OK bytes='+count);
    });
  });`);
  const output = execFileSync(process.execPath, [runner], {encoding:'utf8', timeout:15000});
  assert.equal(output.trim(), 'TEAVM_GZIP_SYNC_CALLBACK_OK bytes='+payload.length,
    'synchronous callback must complete once with the full decoded payload');
  console.log(output.trim());
} finally {
  await rm(temp, {recursive:true, force:true});
}
