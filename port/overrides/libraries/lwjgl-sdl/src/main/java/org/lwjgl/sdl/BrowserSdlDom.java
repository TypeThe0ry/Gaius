package org.lwjgl.sdl;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * JavaScript half of the browser SDL3 shim (work package P2, decision D3).
 *
 * <p>The DOM listeners, canvas and WebGL 2 context handling, pointer and keyboard
 * lock, canvas resolution, input warm-up, input statistics and the cooperative
 * frame-yield scheduler are copied from {@code org.lwjgl.glfw.BrowserGlfw}, which is
 * left untouched so that the Minecraft 26.2 build stays byte-identical. The JS
 * global names ({@code __gaiusWebGL}, {@code __gaiusInputStats},
 * {@code __gaiusCursorX/Y}, {@code __gaiusWantPointerLock},
 * {@code __gaiusApplyCanvasResolution}, {@code __gaiusResolvePixelRatio},
 * {@code __gaiusDisplay}, {@code __gaiusFrameTelemetry}, ...) are the same; the
 * event queue is new and is called {@code __gaiusSdlEvents}.
 *
 * <p>The event engine lives entirely in JavaScript ({@code window.__gaiusSdl}):
 * DOM listeners push neutral records
 * {@code [sdlType, i0, i1, i2, i3, f0, f1, f2, f3, text, timestampMillis]}, and
 * {@code poll()} / {@code flush(min, max)} apply the keyboard, modifier and mouse
 * state when a record leaves the queue. {@link BrowserSdlEvents} copies the current
 * record into an {@code SDL_Event}. Record fields by type:
 * <pre>
 * 768/769 KEY_DOWN/UP   i0 scancode, i1 keycode, i2 mod, i3 repeat, f0 raw key code
 * 770 TEXT_EDITING      i0 start, i1 length, text
 * 771 TEXT_INPUT        text
 * 1024 MOUSE_MOTION     i0 button state, i2 mod, f0 x, f1 y, f2 xrel, f3 yrel
 * 1025/1026 BUTTON      i0 SDL button (1..5), i1 clicks, i2 mod, f0 x, f1 y
 * 1027 MOUSE_WHEEL      i0 direction, i2 mod, f0 x, f1 y, f2 mouse x, f3 mouse y
 * 0x200..0x2FF WINDOW   i0 data1, i1 data2
 * </pre>
 */
final class BrowserSdlDom {
    static final int REC_TYPE = 0;
    static final int REC_I0 = 1;
    static final int REC_I1 = 2;
    static final int REC_I2 = 3;
    static final int REC_I3 = 4;
    static final int REC_F0 = 5;
    static final int REC_F1 = 6;
    static final int REC_F2 = 7;
    static final int REC_F3 = 8;

    private BrowserSdlDom() {
    }

    // ---------------------------------------------------------------- DOM bridge

