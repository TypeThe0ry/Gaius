import {mkdir, writeFile} from 'node:fs/promises';
import {dirname} from 'node:path';
import {installMacrotaskDiagnostic} from './worker-macrotask-diagnostic.mjs';

// Diagnostic runs only: profiling overhead must not certify latency targets.
export async function startWorkerProfiler(cdp, outputPrefix, {
  captureProfile = true,
  runtimeConfig = null,
} = {}) {
  const entries = [];
  const pending = new Set();
  let stopping = false;
  let result;
  const traceMacrotasks = process.env.GAIUS_FILE_MACROTASK_TRACE === '1';
  const configuredKeys = runtimeConfig && typeof runtimeConfig === 'object'
    ? Object.keys(runtimeConfig)
    : [];
  const configureRuntime = configuredKeys.length > 0;
  const pauseWorker = traceMacrotasks || configureRuntime;
  const command = (entry, method, params = {}) =>
    cdp.send(method, params, 10000, entry.sessionId);
  cdp.on('Target.attachedToTarget', ({sessionId, targetInfo}) => {
    if (stopping || targetInfo.type !== 'worker') return;
    const entry = {sessionId, targetId: targetInfo.targetId, url: targetInfo.url,
      started: false, saved: false};
    entries.push(entry);
    const job = (async () => {
      try {
        if (configureRuntime) {
          const declarations = configuredKeys.map(key => {
            const value = JSON.stringify(runtimeConfig[key]);
            return `Object.defineProperty(globalThis,${JSON.stringify(key)},{` +
              `configurable:true,get:()=>${value},set:()=>{}});`;
          }).join('');
          const injected = await command(entry, 'Runtime.evaluate', {
            expression: `(()=>{${declarations}return ${JSON.stringify(runtimeConfig)}})()`,
            returnByValue: true,
          });
          if (injected.exceptionDetails) throw new Error('Worker runtime configuration failed');
          entry.runtimeConfig = injected.result?.value || runtimeConfig;
          entry.configured = true;
        }
        if (traceMacrotasks) {
          const injected = await command(entry, 'Runtime.evaluate', {
            expression: `(${installMacrotaskDiagnostic.toString()})()`,
          });
          if (injected.exceptionDetails) throw new Error('Macrotask trace injection failed');
        }
        if (captureProfile) {
          await command(entry, 'Profiler.enable');
          await command(entry, 'Profiler.setSamplingInterval', {interval: 1000});
          await command(entry, 'Profiler.start');
          entry.started = true;
        }
      } catch (error) { entry.error = String(error.message || error); }
      finally {
        if (pauseWorker) {
          try { await command(entry, 'Runtime.runIfWaitingForDebugger'); }
          catch (error) { entry.resumeError = String(error.message || error); }
        }
      }
    })();
    pending.add(job);
    void job.finally(() => pending.delete(job));
  });
  await cdp.send('Target.setAutoAttach', {
    autoAttach: true, waitForDebuggerOnStart: pauseWorker, flatten: true,
    filter: [{type: 'worker', exclude: false}, {exclude: true}],
  });
  return {
    async stop() {
      if (result) return result;
      stopping = true;
      await Promise.all([...pending]);
      for (const [index, entry] of entries.entries()) {
        if (!captureProfile || !entry.started) continue;
        try {
          if (traceMacrotasks) {
            const snapshot = await command(entry, 'Runtime.evaluate', {
              expression: 'JSON.stringify({trace:globalThis.__gaiusMacrotaskDiagnostic,worldgen:globalThis.__gaiusWorldgenStats})',
              returnByValue: true,
            });
            entry.macrotaskTrace = JSON.parse(snapshot.result.value);
          }
          const {profile} = await command(entry, 'Profiler.stop');
          entry.path = `${outputPrefix}.worker-${index}.cpuprofile`;
          await mkdir(dirname(entry.path), {recursive: true});
          await writeFile(entry.path, JSON.stringify(profile));
          entry.samples = profile.samples?.length || 0;
          entry.saved = true;
        } catch (error) { entry.error = String(error.message || error); }
      }
      let detachError = null;
      try {
        await cdp.send('Target.setAutoAttach', {
          autoAttach: false, waitForDebuggerOnStart: false, flatten: true,
        });
      } catch (error) { detachError = String(error.message || error); }
      result = {diagnosticOnly: true, latencyAcceptanceEligible: false,
        captureProfile, runtimeConfig, entries, detachError,
        complete: entries.length > 0
          && entries.every(entry => (!captureProfile || entry.saved)
            && (!configureRuntime || entry.configured)
            && !entry.error && !entry.resumeError)
          && !detachError};
      return result;
    },
  };
}
