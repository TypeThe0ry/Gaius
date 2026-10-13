import {createHash} from "node:crypto";
import {writeFile} from "node:fs/promises";
import {join} from "node:path";

export const CAPTURE_OPTIONS = Object.freeze([
  "renderDistance:8", "simulationDistance:6", "renderClouds:false", "particles:2",
  "enableVsync:true", "maxFps:60", "bobView:false", "fov:0.0", "guiScale:2",
]);
export const CAPTURE_BOOT_SOURCE = `globalThis.__gaiusCapture={enabled:false,animationTick:0};
globalThis.__gaiusBeforeMain=()=>new Promise(resolve=>{globalThis.__gaiusReleaseBoot=resolve;});`;
export const CAPTURE_OPTIONS_EXPRESSION = `(async()=>{
  if(globalThis.__gaiusFsReady)await globalThis.__gaiusFsReady;
  const path='/gaius/options.txt';
  const encoded=${JSON.stringify(Buffer.from(CAPTURE_OPTIONS.join("\n") + "\n").toString("base64"))};
  const files=globalThis.__gaiusPersistentFiles||(globalThis.__gaiusPersistentFiles=Object.create(null));
  files[path]=encoded;
  if(typeof globalThis.__gaiusFsPut!=='function')throw new Error('capture options storage unavailable');
  globalThis.__gaiusFsPut(path,encoded);
  if(globalThis.__gaiusFsFlush)await globalThis.__gaiusFsFlush(true);
  globalThis.__gaiusReleaseBoot();
  return ${JSON.stringify(CAPTURE_OPTIONS)};
})()`;

