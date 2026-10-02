// Gaius quality profile: turns the GPU tier from gpu-caps.js into concrete runtime settings
// (canvas pixel-ratio cap, world render scale, post-processing stages, order-independent
// transparency gate, inventory world refresh rate). Registers GaiusQuality.profile.
//
// Every setting has a URL override for testing and a persisted per-browser value for the
// switches a player may toggle at runtime (localStorage gaius.quality.settings.v1):
//   ?gaiusPost=0|1                  Gaius post-processing chain off/on (default on)
//   ?gaiusPostTier=low|mid|high|ultra  post stage set independent of the GPU tier
//   ?gaiusFxaa= ?gaiusTonemap= ?gaiusSsao= ?gaiusBloom= ?gaiusSsr=   per-stage 0|1
//   ?gaiusRenderScale=1|0.85|0.75|0.67|0.6|0.5|auto   world render scale (default 1)
//   ?gaiusTargetFps=<n>             frame-rate target of the automatic render scale (default 60)
//   ?gaiusSharpness=0..2            RCAS sharpening in stops (0 = strongest, default 0.25)
//   ?gaiusMaxDpr=<number>           canvas device-pixel-ratio cap
//   ?gaiusOit=0|1                   force improved transparency (OIT) off/on
//   ?gaiusInventoryWorldFps=<n>     world refresh rate behind inventory screens (0 = freeze)
//   ?gaiusPresetReplay=1            vanilla behaviour: re-apply the graphics preset at startup
// Vanilla behaviour is one switch away: ?gaiusPost=0&gaiusRenderScale=1&gaiusOit=0.
(function installGaiusQualityProfile(root) {
  "use strict";
  if (!root) return;
  const Q = root.GaiusQuality || (root.GaiusQuality = {});
  if (Q.profile) return;

  const SETTINGS_KEY = "gaius.quality.settings.v1";
  const SCALE_STEPS = Object.freeze([0.5, 0.6, 0.67, 0.75, 0.85, 1]);

  // Per-tier defaults. maxDpr caps the canvas allocation (the GUI always renders at the canvas
  // resolution); renderScale only scales the 3D world, which is then upscaled with EASU/RCAS.
  const TIER_DEFAULTS = Object.freeze({
    low: Object.freeze({
      maxDpr: 2,
      renderScale: 1,
      fxaa: true, tonemap: true, ssao: false, bloom: false, ssr: false,
      oit: false,
      inventoryWorldFps: 0,
      ssaoRadius: 0.8, ssaoIntensity: 0.9, bloomIntensity: 0.10, bloomThreshold: 0.80
    }),
    mid: Object.freeze({
      maxDpr: 2,
      renderScale: 1,
      fxaa: true, tonemap: true, ssao: true, bloom: true, ssr: false,
      oit: false,
      inventoryWorldFps: 15,
      ssaoRadius: 0.9, ssaoIntensity: 1.0, bloomIntensity: 0.12, bloomThreshold: 0.78
    }),
    high: Object.freeze({
      maxDpr: 2.5,
      renderScale: 1,
      fxaa: true, tonemap: true, ssao: true, bloom: true, ssr: false,
      oit: true,
      inventoryWorldFps: 30,
      ssaoRadius: 1.0, ssaoIntensity: 1.0, bloomIntensity: 0.14, bloomThreshold: 0.76
    }),
    ultra: Object.freeze({
      maxDpr: 3,
      renderScale: 1,
      fxaa: true, tonemap: true, ssao: true, bloom: true, ssr: false,
      oit: true,
      inventoryWorldFps: 60,
      ssaoRadius: 1.0, ssaoIntensity: 1.05, bloomIntensity: 0.15, bloomThreshold: 0.75
    })
  });

  const state = {
    settings: null,
    persisted: null
  };

  function params() {
    try {
      return new URLSearchParams(root.location ? root.location.search : "");
    } catch (error) {
      return null;
    }
  }

  function readPersisted() {
    if (state.persisted) return state.persisted;
    let value = {};
    try {
      const raw = root.localStorage ? root.localStorage.getItem(SETTINGS_KEY) : null;
      if (raw) {
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === "object") value = parsed;
      }
    } catch (error) {
      value = {};
    }
    state.persisted = value;
    return value;
  }

  function writePersisted(patch) {
    const value = Object.assign({}, readPersisted(), patch);
    state.persisted = value;
    try {
      if (root.localStorage) root.localStorage.setItem(SETTINGS_KEY, JSON.stringify(value));
    } catch (error) {
      // Private mode or blocked storage: the switch still applies for this page.
    }
    invalidate();
    return value;
  }

  function bool(value, fallback) {
    if (value === null || value === undefined || value === "") return fallback;
    const text = String(value).toLowerCase();
    if (text === "1" || text === "true" || text === "on" || text === "yes") return true;
    if (text === "0" || text === "false" || text === "off" || text === "no") return false;
    return fallback;
  }

  function number(value, fallback, min, max) {
    if (value === null || value === undefined || value === "") return fallback;
    const parsed = Number(value);
    if (!isFinite(parsed)) return fallback;
    return Math.max(min, Math.min(max, parsed));
  }

  function quantizeScale(value) {
    let best = SCALE_STEPS[SCALE_STEPS.length - 1];
    let distance = Infinity;
    for (let i = 0; i < SCALE_STEPS.length; i++) {
      const d = Math.abs(SCALE_STEPS[i] - value);
      if (d < distance) {
        distance = d;
        best = SCALE_STEPS[i];
      }
    }
    return best;
  }

  function currentCaps() {
    if (Q.caps && typeof Q.caps.current === "function") {
      const caps = Q.caps.current();
      if (caps) return caps;
    }
    return root.__gaiusGpuCaps || null;
  }

  function tierName(caps) {
    const name = caps && caps.tier ? String(caps.tier) : "mid";
    return TIER_DEFAULTS[name] ? name : "mid";
  }

  function resolve() {
    const caps = currentCaps();
    const tier = tierName(caps);
    const base = TIER_DEFAULTS[tier];
    const p = params();
    const get = function (name) {
      return p ? p.get(name) : null;
    };
    const persisted = readPersisted();

    const postTierParam = get("gaiusPostTier");
    const postTier = postTierParam && TIER_DEFAULTS[String(postTierParam).toLowerCase()]
        ? String(postTierParam).toLowerCase() : tier;
    const post = TIER_DEFAULTS[postTier];

    let postEnabled = typeof persisted.post === "boolean" ? persisted.post : true;
    postEnabled = bool(get("gaiusPost"), postEnabled);

    let ssr = typeof persisted.ssr === "boolean" ? persisted.ssr : post.ssr;
    ssr = bool(get("gaiusSsr"), ssr);

    let scaleSetting = persisted.renderScale !== undefined ? persisted.renderScale : base.renderScale;
    const scaleParam = get("gaiusRenderScale");
    if (scaleParam !== null && scaleParam !== "") scaleSetting = scaleParam;
    let scaleMode = "fixed";
    let renderScale = 1;
    if (String(scaleSetting).toLowerCase() === "auto") {
      scaleMode = "auto";
      renderScale = 1;
    } else {
      renderScale = quantizeScale(number(scaleSetting, 1, SCALE_STEPS[0], 1));
    }

    const dprParam = get("gaiusMaxDpr");
    const maxDpr = number(dprParam, base.maxDpr, 0.5, 4);

    let oitForced = null;
    const oitParam = get("gaiusOit");
    if (oitParam !== null && oitParam !== "") oitForced = bool(oitParam, null);

    const fpsParam = get("gaiusInventoryWorldFps");
    const inventoryWorldFps = number(fpsParam, base.inventoryWorldFps, 0, 240);

    const targetFps = number(get("gaiusTargetFps"), 60, 20, 240);

    const sharpness = number(get("gaiusSharpness"),
        typeof persisted.sharpness === "number" ? persisted.sharpness : 0.25, 0, 2);

    const settings = {
      tier: tier,
      tierIndex: ["low", "mid", "high", "ultra"].indexOf(tier),
      postTier: postTier,
      maxDpr: maxDpr,
      renderScaleMode: scaleMode,
      renderScale: renderScale,
      renderScaleSteps: SCALE_STEPS,
      targetFps: targetFps,
      sharpness: sharpness,
      post: {
        enabled: postEnabled,
        fxaa: bool(get("gaiusFxaa"), post.fxaa),
        tonemap: bool(get("gaiusTonemap"), post.tonemap),
        ssao: bool(get("gaiusSsao"), post.ssao),
        bloom: bool(get("gaiusBloom"), post.bloom),
        ssr: ssr,
        ssaoRadius: post.ssaoRadius,
        ssaoIntensity: post.ssaoIntensity,
        bloomIntensity: post.bloomIntensity,
        bloomThreshold: post.bloomThreshold
      },
      oitTier: base.oit,
      oitForced: oitForced,
      inventoryWorldFps: inventoryWorldFps,
      presetReplay: bool(get("gaiusPresetReplay"), false)
    };
    state.settings = settings;
    try {
      root.__gaiusQualitySettings = settings;
    } catch (error) {
      // ignore
    }
    return settings;
  }

  function settings() {
    return state.settings || resolve();
  }

  function invalidate() {
    state.settings = null;
  }

  // Order-independent transparency (26.3 "Improved Transparency") needs float colour targets with
  // blending: RGBA16F accumulation/transmittance targets, RGBA32F depth bounds blended with MAX,
  // and two colour outputs from one draw.
  function oitAllowed() {
    const s = settings();
    if (s.oitForced === false) return false;
    const caps = currentCaps();
    const ext = caps && caps.extensions;
    const float = caps && caps.floatTargets;
    const capable = !!(ext && float && ext.colorBufferFloat && ext.floatBlend
        && float.rgba16f && float.rgba32f && (caps.maxDrawBuffers || 0) >= 2
        && !caps.contextLost);
    if (s.oitForced === true) return capable;
    return capable && s.oitTier;
  }

  // Device-pixel-ratio for the canvas backing store. Never upsamples above the device ratio.
  function resolvePixelRatio(devicePixelRatio) {
    const raw = Number(devicePixelRatio) > 0 ? Number(devicePixelRatio) : 1;
    return Math.max(0.5, Math.min(raw, settings().maxDpr));
  }

  Q.profile = Object.freeze({
    SETTINGS_KEY: SETTINGS_KEY,
    SCALE_STEPS: SCALE_STEPS,
    TIER_DEFAULTS: TIER_DEFAULTS,
    settings: settings,
    resolve: resolve,
    invalidate: invalidate,
    oitAllowed: oitAllowed,
    resolvePixelRatio: resolvePixelRatio,
    quantizeScale: quantizeScale,
    setPostEnabled: function (enabled) {
      return writePersisted({post: !!enabled});
    },
    setSsr: function (enabled) {
      return writePersisted({ssr: !!enabled});
    },
    setRenderScale: function (value) {
      if (String(value).toLowerCase() === "auto") return writePersisted({renderScale: "auto"});
      return writePersisted({renderScale: quantizeScale(number(value, 1, SCALE_STEPS[0], 1))});
    },
    setSharpness: function (value) {
      return writePersisted({sharpness: number(value, 0.25, 0, 2)});
    },
    setTier: function (name) {
      if (Q.caps && typeof Q.caps.setOverride === "function") Q.caps.setOverride(name);
      invalidate();
      return settings();
    }
  });
})(typeof globalThis !== "undefined" ? globalThis : (typeof self !== "undefined" ? self : this));