    @JSBody(script = """
            const w = window;
            if (w.__gaiusSdl && w.__gaiusSdl.installed) return;
            const clock = () => (typeof performance !== 'undefined' && performance.now)
              ? performance.now() : Date.now();
            const events = Array.isArray(w.__gaiusSdlEvents) ? w.__gaiusSdlEvents : [];
            events.length = 0;
            w.__gaiusSdlEvents = events;
            w.__gaiusSdlEventHead = 0;
            w.__gaiusSdlKeys = Object.create(null);
            const stats = w.__gaiusInputStats || (w.__gaiusInputStats = {
              callbacks: {},
              events: {},
              callbackMisses: {},
              totalEvents: 0,
              lastEvent: null
            });
            stats.backend = 'sdl';
            const sdl = {
              installed: true,
              events: events,
              head: 0,
              current: null,
              keys: new Uint8Array(512),
              buttons: 0,
              mods: 0,
              cursorX: 0,
              cursorY: 0,
              focused: true,
              domKeys: Object.create(null),
              domButtons: 0,
              domMods: 0,
              lastX: NaN,
              lastY: NaN,
              textInput: false,
              textInputArea: null,
              relative: false,
              fullscreen: false,
              layoutMap: null,
              coalescedMotion: 0,
              flushed: 0,
              lastFlushRemoved: 0
            };
            w.__gaiusSdl = sdl;
            w.__gaiusWantPointerLock = false;
            w.__gaiusPointerLockErrors = [];
            w.__gaiusMaxDpr = Number.isFinite(Number(w.__gaiusMaxDpr))
              ? Number(w.__gaiusMaxDpr)
              : 1.0;

            // ---- records and the queue engine
            const record = (type, i0, i1, i2, i3, f0, f1, f2, f3, text) => [
              type | 0, i0 | 0, i1 | 0, i2 | 0, i3 | 0,
              +f0 || 0, +f1 || 0, +f2 || 0, +f3 || 0,
              text == null ? null : String(text), clock()
            ];
            const push = r => {
              events.push(r);
              return r;
            };
            const pushWindow = (type, data1, data2) => push(record(type, data1, data2, 0, 0, 0, 0, 0, 0, null));
            const compact = () => {
              const head = sdl.head;
              if (head >= events.length) {
                events.length = 0;
                sdl.head = 0;
              } else if (head > 128 && head * 2 > events.length) {
                events.splice(0, head);
                sdl.head = 0;
              }
              w.__gaiusSdlEventHead = sdl.head;
            };
            const apply = r => {
              switch (r[0]) {
                case 768: if (r[1] > 0 && r[1] < 512) sdl.keys[r[1]] = 1; sdl.mods = r[3]; break;
                case 769: if (r[1] > 0 && r[1] < 512) sdl.keys[r[1]] = 0; sdl.mods = r[3]; break;
                case 1024: sdl.buttons = r[1]; sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6]; break;
                case 1025:
                  sdl.buttons |= 1 << ((r[1] - 1) & 31);
                  sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6];
                  break;
                case 1026:
                  sdl.buttons &= ~(1 << ((r[1] - 1) & 31));
                  sdl.mods = r[3]; sdl.cursorX = r[5]; sdl.cursorY = r[6];
                  break;
                case 1027: sdl.mods = r[3]; break;
                case 526: sdl.focused = true; break;
                case 527: sdl.focused = false; break;
                default: break;
              }
            };
            const warmup = () => {
              if (w.__gaiusInputWarmupDone) return;
              const minecraftState = w.__gaiusMinecraftState || null;
              if (!minecraftState || !minecraftState.screen || minecraftState.level) return;
              w.__gaiusInputWarmupDone = true;
              w.__gaiusCursorX = 1;
              w.__gaiusCursorY = 1;
              push(record(1024, sdl.domButtons, 0, sdl.domMods, 0, 1, 1, 0, 0, null));
              push(record(1025, 1, 1, sdl.domMods, 0, 1, 1, 0, 0, null));
              push(record(1026, 1, 1, sdl.domMods, 0, 1, 1, 0, 0, null));
            };
            // Returns the SDL type of the record that left the queue, 0 when empty.
            // Re-entrant: a handler may flush or poll again before the outer poll
            // returns; each call only moves the shared head forward.
            sdl.poll = () => {
              if (sdl.head >= events.length) warmup();
              if (sdl.head >= events.length) {
                compact();
                sdl.current = null;
                return 0;
              }
              const r = events[sdl.head];
              events[sdl.head] = null;
              sdl.head++;
              apply(r);
              sdl.current = r;
              const key = String(r[0]);
              stats.totalEvents = (stats.totalEvents || 0) + 1;
              stats.events[key] = (stats.events[key] || 0) + 1;
              stats.lastEvent = {
                type: r[0],
                a: r[1],
                b: r[2],
                c: r[3],
                x: r[5],
                y: r[6],
                callbackInstalled: true,
                at: Date.now()
              };
              compact();
              return r[0];
            };
            sdl.peek = () => sdl.head < events.length;
            // SDL_FlushEvents: drop queued records whose type is in [min, max]
            // after applying their state; keep every other record in order.
            // Returns the number of keyboard records whose state was applied.
            sdl.flush = (min, max) => {
              let keyChanges = 0;
              let removed = 0;
              let write = sdl.head;
              for (let read = sdl.head; read < events.length; read++) {
                const r = events[read];
                if (r[0] >= min && r[0] <= max) {
                  apply(r);
                  removed++;
                  if (r[0] === 768 || r[0] === 769) keyChanges++;
                } else {
                  events[write++] = r;
                }
              }
              events.length = write;
              sdl.lastFlushRemoved = removed;
              sdl.flushed += removed;
              stats.flushedEvents = (stats.flushedEvents || 0) + removed;
              compact();
              return keyChanges;
            };
            // Mouse motion records merge while they are the newest queued record:
            // the position and state follow the latest DOM event, xrel/yrel add up.
            const pushMotion = (x, y, xrel, yrel) => {
              sdl.lastX = x;
              sdl.lastY = y;
              const n = events.length;
              if (n > sdl.head) {
                const last = events[n - 1];
                if (last && last[0] === 1024) {
                  last[1] = sdl.domButtons;
                  last[3] = sdl.domMods;
                  last[5] = +x || 0;
                  last[6] = +y || 0;
                  last[7] += +xrel || 0;
                  last[8] += +yrel || 0;
                  last[10] = clock();
                  sdl.coalescedMotion++;
                  return last;
                }
              }
              return push(record(1024, sdl.domButtons, 0, sdl.domMods, 0, x, y, xrel, yrel, null));
            };
            sdl.record = record;
            sdl.push = push;
            sdl.pushMotion = pushMotion;
            sdl.pushWindow = pushWindow;

            // ---- keyboard tables (SDL3 scancodes; see migration notes 4.4)
            const CODE_TO_SCANCODE = Object.create(null);
            for (let i = 0; i < 26; i++) CODE_TO_SCANCODE['Key' + String.fromCharCode(65 + i)] = 4 + i;
            for (let i = 1; i <= 9; i++) CODE_TO_SCANCODE['Digit' + i] = 29 + i;
            CODE_TO_SCANCODE.Digit0 = 39;
            for (let i = 1; i <= 12; i++) CODE_TO_SCANCODE['F' + i] = 57 + i;
            for (let i = 13; i <= 24; i++) CODE_TO_SCANCODE['F' + i] = 91 + i;
            for (let i = 1; i <= 9; i++) CODE_TO_SCANCODE['Numpad' + i] = 88 + i;
            CODE_TO_SCANCODE.Numpad0 = 98;
            const namedCodes = {
              Enter: 40, Escape: 41, Backspace: 42, Tab: 43, Space: 44, Minus: 45, Equal: 46,
              BracketLeft: 47, BracketRight: 48, Backslash: 49, IntlHash: 50, Semicolon: 51,
              Quote: 52, Backquote: 53, Comma: 54, Period: 55, Slash: 56, CapsLock: 57,
              PrintScreen: 70, ScrollLock: 71, Pause: 72, Insert: 73, Home: 74, PageUp: 75,
              Delete: 76, End: 77, PageDown: 78, ArrowRight: 79, ArrowLeft: 80, ArrowDown: 81,
              ArrowUp: 82, NumLock: 83, NumpadDivide: 84, NumpadMultiply: 85, NumpadSubtract: 86,
              NumpadAdd: 87, NumpadEnter: 88, NumpadDecimal: 99, IntlBackslash: 100,
              ContextMenu: 101, Power: 102, NumpadEqual: 103, Help: 117, Select: 119, Undo: 122,
              Cut: 123, Copy: 124, Paste: 125, Find: 126, AudioVolumeMute: 127,
              AudioVolumeUp: 128, AudioVolumeDown: 129, NumpadComma: 133, IntlRo: 135,
              KanaMode: 136, IntlYen: 137, Convert: 138, NonConvert: 139, Lang1: 144, Lang2: 145,
              ControlLeft: 224, ShiftLeft: 225, AltLeft: 226, MetaLeft: 227, OSLeft: 227,
              ControlRight: 228, ShiftRight: 229, AltRight: 230, MetaRight: 231, OSRight: 231,
              Sleep: 258, WakeUp: 259, MediaTrackNext: 267, MediaTrackPrevious: 268,
              MediaStop: 269, MediaPlayPause: 271, BrowserSearch: 280, BrowserHome: 281,
              BrowserBack: 282, BrowserForward: 283, BrowserStop: 284, BrowserRefresh: 285,
              BrowserFavorites: 286
            };
            Object.keys(namedCodes).forEach(code => { CODE_TO_SCANCODE[code] = namedCodes[code]; });
            const SCANCODE_TO_CODE = Object.create(null);
            Object.keys(CODE_TO_SCANCODE).forEach(code => {
              const scancode = CODE_TO_SCANCODE[code];
              if (SCANCODE_TO_CODE[scancode] === undefined) SCANCODE_TO_CODE[scancode] = code;
            });
            // Unshifted US keycodes; everything else is scancode | SDLK_SCANCODE_MASK.
            const US_KEYCODES = new Int32Array(512);
            for (let i = 0; i < 26; i++) US_KEYCODES[4 + i] = 97 + i;
            for (let i = 0; i < 9; i++) US_KEYCODES[30 + i] = 49 + i;
            US_KEYCODES[39] = 48;
            const fixedKeycodes = [
              40, 13, 41, 27, 42, 8, 43, 9, 44, 32, 45, 45, 46, 61, 47, 91, 48, 93, 49, 92,
              50, 35, 51, 59, 52, 39, 53, 96, 54, 44, 55, 46, 56, 47, 76, 127
            ];
            for (let i = 0; i < fixedKeycodes.length; i += 2) US_KEYCODES[fixedKeycodes[i]] = fixedKeycodes[i + 1];
            const scancodeOf = code => {
              const scancode = CODE_TO_SCANCODE[code];
              return scancode === undefined ? 0 : scancode;
            };
            // SDL3 keycodes are unshifted; letters follow the layout but stay Latin
            // (SDL_HINT_KEYCODE_OPTIONS "latin_letters"), digits stay digits
            // ("french_numbers") and punctuation follows the layout when it is ASCII.
            const keycodeFor = (scancode, code, key) => {
              if (scancode <= 0 || scancode >= 512) return 0;
              const fixed = US_KEYCODES[scancode];
              const letter = scancode >= 4 && scancode <= 29;
              const punctuation = scancode >= 45 && scancode <= 56;
              if (letter || punctuation) {
                let ch = null;
                const layoutMap = sdl.layoutMap;
                const layoutCode = code || SCANCODE_TO_CODE[scancode];
                if (layoutMap && layoutCode && typeof layoutMap.get === 'function') {
                  const mapped = layoutMap.get(layoutCode);
                  if (typeof mapped === 'string' && mapped.length === 1) ch = mapped;
                }
                if (ch === null && letter && typeof key === 'string' && key.length === 1) ch = key;
                if (ch !== null) {
                  const cp = ch.toLowerCase().charCodeAt(0);
                  if (letter ? (cp >= 97 && cp <= 122) : (cp > 32 && cp < 127 && !(cp >= 65 && cp <= 90))) {
                    return cp;
                  }
                }
                return fixed;
              }
              if (fixed) return fixed;
              return scancode | 0x40000000;
            };
            sdl.keycodeForScancode = scancode => keycodeFor(scancode | 0, SCANCODE_TO_CODE[scancode | 0], null);
            sdl.scancodeOf = scancodeOf;
            try {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (keyboard && typeof keyboard.getLayoutMap === 'function') {
                Promise.resolve(keyboard.getLayoutMap()).then(map => { sdl.layoutMap = map; }, () => {});
              }
            } catch (ignored) {
              sdl.layoutMap = null;
            }
            // SDL_Keymod bits: LSHIFT 1, RSHIFT 2, LCTRL 64, RCTRL 128, LALT 256,
            // RALT 512, LGUI 1024, RGUI 2048, NUM 4096, CAPS 8192, MODE 16384, SCROLL 32768.
            const MOD_BITS = {224: 64, 225: 1, 226: 256, 227: 1024, 228: 128, 229: 2, 230: 512, 231: 2048};
            const LOCK_BITS = 4096 | 8192 | 32768;
            const reconcile = (mods, pressed, mask, fallback) => pressed
              ? ((mods & mask) ? mods : (mods | fallback))
              : (mods & ~mask);
            const updateMods = (e, scancode, down) => {
              let mods = sdl.domMods & 0x0FC3;
              const bit = MOD_BITS[scancode];
              if (bit) mods = down ? (mods | bit) : (mods & ~bit);
              if (e) {
                if (typeof e.shiftKey === 'boolean') mods = reconcile(mods, e.shiftKey, 3, 1);
                if (typeof e.ctrlKey === 'boolean') mods = reconcile(mods, e.ctrlKey, 192, 64);
                if (typeof e.altKey === 'boolean') mods = reconcile(mods, e.altKey, 768, 256);
                if (typeof e.metaKey === 'boolean') mods = reconcile(mods, e.metaKey, 3072, 1024);
              }
              if (e && typeof e.getModifierState === 'function') {
                if (e.getModifierState('NumLock')) mods |= 4096;
                if (e.getModifierState('CapsLock')) mods |= 8192;
                if (e.getModifierState('AltGraph')) mods |= 16384;
                if (e.getModifierState('ScrollLock')) mods |= 32768;
              } else {
                mods |= sdl.domMods & LOCK_BITS;
              }
              sdl.domMods = mods & 0xFFFF;
              return sdl.domMods;
            };
            // Printable filter of BrowserGlfw: TEXT_INPUT follows the KEY_DOWN only
            // while SDL text input is active (SDL3 semantics).
            const printable = e => {
              const altGraph = typeof e.getModifierState === 'function' && e.getModifierState('AltGraph');
              return typeof e.key === 'string'
                && (e.key.length === 1 || (e.key.length === 2 && e.key.codePointAt(0) > 65535))
                && !e.metaKey && (!e.ctrlKey || altGraph);
            };
            const pasteChord = e => ((e.ctrlKey || e.metaKey) && !e.altKey && e.code === 'KeyV')
              || (e.shiftKey && e.code === 'Insert');

            // ---- canvas, pointer lock, keyboard lock (copied from BrowserGlfw)
            const canvas = () => document.getElementById('mc-canvas');
            const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
            const rememberPointerLockError = error => {
              const message = String(error && (error.message || error.name) || error);
              w.__gaiusPointerLockLastError = message;
              w.__gaiusPointerLockErrors.push({message: message, at: Date.now()});
              if (w.__gaiusPointerLockErrors.length > 20) w.__gaiusPointerLockErrors.shift();
            };
            const requestPointerLockIfWanted = () => {
              const c = canvas();
              if (!w.__gaiusWantPointerLock || !c || !c.requestPointerLock || document.pointerLockElement === c) {
                return;
              }
              try {
                const lockResult = c.requestPointerLock();
                if (lockResult && lockResult.catch) lockResult.catch(rememberPointerLockError);
              } catch (error) {
                rememberPointerLockError(error);
              }
            };
            const rememberKeyboardLockError = error => {
              w.__gaiusKeyboardLockPending = false;
              w.__gaiusKeyboardLockHeld = false;
              w.__gaiusKeyboardLockLastError = String(error && (error.message || error.name) || error);
            };
            const requestKeyboardLockIfWanted = () => {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (!document.fullscreenElement || !keyboard || !keyboard.lock || w.__gaiusKeyboardLockHeld || w.__gaiusKeyboardLockPending) {
                return;
              }
              w.__gaiusKeyboardLockPending = true;
              try {
                // Browser accelerators are captured only in API fullscreen.
                // Locking KeyW covers Ctrl+W while leaving Ctrl+R and Ctrl+L alone.
                const result = keyboard.lock(['KeyW']);
                if (result && result.then) {
                  result.then(() => {
                    w.__gaiusKeyboardLockPending = false;
                    w.__gaiusKeyboardLockHeld = !!document.fullscreenElement;
                  }, rememberKeyboardLockError);
                } else {
                  w.__gaiusKeyboardLockPending = false;
                  w.__gaiusKeyboardLockHeld = true;
                }
              } catch (error) {
                rememberKeyboardLockError(error);
              }
            };
            const requestGameFullscreen = () => {
              const root = document.documentElement;
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (!w.__gaiusWantPointerLock || document.fullscreenElement ||
                  !keyboard || !keyboard.lock || !root || !root.requestFullscreen) {
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
                return;
              }
              if (w.__gaiusFullscreenPending) return;
              w.__gaiusFullscreenPending = true;
              const ready = () => {
                w.__gaiusFullscreenPending = false;
                requestKeyboardLockIfWanted();
                requestPointerLockIfWanted();
              };
              const failed = error => {
                w.__gaiusFullscreenPending = false;
                rememberKeyboardLockError(error);
                requestPointerLockIfWanted();
              };
              try { Promise.resolve(root.requestFullscreen()).then(ready, failed); }
              catch (error) { failed(error); }
            };
            addEventListener('fullscreenchange', () => {
              w.__gaiusKeyboardLockHeld = false;
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (document.fullscreenElement) requestKeyboardLockIfWanted();
              else if (keyboard && keyboard.unlock) keyboard.unlock();
              // Leaving browser fullscreen ends the SDL fullscreen state that
              // SDL_SetWindowFullscreen(true) requested.
              if (!document.fullscreenElement && sdl.fullscreen) {
                sdl.fullscreen = false;
                pushWindow(536, 0, 0);
              }
            });
            // Ctrl+W is a valid sprint + forward chord in Minecraft; Chrome reserves
            // it for closing the tab. The normal handler still forwards both key events.
            addEventListener('keydown', e => {
              if (e.ctrlKey && !e.altKey && !e.metaKey && e.code === 'KeyW') {
                e.preventDefault();
              }
            }, {capture: true, passive: false});
            const urlNumber = name => {
              try {
                const value = new URLSearchParams(location.search).get(name);
                const number = Number(value);
                return Number.isFinite(number) ? number : NaN;
              } catch (ignored) {
                return NaN;
              }
            };
            w.__gaiusResolvePixelRatio = () => {
              const minecraftState = w.__gaiusMinecraftState || null;
              const inWorld = !!(minecraftState && minecraftState.level);
              const minDpr = clamp(
                inWorld
                  ? (Number(w.__gaiusWorldMinDpr) || Number(w.__gaiusMinDpr) || 1.0)
                  : (Number(w.__gaiusMenuMinDpr) || Number(w.__gaiusMinDpr) || 1.0),
                1.0,
                3.0);
              const forced = urlNumber('dpr');
              if (forced > 0) return clamp(forced, 0.2, 3.0);
              const forcedPixelRatio = urlNumber('pixelRatio');
              if (forcedPixelRatio > 0) return clamp(forcedPixelRatio, 0.2, 3.0);
              const urlMax = urlNumber('maxDpr');
              const maxDpr = urlMax > 0 ? urlMax : (Number(w.__gaiusMaxDpr) || 1.0);
              const raw = Number(w.devicePixelRatio) || 1.0;
              return clamp(Math.min(raw, maxDpr), minDpr, 3.0);
            };
            // emitEvent marks a change the game did not ask for (browser resize,
            // launcher DPR policy): 26.3 never calls SDL_GetWindowSize and learns the
            // logical size only from WINDOW_RESIZED (518), the pixel size from
            // WINDOW_PIXEL_SIZE_CHANGED (519).
            w.__gaiusApplyCanvasResolution = (width, height, emitEvent) => {
              const c = canvas();
              if (!c) return;
              const cssWidth = Math.max(1, Math.round(Number(width) || w.innerWidth || 1));
              const cssHeight = Math.max(1, Math.round(Number(height) || w.innerHeight || 1));
              const pixelRatio = w.__gaiusResolvePixelRatio();
              const framebufferWidth = Math.max(1, Math.round(cssWidth * pixelRatio));
              const framebufferHeight = Math.max(1, Math.round(cssHeight * pixelRatio));
              const previous = w.__gaiusDisplay;
              c.style.width = cssWidth + 'px';
              c.style.height = cssHeight + 'px';
              const framebufferChanged = c.width !== framebufferWidth || c.height !== framebufferHeight;
              const cssChanged = !previous || previous.cssWidth !== cssWidth || previous.cssHeight !== cssHeight;
              c.width = framebufferWidth;
              c.height = framebufferHeight;
              w.__gaiusDisplay = {
                cssWidth: cssWidth,
                cssHeight: cssHeight,
                framebufferWidth: framebufferWidth,
                framebufferHeight: framebufferHeight,
                pixelRatio: pixelRatio,
                rawDevicePixelRatio: Number(w.devicePixelRatio) || 1.0,
                maxDpr: Number(w.__gaiusMaxDpr) || 1.0
              };
              w.__gaiusCanvasRect = null;
              if (emitEvent) {
                if (cssChanged) pushWindow(518, cssWidth, cssHeight);
                if (cssChanged || framebufferChanged) pushWindow(519, framebufferWidth, framebufferHeight);
              }
            };
            const watchPixelRatio = () => {
              if (typeof w.matchMedia !== 'function') return;
              try {
                const query = w.matchMedia('(resolution: ' + (Number(w.devicePixelRatio) || 1) + 'dppx)');
                if (!query || typeof query.addEventListener !== 'function') return;
                const changed = () => {
                  try { query.removeEventListener('change', changed); } catch (ignored) {}
                  const display = w.__gaiusDisplay;
                  if (display) w.__gaiusApplyCanvasResolution(display.cssWidth, display.cssHeight, true);
                  watchPixelRatio();
                };
                query.addEventListener('change', changed);
              } catch (ignored) {
                // matchMedia resolution queries are optional.
              }
            };
            watchPixelRatio();
            const canvasRect = c => {
              const now = clock();
              const cached = w.__gaiusCanvasRect;
              if (cached && now - (w.__gaiusCanvasRectAt || 0) < 250) return cached;
              const rect = c ? c.getBoundingClientRect() : {left: 0, top: 0, width: 0, height: 0};
              w.__gaiusCanvasRect = {left: rect.left, top: rect.top, width: rect.width, height: rect.height};
              w.__gaiusCanvasRectAt = now;
              return w.__gaiusCanvasRect;
            };
            const updateCursorFromMouseEvent = e => {
              const c = canvas();
              const r = canvasRect(c);
              const locked = !!c && document.pointerLockElement === c;
              w.__gaiusCursorX = locked ? (w.__gaiusCursorX || 0) + (+e.movementX || 0) : e.clientX - r.left;
              w.__gaiusCursorY = locked ? (w.__gaiusCursorY || 0) + (+e.movementY || 0) : e.clientY - r.top;
              return [w.__gaiusCursorX, w.__gaiusCursorY];
            };
            sdl.hookCanvas = c => {
              if (!c || c.__gaiusSdlHooked || typeof c.addEventListener !== 'function') return;
              c.__gaiusSdlHooked = true;
              c.addEventListener('mouseenter', () => pushWindow(524, 0, 0));
              c.addEventListener('mouseleave', () => pushWindow(525, 0, 0));
            };
            sdl.hookCanvas(canvas());
            sdl.requestPointerLockIfWanted = requestPointerLockIfWanted;

            // ---- DOM input listeners
            addEventListener('keydown', e => {
              const scancode = scancodeOf(e.code);
              const mods = updateMods(e, scancode, true);
              const keycode = keycodeFor(scancode, e.code, e.key);
              if (scancode) {
                sdl.domKeys[scancode] = keycode;
                w.__gaiusSdlKeys[scancode] = true;
              }
              push(record(768, scancode, keycode, mods, e.repeat ? 1 : 0, e.keyCode | 0, 0, 0, 0, null));
              if (sdl.textInput && printable(e)) push(record(771, 0, 0, 0, 0, 0, 0, 0, 0, e.key));
              // Ctrl+V and Shift+Insert keep their default so that the paste event
              // below can capture the system clipboard for SDL_GetClipboardText.
              if (document.activeElement === canvas() && !pasteChord(e)) e.preventDefault();
            });
            addEventListener('keyup', e => {
              const scancode = scancodeOf(e.code);
              const mods = updateMods(e, scancode, false);
              if (scancode && sdl.domKeys[scancode] === undefined) return;
              const keycode = scancode ? sdl.domKeys[scancode] : 0;
              if (scancode) {
                delete sdl.domKeys[scancode];
                w.__gaiusSdlKeys[scancode] = false;
              }
              push(record(769, scancode, keycode, mods, 0, e.keyCode | 0, 0, 0, 0, null));
            });
            addEventListener('mousedown', e => {
              const c = canvas();
              if (c && c.focus) c.focus();
              const p = updateCursorFromMouseEvent(e);
              const mods = updateMods(e, 0, false);
              if (p[0] !== sdl.lastX || p[1] !== sdl.lastY) pushMotion(p[0], p[1], 0, 0);
              const button = (e.button | 0) + 1;
              sdl.domButtons |= 1 << ((button - 1) & 31);
              push(record(1025, button, Math.max(1, Math.min(255, e.detail | 0)), mods, 0, p[0], p[1], 0, 0, null));
              if (c && e.target === c) requestGameFullscreen();
            });
            addEventListener('mouseup', e => {
              const p = updateCursorFromMouseEvent(e);
              const mods = updateMods(e, 0, false);
              if (p[0] !== sdl.lastX || p[1] !== sdl.lastY) pushMotion(p[0], p[1], 0, 0);
              const button = (e.button | 0) + 1;
              sdl.domButtons &= ~(1 << ((button - 1) & 31));
              push(record(1026, button, Math.max(1, Math.min(255, e.detail | 0)), mods, 0, p[0], p[1], 0, 0, null));
            });
            addEventListener('mousemove', e => {
              const p = updateCursorFromMouseEvent(e);
              updateMods(e, 0, false);
              pushMotion(p[0], p[1], +e.movementX || 0, +e.movementY || 0);
            });
            // SDL3 wheel: y > 0 away from the user, x > 0 to the right.
            addEventListener('wheel', e => {
              const scale = e.deltaMode === 1 ? 100 / 3 : (e.deltaMode === 2 ? 100 : 1);
              const x = (+e.deltaX || 0) * scale / 100;
              const y = -(+e.deltaY || 0) * scale / 100;
              const mods = updateMods(e, 0, false);
              push(record(1027, 0, 0, mods, 0, x, y, w.__gaiusCursorX || 0, w.__gaiusCursorY || 0, null));
              if (document.activeElement === canvas()) e.preventDefault();
            }, {passive: false});
            addEventListener('focus', () => pushWindow(526, 0, 0));
            // SDL resets the keyboard on focus loss; also release held mouse buttons
            // so that nothing stays pressed while the page is in the background.
            addEventListener('blur', () => {
              Object.keys(sdl.domKeys).forEach(key => {
                const scancode = key | 0;
                push(record(769, scancode, sdl.domKeys[key], 0, 0, 0, 0, 0, 0, null));
                w.__gaiusSdlKeys[scancode] = false;
              });
              sdl.domKeys = Object.create(null);
              for (let button = 1; button <= 5; button++) {
                if (sdl.domButtons & (1 << (button - 1))) {
                  push(record(1026, button, 1, 0, 0, sdl.lastX || 0, sdl.lastY || 0, 0, 0, null));
                }
              }
              sdl.domButtons = 0;
              sdl.domMods &= LOCK_BITS;
              pushWindow(527, 0, 0);
            });
            addEventListener('resize', () => {
              w.__gaiusApplyCanvasResolution(w.innerWidth, w.innerHeight, true);
            });
            addEventListener('beforeunload', () => {
              const keyboard = typeof navigator !== 'undefined' ? navigator.keyboard : null;
              if (keyboard && keyboard.unlock) {
                try { keyboard.unlock(); } catch (ignored) {}
              }
              w.__gaiusKeyboardLockHeld = false;
              w.__gaiusKeyboardLockPending = false;
              pushWindow(528, 0, 0);
            });
            if (typeof document.addEventListener === 'function') {
              document.addEventListener('paste', e => {
                try {
                  const text = e.clipboardData && e.clipboardData.getData('text/plain');
                  if (typeof text === 'string') w.__gaiusClipboard = text;
                } catch (ignored) {
                  // Clipboard data is best effort.
                }
                if (document.activeElement === canvas()) e.preventDefault();
              });
            }

            // ---- game-requested window state
            sdl.setTextInput = on => { sdl.textInput = !!on; };
            sdl.setTextInputArea = (x, y, width, height, cursor) => {
              sdl.textInputArea = {x: x | 0, y: y | 0, w: width | 0, h: height | 0, cursor: cursor | 0};
            };
            sdl.setRelative = on => {
              sdl.relative = !!on;
              if (on) {
                w.__gaiusWantPointerLock = true;
                // grabMouse normally runs inside the poll that handled the click, so
                // the page still has transient activation; the canvas mousedown path
                // stays as the fallback.
                requestPointerLockIfWanted();
              } else {
                w.__gaiusWantPointerLock = false;
                if (document.exitPointerLock) {
                  try {
                    const exitResult = document.exitPointerLock();
                    if (exitResult && exitResult.catch) exitResult.catch(() => {});
                  } catch (ignored) {
                    // Pointer-lock exit is best effort.
                  }
                }
              }
            };
            sdl.warp = (x, y) => {
              w.__gaiusCursorX = +x || 0;
              w.__gaiusCursorY = +y || 0;
              sdl.cursorX = w.__gaiusCursorX;
              sdl.cursorY = w.__gaiusCursorY;
              sdl.lastX = sdl.cursorX;
              sdl.lastY = sdl.cursorY;
            };
            const CURSORS = [
              'default', 'text', 'wait', 'crosshair', 'progress', 'nwse-resize', 'nesw-resize',
              'ew-resize', 'ns-resize', 'move', 'not-allowed', 'pointer', 'nw-resize', 'n-resize',
              'ne-resize', 'e-resize', 'se-resize', 's-resize', 'sw-resize', 'w-resize'
            ];
            sdl.setCursor = id => {
              const c = canvas();
              if (!c || !c.style) return;
              c.style.cursor = CURSORS[id | 0] || 'default';
            };
            sdl.setFullscreen = on => {
              on = !!on;
              if (on === sdl.fullscreen) return true;
              const root = document.documentElement;
              if (on) {
                if (!document.fullscreenElement) {
                  if (!root || !root.requestFullscreen) return false;
                  try {
                    Promise.resolve(root.requestFullscreen()).then(() => {}, () => {
                      if (sdl.fullscreen) {
                        sdl.fullscreen = false;
                        pushWindow(536, 0, 0);
                      }
                    });
                  } catch (error) {
                    return false;
                  }
                }
                sdl.fullscreen = true;
                pushWindow(535, 0, 0);
                return true;
              }
              sdl.fullscreen = false;
              pushWindow(536, 0, 0);
              if (document.fullscreenElement && document.exitFullscreen) {
                try {
                  const exitResult = document.exitFullscreen();
                  if (exitResult && exitResult.catch) exitResult.catch(() => {});
                } catch (ignored) {
                  // Leaving fullscreen is best effort.
                }
              }
              return true;
            };
            """)
    static native void installDomBridge();