export function stageAnimationCapture(source) {
  const pattern = /(?:\$rt_java\.)?[\w$]+_SpriteContents\$AnimationState_tick = \$this => \{([\s\S]*?)\n\};/g;
  const matches = [...source.matchAll(pattern)];
  if (matches.length !== 1) throw new Error(`expected one vanilla animation tick, found ${matches.length}`);
  const match = matches[0];
  const body = match[1];
  const subFrame = body.match(/\$this\.(\$\w+) = \$this\.\1 \+ 1 \| 0;/)?.[1];
  const frame = body.match(/\$this\.(\$\w+) = \(\$this\.\1 \+ 1 \| 0\) %/)?.[1];
  const dirty = body.match(/\$this\.(\$\w+) = 0;/)?.[1];
  if (!subFrame || !frame || !dirty) throw new Error("vanilla animation tick structure changed");
  const minecraft = source.match(/(?:let |\$rt_java\.)[\w$]+_Minecraft_getInstance = \(\) => \{[^}]*?return ([\w$.]+);/);
  const cloudField = source.match(/_Options_cloudStatus = \$this => \{\s*return \$this\.(\$\w+);/)?.[1];
  const particlesField = source.match(/_Options_particles = \$this => \{\s*return \$this\.(\$\w+);/)?.[1];
  const valueField = source.match(/_OptionInstance_get = \$this => \{\s*return \$this\.(\$\w+);/)?.[1];
  const ordinalField = source.match(/\bjl_Enum_ordinal = \$this => \{\s*return \$this\.(\$\w+);/)?.[1];
  const optionsField = source.match(/nmc_Options__init_\(([\w$]+), \$this, [\w$]+\);[\s\S]{0,160}?\$this\.(\$\w+) = \1;/)?.[2];
  if (!minecraft || !cloudField || !particlesField || !valueField || !ordinalField || !optionsField) {
    throw new Error("capture cannot verify vanilla cloud/particle options");
  }
  const observe = `const options=${minecraft[1]}.${optionsField};
capture.optionsObserved={cloudsOff:options.${cloudField}.${valueField}.${ordinalField}===0,
particlesMinimal:options.${particlesField}.${valueField}.${ordinalField}===2};`;
  const prefix = `
    const capture=globalThis.__gaiusCapture;
    if(capture&&capture.enabled===true&&Number.isInteger(capture.animationTick)
        &&capture.animationTick>=0&&capture.animationTick<=1200){
      ${observe}
      const previousFrame=$this.${frame},previousSubFrame=$this.${subFrame};
      $this.${frame}=0;$this.${subFrame}=0;
      for(let captureTick=0;captureTick<capture.animationTick;captureTick++){
        (()=>{${body}})();
      }
      $this.${dirty}=previousFrame!==$this.${frame}||previousSubFrame!==$this.${subFrame}?1:0;
      capture.animationHook='staged-vanilla-replay-v1';
      capture.animationApplications=(capture.animationApplications||0)+1;
      capture.appliedAnimationTick=capture.animationTick;
      return;
    }
`;
  const insertion = match.index + match[0].indexOf("{") + 1;
  const staged = source.slice(0, insertion) + prefix + source.slice(insertion);
  return {source: staged, receipt: {
    mode: "diagnostic-response-only", fields: {frame, subFrame, dirty},
    originalSha256: createHash("sha256").update(source).digest("hex"),
    stagedSha256: createHash("sha256").update(staged).digest("hex"),
  }};
}

// Freeze before the settling interval: random ticks can otherwise grow vines or
// decay leaves differently while two backends load the same seeded world.
export async function freezeCaptureWorld(command) {
  const evidence = await command("/tick freeze");
  if (!evidence?.ok) throw new Error("capture early freeze was not acknowledged");
  return evidence;
}

export function captureViews(player) {
  const centerX = Math.floor(player.x) + 0.5;
  const centerZ = Math.floor(player.z) + 0.5;
  const groundY = Math.floor(player.y);
  return {
    horizon: [centerX, groundY + 2, centerZ, 0, -8],
    entities: [centerX, groundY + 2.5, centerZ, 0, 12],
    high: [centerX, groundY + 30, centerZ, 45, 25],
    topdown: [centerX, groundY + 40, centerZ, 0, 90],
  };
}

export function validateCaptureViews(views) {
  const names = ["horizon", "entities", "high", "topdown"];
  if (!views || Object.keys(views).length !== names.length
      || names.some(name => !Array.isArray(views[name]) || views[name].length !== 5
        || !views[name].every(Number.isFinite))) {
    throw new Error("capture cameras require four named finite x/y/z/yaw/pitch arrays");
  }
  return Object.fromEntries(names.map(name => [name, views[name]]));
}

export function captureCameraReadyExpression(camera) {
  return `(()=>{const state=window.__gaiusMinecraftState;const audit=window.__gaiusSectionAudit;
const pipeline=window.__gaiusChunkPipelineTelemetry||{};const player=state?.player;
return !!player&&!state.screen&&audit?.uncompiled===0&&Date.now()-audit.at<5000
&&!(pipeline.compileBacklog>0)&&!(pipeline.uploadBacklog>0)
&&Math.abs(player.x-(${camera[0]}))<0.01&&Math.abs(player.y-(${camera[1]}))<0.01
&&Math.abs(player.z-(${camera[2]}))<0.01&&Math.abs(player.yaw-(${camera[3]}))<0.01
&&Math.abs(player.pitch-(${camera[4]}))<0.01;})()`;
}

export async function captureDeterministicWorld({command, evaluate, waitFor, key, sleep,
  screenshot, player, outputPath, seed, profile, stageReceipt, clientIdentity, cameras, backend = null}) {
  const result = {schema: 1, seed, profile, options: CAPTURE_OPTIONS, stageReceipt, clientIdentity,
    settings: {animationTick: 0, time: 6000, weather: "clear", tickFrozen: true},
    commands: [], views: {}, complete: false};
  const checkedCommand = async text => {
    const evidence = await command(text);
    if (!evidence?.ok) throw new Error(`capture command not acknowledged: ${text}`);
    result.commands.push({text, ...evidence});
  };
  let hidden = false;
  try {
    await checkedCommand("/tick freeze");
    await evaluate("globalThis.__gaiusCapture.enabled=true;globalThis.__gaiusCapture.animationTick=0;true");
    for (const text of ["/gamerule advance_time false", "/gamerule advance_weather false",
      "/gamerule random_tick_speed 0", "/gamerule spawn_mobs false", "/time set 6000",
      "/weather clear 1000000", "/gamemode spectator", "/kill @e[type=!minecraft:player]"]) {
      await checkedCommand(text);
    }
    const views = validateCaptureViews(cameras || captureViews(player));
    const [centerX, cameraY, centerZ] = views.horizon;
    await checkedCommand(`/summon minecraft:pig ${centerX - 1.5} ${cameraY - 1} ${centerZ + 5} {NoAI:1b,NoGravity:1b,Silent:1b,Rotation:[150f,0f]}`);
    await checkedCommand(`/summon minecraft:armor_stand ${centerX + 1.5} ${cameraY - 1} ${centerZ + 5} {NoGravity:1b,ShowArms:1b,Rotation:[200f,0f]}`);
    await sleep(1500);
    await waitFor("globalThis.__gaiusCapture?.animationApplications>0", 10000, "texture animation hook acknowledgement");
    for (const [name, camera] of Object.entries(views)) {
      if (hidden) { await key("F1", "F1", 112); hidden = false; }
      await checkedCommand(`/tp @s ${camera.join(" ")}`);
      const ready = captureCameraReadyExpression(camera);
      try {
        await waitFor(ready, 45000, `settled camera ${name}`);
      } catch (error) {
        result.unsettled = await evaluate("({player:window.__gaiusMinecraftState?.player,screen:window.__gaiusMinecraftState?.screen,audit:window.__gaiusSectionAudit,pipeline:window.__gaiusChunkPipelineTelemetry,capture:globalThis.__gaiusCapture})").catch(() => null);
        throw error;
      }
      await sleep(1500);
      await key("F1", "F1", 112); hidden = true;
      await sleep(500);
      const files = [];
      const states = [];
      for (let repeat = 0; repeat < 3; repeat++) {
        await sleep(400);
        const state = await evaluate(`(()=>{const capture=globalThis.__gaiusCapture;return {
player:window.__gaiusMinecraftState?.player,screen:window.__gaiusMinecraftState?.screen,
backend:window.__gaiusGpuBackend||${JSON.stringify(backend)},animationHook:capture?.animationHook,
animationApplications:capture?.animationApplications,animationTick:capture?.appliedAnimationTick,
options:capture?.optionsObserved,gpu:globalThis.__gaiusWebGPU?.snapshot?.()||null};})()`);
        if (!state?.animationHook || state.animationTick !== 0 || state.screen
            || !["webgl2", "webgpu"].includes(state.backend)) {
          throw new Error(`capture state is not frozen: ${name}`);
        }
        if (!state.options?.cloudsOff || !state.options?.particlesMinimal) {
          throw new Error("vanilla cloud/particle options are not confirmed");
        }
        const filename = `capture-${name}-${repeat}.png`;
        if (!await screenshot(filename)) throw new Error(`screenshot failed: ${filename}`);
        files.push(filename); states.push(state);
      }
      result.views[name] = {camera, files, states};
    }
    result.complete = true;
    return result;
  } catch (error) {
    result.error = String(error.stack || error);
    throw error;
  } finally {
    if (hidden) await key("F1", "F1", 112).catch(() => {});
    result.cleanup = {animation: await evaluate("globalThis.__gaiusCapture.enabled=false;true").catch(() => false),
      unfreeze: await command("/tick unfreeze").catch(error => ({ok: false, error: String(error)}))};
    await writeFile(join(outputPath, "capture.json"), JSON.stringify(result, null, 2));
  }
}