    // ---------------------------------------------------------------- event engine

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.poll()|0) : 0;")
    static native int poll();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.peek());")
    static native boolean peek();

    @JSBody(params = {"index"}, script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? (r[index]|0) : 0;")
    static native int recordInt(int index);

    @JSBody(params = {"index"}, script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? +r[index] : 0;")
    static native double recordDouble(int index);

    @JSBody(script = "const s=window.__gaiusSdl; const r=s&&s.current; return r && r[9] != null ? String(r[9]) : null;")
    static native String recordText();

    @JSBody(script = "const s=window.__gaiusSdl; const r=s&&s.current; return r ? +r[10] : 0;")
    static native double recordTimestamp();

    @JSBody(params = {"min", "max"}, script = "const s=window.__gaiusSdl; return s ? (s.flush(min, max)|0) : 0;")
    static native int flush(int min, int max);

    @JSBody(params = {"scancode"}, script = "const s=window.__gaiusSdl; return s ? (s.keys[scancode & 511]|0) : 0;")
    static native int keyState(int scancode);

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.mods & 0xFFFF) : 0;")
    static native int modState();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? (s.buttons|0) : 0;")
    static native int buttonState();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? +s.cursorX : 0;")
    static native double cursorX();

    @JSBody(script = "const s=window.__gaiusSdl; return s ? +s.cursorY : 0;")
    static native double cursorY();

    @JSBody(script = "const s=window.__gaiusSdl; return !s || s.focused !== false;")
    static native boolean focused();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.relative);")
    static native boolean relativeMouseMode();

    @JSBody(script = "const s=window.__gaiusSdl; return !!(s && s.fullscreen);")
    static native boolean fullscreen();

    @JSBody(params = {"scancode"}, script = "const s=window.__gaiusSdl; return s ? (s.keycodeForScancode(scancode)|0) : 0;")
    static native int keycodeForScancode(int scancode);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; if (s) s.setTextInput(on);")
    static native void setTextInput(boolean on);

    @JSBody(params = {"x", "y", "width", "height", "cursor"},
            script = "const s=window.__gaiusSdl; if (s) s.setTextInputArea(x, y, width, height, cursor);")
    static native void setTextInputArea(int x, int y, int width, int height, int cursor);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; if (s) s.setRelative(on);")
    static native void setRelativeMouseMode(boolean on);

    @JSBody(params = {"x", "y"}, script = "const s=window.__gaiusSdl; if (s) s.warp(x, y);")
    static native void warpMouse(double x, double y);

    @JSBody(params = {"systemCursor"}, script = "const s=window.__gaiusSdl; if (s) s.setCursor(systemCursor);")
    static native void setCursor(int systemCursor);

    @JSBody(params = {"on"}, script = "const s=window.__gaiusSdl; return s ? !!s.setFullscreen(on) : false;")
    static native boolean setFullscreen(boolean on);

    // ---------------------------------------------------------------- canvas and context

    /** Makes sure {@code #mc-canvas} exists without changing its size. */
    @JSBody(script = """
            let canvasElement = document.getElementById('mc-canvas');
            if (!canvasElement) {
              canvasElement = document.createElement('canvas');
              canvasElement.id = 'mc-canvas';
              canvasElement.tabIndex = 0;
              document.body.appendChild(canvasElement);
            }
            const s = window.__gaiusSdl;
            if (s && s.hookCanvas) s.hookCanvas(canvasElement);
            """)
    static native void prepareCanvas();

    /** The first visible SDL window adopts the canvas (BrowserGlfw.createCanvas parity). */
    @JSBody(params = {"width", "height", "title"}, script = """
            const canvasElement = document.getElementById('mc-canvas');
            if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const pixelRatio = Math.min(devicePixelRatio || 1, 1);
              canvasElement.style.width = width + 'px'; canvasElement.style.height = height + 'px';
              canvasElement.width = Math.max(1, Math.round(width * pixelRatio));
              canvasElement.height = Math.max(1, Math.round(height * pixelRatio));
            }
            document.title = title;
            if (canvasElement.focus) canvasElement.focus();
            """)
    static native void showMainWindow(int width, int height, String title);

    /**
     * Contract C5: creates {@code window.__gaiusWebGL} once (same attributes as
     * BrowserGlfw.createCanvas) and reports whether it exists.
     */
    @JSBody(script = """
            if (window.__gaiusWebGL) return true;
            const canvasElement = document.getElementById('mc-canvas');
            if (!canvasElement || !canvasElement.getContext) return false;
            let preserveDrawingBuffer = false;
            try {
              preserveDrawingBuffer = new URLSearchParams(location.search).get('preserveDrawingBuffer') === '1';
            } catch (ignored) {
              preserveDrawingBuffer = false;
            }
            window.__gaiusWebGL = canvasElement.getContext('webgl2', {
              alpha: false,
              antialias: false,
              depth: true,
              stencil: true,
              powerPreference: 'high-performance',
              preserveDrawingBuffer: preserveDrawingBuffer
            });
            return !!window.__gaiusWebGL;
            """)
    static native boolean createContext();

    @JSBody(params = {"width", "height"}, script = """
            const canvasElement = document.getElementById('mc-canvas'); if (!canvasElement) return;
            if (window.__gaiusApplyCanvasResolution) {
              window.__gaiusApplyCanvasResolution(width, height, false);
            } else {
              const pixelRatio = Math.min(devicePixelRatio || 1, 1);
              canvasElement.style.width = width + 'px'; canvasElement.style.height = height + 'px';
              canvasElement.width = Math.max(1, Math.round(width * pixelRatio));
              canvasElement.height = Math.max(1, Math.round(height * pixelRatio));
            }
            """)
    static native void resizeCanvas(int width, int height);

    @JSBody(params = {"title"}, script = "document.title = title;")
    static native void setTitle(String title);

    @JSBody(script = """
            const display = window.__gaiusDisplay;
            if (display && display.cssWidth > 0) return display.cssWidth | 0;
            const canvasElement = document.getElementById('mc-canvas');
            return canvasElement ? Math.round(canvasElement.getBoundingClientRect().width) : (innerWidth | 0);
            """)
    static native int canvasCssWidth();

    @JSBody(script = """
            const display = window.__gaiusDisplay;
            if (display && display.cssHeight > 0) return display.cssHeight | 0;
            const canvasElement = document.getElementById('mc-canvas');
            return canvasElement ? Math.round(canvasElement.getBoundingClientRect().height) : (innerHeight | 0);
            """)
    static native int canvasCssHeight();

    @JSBody(script = "const canvasElement = document.getElementById('mc-canvas'); return canvasElement ? canvasElement.width : innerWidth;")
    static native int framebufferWidth();

    @JSBody(script = "const canvasElement = document.getElementById('mc-canvas'); return canvasElement ? canvasElement.height : innerHeight;")
    static native int framebufferHeight();

    @JSBody(script = "return screen.width | 0;")
    static native int screenWidth();

    @JSBody(script = "return screen.height | 0;")
    static native int screenHeight();

    @JSBody(script = "return window.__gaiusResolvePixelRatio ? window.__gaiusResolvePixelRatio() : (devicePixelRatio || 1);")
    static native double devicePixelRatio();

    // ---------------------------------------------------------------- misc

    @JSBody(script = "return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();")
    static native double nowMillis();

    @JSBody(params = {"value"}, script = """
            window.__gaiusClipboard = value;
            try {
              if (navigator.clipboard && navigator.clipboard.writeText) {
                const result = navigator.clipboard.writeText(value);
                if (result && result.catch) result.catch(() => {});
              }
            } catch (ignored) {
              // The in-page clipboard above still round-trips inside the game.
            }
            """)
    static native void writeClipboard(String value);

    @JSBody(script = "return window.__gaiusClipboard == null ? '' : String(window.__gaiusClipboard);")
    static native String readClipboard();

    @JSBody(params = {"url"}, script = """
            const lower = String(url).toLowerCase();
            if (lower.indexOf('https:') !== 0 && lower.indexOf('http:') !== 0) return false;
            try {
              // With noopener the return value is always null, so a blocked popup
              // cannot be told apart from an opened one.
              window.open(url, '_blank', 'noopener,noreferrer');
              return true;
            } catch (ignored) {
              return false;
            }
            """)
    static native boolean openUrl(String url);

    @JSBody(params = {"message"}, script = "if (typeof console !== 'undefined') console.error(message);")
    static native void consoleError(String message);

    // ---------------------------------------------------------------- frame pacing
    // Copied from BrowserGlfw.swapBuffersJs / yieldAfterPresent / scheduleFrameYield.

    @JSBody(script = """
            const hidden=document.visibilityState!=='visible';
            const fps=window.__gaiusFps || (window.__gaiusFps={});
            const telemetry=window.__gaiusFrameTelemetry;
            let now;
            if (telemetry && telemetry.enabled) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
              if (!Number.isFinite(telemetry.startedAt)) telemetry.startedAt=now;
              const previous=telemetry.lastFrameAt;
              if (Number.isFinite(previous) && previous > 0) {
                const frameElapsed=Math.max(0, now-previous);
                const bucket=Math.min(4000, Math.floor(frameElapsed*4));
                let histogram=telemetry.histogram;
                if (!histogram || histogram.length !== 4001) {
                  histogram=new Uint32Array(4001);
                  telemetry.histogram=histogram;
                }
                histogram[bucket]=histogram[bucket]+1;
                telemetry.frameCount=(telemetry.frameCount||0)+1;
                telemetry.totalFrameMillis=(telemetry.totalFrameMillis||0)+frameElapsed;
                telemetry.longestFrameMillis=Math.max(telemetry.longestFrameMillis||0, frameElapsed);
                if (frameElapsed >= 500) {
                  telemetry.freezeCount=(telemetry.freezeCount||0)+1;
                }
                let samples=telemetry.frameTimes;
                if (!(samples instanceof Float32Array)) {
                  const requested=Number(telemetry.sampleCapacity);
                  const capacity=Number.isFinite(requested)
                    ? Math.max(1024, Math.min(65536, Math.floor(requested)))
                    : 65536;
                  samples=new Float32Array(capacity);
                  telemetry.frameTimes=samples;
                  telemetry.sampleCapacity=capacity;
                  telemetry.sampleWriteIndex=0;
                  telemetry.sampleCount=0;
                }
                const writeIndex=(Number(telemetry.sampleWriteIndex)||0)%samples.length;
                samples[writeIndex]=frameElapsed;
                telemetry.sampleWriteIndex=(writeIndex+1)%samples.length;
                telemetry.sampleCount=Math.min(
                  samples.length,
                  (Number(telemetry.sampleCount)||0)+1
                );
              }
              if (hidden) telemetry.hiddenFrameCount=(telemetry.hiddenFrameCount||0)+1;
              else telemetry.visibleFrameCount=(telemetry.visibleFrameCount||0)+1;
              telemetry.lastFrameAt=now;
            }
            fps.gameFrames=(fps.gameFrames||0)+1;
            fps.gameSampleCounter=((fps.gameSampleCounter||0)+1)&15;
            if (fps.gameSampleCounter !== 0 && fps.gameLastSampleAt) {
              if (hidden) {
                window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
              }
              return hidden;
            }
            if (now === undefined) {
              now=(typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
            }
            if (!fps.gameLastSampleAt) fps.gameLastSampleAt=now;
            const elapsed=now-fps.gameLastSampleAt;
            if (elapsed >= 1000) {
              fps.gameFps=Math.round((fps.gameFrames*1000/elapsed)*10)/10;
              fps.gameFrames=0;
              fps.gameLastSampleAt=now;
            }
            if (hidden) {
              window.__gaiusBackgroundFrameThrottles=(window.__gaiusBackgroundFrameThrottles||0)+1;
            }
            return hidden;
            """)
    private static native boolean swapBuffersJs();

    @Async
    private static native void yieldAfterPresent(boolean hidden, int interval);

    private static void yieldAfterPresent(boolean hidden, int interval, AsyncCallback<Void> callback) {
        scheduleFrameYield(hidden, interval, () -> callback.complete(null));
    }

    @JSBody(params = {"hidden", "interval", "resume"}, script = """
            const root=typeof window!=='undefined' ? window : globalThis;
            const telemetry=root.__gaiusFrameTelemetry;
            const telemetryEnabled=!!(telemetry && telemetry.enabled);
            const synchronizedToDisplay=Number(interval)!==0;
            const clock=() => (typeof performance!=='undefined' && performance.now)
              ? performance.now()
              : Date.now();
            const requestedAt=telemetryEnabled ? clock() : 0;
            if (telemetryEnabled) {
              telemetry.yieldRequestCount=(telemetry.yieldRequestCount||0)+1;
              telemetry.pendingYieldCount=Math.max(
                0,Number(telemetry.pendingYieldCount)||0)+1;
              telemetry.maxPendingYieldCount=Math.max(
                Number(telemetry.maxPendingYieldCount)||0,
                telemetry.pendingYieldCount);
              telemetry.duplicateYieldCallbackCount=
                Number(telemetry.duplicateYieldCallbackCount)||0;
              telemetry.swapInterval=Number(interval)||0;
              if (synchronizedToDisplay) {
                telemetry.vsyncYieldCount=(telemetry.vsyncYieldCount||0)+1;
              } else {
                telemetry.uncappedYieldCount=(telemetry.uncappedYieldCount||0)+1;
              }
              if (hidden) telemetry.hiddenYieldCount=(telemetry.hiddenYieldCount||0)+1;
              else telemetry.visibleYieldCount=(telemetry.visibleYieldCount||0)+1;
            }
            let resumed=false;
            let watchdog=-1;
            let activeMessageScheduler=null;
            let activeMessageTaskId=0;
            const retireMessageChannel=scheduler => {
              const channel=scheduler && scheduler.channel;
              if (!channel) return;
              try { channel.port1.onmessage=null; } catch (ignored) {}
              try { if (channel.port1.close) channel.port1.close(); } catch (ignored) {}
              try { if (channel.port2.close) channel.port2.close(); } catch (ignored) {}
              scheduler.channel=null;
              if (telemetryEnabled) {
                telemetry.messageChannelRebuildCount=
                  (Number(telemetry.messageChannelRebuildCount)||0)+1;
              }
            };
            const detachMessageTask=failed => {
              const scheduler=activeMessageScheduler;
              const taskId=activeMessageTaskId;
              activeMessageScheduler=null;
              activeMessageTaskId=0;
              if (!scheduler || !taskId || !(scheduler.tasks instanceof Map)) return;
              const removed=scheduler.tasks.delete(taskId);
              if (removed && telemetryEnabled) {
                telemetry.cancelledMessageTaskCount=
                  (Number(telemetry.cancelledMessageTaskCount)||0)+1;
              }
              if (failed) retireMessageChannel(scheduler);
            };
            const finish=source => {
              if (resumed) {
                if (telemetryEnabled) {
                  telemetry.duplicateYieldCallbackCount=
                    (Number(telemetry.duplicateYieldCallbackCount)||0)+1;
                }
                return;
              }
              resumed=true;
              if (watchdog >= 0) clearTimeout(watchdog);
              if (source==='message') {
                activeMessageScheduler=null;
                activeMessageTaskId=0;
              } else {
                detachMessageTask(source==='watchdog');
              }
              if (telemetryEnabled) {
                telemetry.pendingYieldCount=Math.max(
                  0,(Number(telemetry.pendingYieldCount)||0)-1);
                const delay=Math.max(0, clock()-requestedAt);
                telemetry.yieldCompletionCount=(telemetry.yieldCompletionCount||0)+1;
                telemetry.lastYieldResumeDelayMillis=delay;
                telemetry.totalYieldResumeDelayMillis=(telemetry.totalYieldResumeDelayMillis||0)+delay;
                telemetry.longestYieldResumeDelayMillis=Math.max(
                  telemetry.longestYieldResumeDelayMillis||0,
                  delay
                );
                if (source==='message') {
                  telemetry.messageChannelYieldCount=(telemetry.messageChannelYieldCount||0)+1;
                } else if (source==='scheduler') {
                  telemetry.schedulerYieldCount=(telemetry.schedulerYieldCount||0)+1;
                } else {
                  telemetry.timerYieldCount=(telemetry.timerYieldCount||0)+1;
                  if (source==='watchdog') {
                    telemetry.watchdogYieldCount=(telemetry.watchdogYieldCount||0)+1;
                  }
                }
              }
              resume();
            };
            const postTask=() => {
              let scheduler=root.__gaiusFrameYieldScheduler;
              if (!scheduler || !(scheduler.tasks instanceof Map)) {
                if (scheduler && scheduler.channel) retireMessageChannel(scheduler);
                scheduler={tasks:new Map(),channel:null,nextTaskId:1};
                root.__gaiusFrameYieldScheduler=scheduler;
              }
              if (!scheduler.channel) {
                if (typeof MessageChannel==='function') {
                  try {
                    const channel=new MessageChannel();
                    channel.port1.onmessage=event => {
                      const taskId=Number(event && event.data)||0;
                      const task=scheduler.tasks.get(taskId);
                      if (!task) return;
                      scheduler.tasks.delete(taskId);
                      task();
                    };
                    scheduler.channel=channel;
                  } catch (ignored) {
                    if (telemetryEnabled) {
                      telemetry.messageChannelCreateFailureCount=
                        (Number(telemetry.messageChannelCreateFailureCount)||0)+1;
                    }
                  }
                }
              }
              if (scheduler.channel) {
                let taskId=(Number(scheduler.nextTaskId)||1)>>>0;
                if (taskId===0) taskId=1;
                scheduler.nextTaskId=(taskId+1)>>>0;
                activeMessageScheduler=scheduler;
                activeMessageTaskId=taskId;
                scheduler.tasks.set(taskId,() => finish('message'));
                try {
                  scheduler.channel.port2.postMessage(taskId);
                } catch (ignored) {
                  detachMessageTask(false);
                  retireMessageChannel(scheduler);
                  if (telemetryEnabled) {
                    telemetry.messageChannelPostFailureCount=
                      (Number(telemetry.messageChannelPostFailureCount)||0)+1;
                  }
                  setTimeout(() => finish('timer'), 0);
                }
              } else {
                setTimeout(() => finish('timer'), 0);
              }
            };
            if (hidden) {
              setTimeout(() => finish('timer'), 50);
            } else if (synchronizedToDisplay && typeof requestAnimationFrame==='function') {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              requestAnimationFrame(() => {
                if (resumed) return;
                if (telemetryEnabled) {
                  const delay=Math.max(0, clock()-requestedAt);
                  telemetry.presentToRafCount=(telemetry.presentToRafCount||0)+1;
                  telemetry.lastPresentToRafMillis=delay;
                  telemetry.totalPresentToRafMillis=(telemetry.totalPresentToRafMillis||0)+delay;
                  telemetry.longestPresentToRafMillis=Math.max(
                    telemetry.longestPresentToRafMillis||0,
                    delay
                  );
                }
                postTask();
              });
            } else {
              watchdog=setTimeout(() => finish('watchdog'), 100);
              // Keep every visible uncapped present on the same MessageChannel task path.
              // Mixing scheduler.yield() into every fourth present makes Chromium defer that
              // continuation behind compositor arbitration, producing a stable 3:1 cadence
              // and periodic multi-refresh frame bubbles on high-refresh displays.
              postTask();
            }
            """)
    private static native void scheduleFrameYield(boolean hidden, int interval, FrameYieldCallback resume);

    /**
     * SDL_GL_SwapWindow body: present bookkeeping, then the per-frame cooperative
     * yield (frame source of performance-contract.json on 26.3).
     */
    static void swapWindow(int swapInterval) {
        boolean hidden = swapBuffersJs();
        yieldAfterPresent(hidden, swapInterval);
    }

    @JSFunctor
    interface FrameYieldCallback extends JSObject {
        void run();
    }
}
