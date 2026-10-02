// Gaius boot screen art: a Minecraft-style launcher look drawn procedurally (no game assets).
// Injected by port/scripts/postprocess-index-html.py (apply_gaius_boot_art). It only paints the
// boot overlay and mirrors the launcher's existing progress (window.__gaiusSetBootProgress); the
// boot flow itself is unchanged.
(function installGaiusBootArt() {
  "use strict";
  const doc = document;
  const bootScreen = doc.getElementById("boot-screen");
  const bootBrand = doc.getElementById("boot-brand");
  const progressText = doc.getElementById("boot-progress-text");
  if (!bootScreen || !bootBrand) return;

  // 5x7 bitmap glyphs (rows top to bottom, "#" = pixel). Uppercase, digits and punctuation.
  const GLYPHS = {
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "B": ["####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."],
    "C": [".###.", "#...#", "#....", "#....", "#....", "#...#", ".###."],
    "D": ["####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."],
    "E": ["#####", "#....", "#....", "####.", "#....", "#....", "#####"],
    "F": ["#####", "#....", "#....", "####.", "#....", "#....", "#...."],
    "G": [".####", "#....", "#....", "#.###", "#...#", "#...#", ".####"],
    "H": ["#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
    "J": ["....#", "....#", "....#", "....#", "#...#", "#...#", ".###."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "L": ["#....", "#....", "#....", "#....", "#....", "#....", "#####"],
    "M": ["#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#"],
    "N": ["#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"],
    "O": [".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "P": ["####.", "#...#", "#...#", "####.", "#....", "#....", "#...."],
    "Q": [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"],
    "R": ["####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"],
    "S": [".####", "#....", "#....", ".###.", "....#", "....#", "####."],
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "V": ["#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."],
    "W": ["#...#", "#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#"],
    "X": ["#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#"],
    "Y": ["#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."],
    "Z": ["#####", "....#", "...#.", "..#..", ".#...", "#....", "#####"],
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": ["..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###."],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": [".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."],
    " ": ["...", "...", "...", "...", "...", "...", "..."],
    ".": [".", ".", ".", ".", ".", ".", "#"],
    ",": ["..", "..", "..", "..", "..", ".#", "#."],
    ":": [".", "#", ".", ".", ".", "#", "."],
    "!": ["#", "#", "#", "#", "#", ".", "#"],
    "?": [".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#.."],
    "'": ["#", "#", ".", ".", ".", ".", "."],
    "-": ["....", "....", "....", "####", "....", "....", "...."],
    "+": [".....", "..#..", "..#..", "#####", "..#..", "..#..", "....."],
    "/": ["....#", "...#.", "...#.", "..#..", ".#...", ".#...", "#...."],
    "%": ["##..#", "##..#", "...#.", "..#..", ".#...", "#..##", "#..##"],
    "(": [".#", "#.", "#.", "#.", "#.", "#.", ".#"],
    ")": ["#.", ".#", ".#", ".#", ".#", ".#", "#."],
    "_": ["....", "....", "....", "....", "....", "....", "####"],
  };

  function glyph(ch) {
    return GLYPHS[ch] || GLYPHS[ch.toUpperCase()] || GLYPHS["?"];
  }

  function textWidth(text) {
    let width = 0;
    for (const ch of String(text)) width += glyph(ch)[0].length + 1;
    return Math.max(0, width - 1);
  }

  // Draws text with a Minecraft-style drop shadow; returns the drawn width in pixels.
  function drawText(ctx, text, x, y, scale, color, shadow) {
    let cx = x;
    for (const ch of String(text)) {
      const rows = glyph(ch);
      for (let row = 0; row < rows.length; row++) {
        for (let col = 0; col < rows[row].length; col++) {
          if (rows[row][col] !== "#") continue;
          if (shadow) {
            ctx.fillStyle = shadow;
            ctx.fillRect(cx + (col + 1) * scale, y + (row + 1) * scale, scale, scale);
          }
          ctx.fillStyle = color;
          ctx.fillRect(cx + col * scale, y + row * scale, scale, scale);
        }
      }
      cx += (rows[0].length + 1) * scale;
    }
    return cx - x - scale;
  }

  // Small deterministic PRNG so the texture is stable between frames and reloads.
  function rng(seed) {
    let s = seed >>> 0 || 1;
    return function next() {
      s ^= s << 13; s >>>= 0;
      s ^= s >>> 17;
      s ^= s << 5; s >>>= 0;
      return s / 4294967296;
    };
  }

  // A darkened 16x16 dirt tile, the classic Minecraft menu background, generated per pixel.
  function dirtTile() {
    const tile = doc.createElement("canvas");
    tile.width = 16;
    tile.height = 16;
    const ctx = tile.getContext("2d");
    const random = rng(20261002);
    const palette = ["#4b3523", "#563d29", "#5f4430", "#3f2c1d", "#6b4e37", "#47331f", "#7a5b41"];
    for (let y = 0; y < 16; y++) {
      for (let x = 0; x < 16; x++) {
        const r = random();
        ctx.fillStyle = palette[Math.min(palette.length - 1, Math.floor(r * r * palette.length * 1.15))];
        ctx.fillRect(x, y, 1, 1);
      }
    }
    // Scatter a few small pebbles like the vanilla dirt texture.
    for (let i = 0; i < 9; i++) {
      const x = Math.floor(random() * 15);
      const y = Math.floor(random() * 15);
      ctx.fillStyle = random() < 0.5 ? "#8a8a84" : "#6e6e68";
      ctx.fillRect(x, y, 1, 1);
      if (random() < 0.5) ctx.fillRect(x + 1, y, 1, 1);
    }
    ctx.fillStyle = "rgba(0, 0, 0, 0.62)";
    ctx.fillRect(0, 0, 16, 16);
    return tile.toDataURL("image/png");
  }

  // Blocky stone logo: each glyph cell becomes a textured block with an extruded side.
  const LOGO = {
    "G": [".#####", "##....", "##....", "##.###", "##...#", "##...#", ".#####"],
    "A": [".####.", "##..##", "##..##", "######", "##..##", "##..##", "##..##"],
    "I": ["####", ".##.", ".##.", ".##.", ".##.", ".##.", "####"],
    "U": ["##..##", "##..##", "##..##", "##..##", "##..##", "##..##", ".####."],
    "S": [".#####", "##....", "##....", ".####.", "....##", "....##", "#####."],
  };

  function drawLogo(canvas, word) {
    const cell = 10;
    const depth = 4;
    const gap = 1;
    let cols = 0;
    for (const ch of word) cols += LOGO[ch][0].length + gap;
    cols -= gap;
    const pad = 2;
    const width = (cols + pad * 2) * cell + depth * 2;
    const height = (7 + pad * 2) * cell + depth * 2;
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext("2d");
    ctx.imageSmoothingEnabled = false;
    const cells = [];
    let ox = 0;
    for (const ch of word) {
      const rows = LOGO[ch];
      for (let r = 0; r < rows.length; r++) {
        for (let c = 0; c < rows[r].length; c++) {
          if (rows[r][c] === "#") cells.push([ox + c, r]);
        }
      }
      ox += rows[0].length + gap;
    }
    const at = (c, r) => [(c + pad) * cell, (r + pad) * cell];
    // Black outline, then the extruded side, then the stone faces.
    ctx.fillStyle = "#0d0d0d";
    for (const [c, r] of cells) {
      const [x, y] = at(c, r);
      ctx.fillRect(x - 3, y - 3, cell + 6 + depth, cell + 6 + depth);
    }
    for (let d = depth; d >= 1; d--) {
      const shade = 46 + d * 6;
      ctx.fillStyle = "rgb(" + shade + "," + shade + "," + (shade + 4) + ")";
      for (const [c, r] of cells) {
        const [x, y] = at(c, r);
        ctx.fillRect(x + d, y + d, cell, cell);
      }
    }
    const random = rng(0x5170e);
    for (const [c, r] of cells) {
      const [x, y] = at(c, r);
      const sub = cell / 5;
      for (let sy = 0; sy < 5; sy++) {
        for (let sx = 0; sx < 5; sx++) {
          const v = 128 + Math.floor((random() - 0.5) * 46) + (r === 0 ? 14 : 0) - r * 3;
          ctx.fillStyle = "rgb(" + v + "," + v + "," + (v + 3) + ")";
          ctx.fillRect(x + sx * sub, y + sy * sub, sub, sub);
        }
      }
      ctx.fillStyle = "rgba(255,255,255,0.18)";
      ctx.fillRect(x, y, cell, 2);
      ctx.fillStyle = "rgba(0,0,0,0.22)";
      ctx.fillRect(x, y + cell - 2, cell, 2);
    }
  }

  const SPLASHES = [
    "Runs in your browser!",
    "No install needed!",
    "One HTML file!",
    "Open to LAN!",
    "Three versions!",
    "Java edition, no Java!",
    "Compiled with TeaVM!",
    "Also try singleplayer!",
  ];

  // Markup: keep the accessible "GAIUS CLIENT" text in #boot-brand, draw the art next to it.
  const art = doc.createElement("div");
  art.id = "gaius-boot-art";
  art.setAttribute("aria-hidden", "true");
  const logo = doc.createElement("canvas");
  logo.id = "gaius-boot-logo";
  drawLogo(logo, "GAIUS");
  const ribbon = doc.createElement("canvas");
  ribbon.id = "gaius-boot-edition";
  const ribbonText = "BROWSER EDITION";
  ribbon.width = textWidth(ribbonText) * 2 + 16;
  ribbon.height = 7 * 2 + 10;
  {
    const ctx = ribbon.getContext("2d");
    ctx.fillStyle = "#1b1b1b";
    ctx.fillRect(0, 0, ribbon.width, ribbon.height);
    ctx.fillStyle = "#3a3a3a";
    ctx.fillRect(2, 2, ribbon.width - 4, ribbon.height - 4);
    drawText(ctx, ribbonText, 8, 5, 2, "#ffffff", "#3f3f3f");
  }
  const splash = doc.createElement("canvas");
  splash.id = "gaius-boot-splash";
  {
    const text = SPLASHES[Math.floor(Math.random() * SPLASHES.length)];
    splash.width = textWidth(text) * 2 + 4;
    splash.height = 7 * 2 + 4;
    drawText(splash.getContext("2d"), text, 0, 0, 2, "#ffff55", "#3f3f15");
  }
  art.appendChild(logo);
  art.appendChild(ribbon);
  art.appendChild(splash);
  bootBrand.insertAdjacentElement("afterend", art);

  const label = doc.createElement("canvas");
  label.id = "gaius-boot-label";
  label.setAttribute("aria-hidden", "true");
  const hint = doc.createElement("canvas");
  hint.id = "gaius-boot-hint";
  hint.setAttribute("aria-hidden", "true");
  (progressText || bootBrand).insertAdjacentElement("afterend", label);
  label.insertAdjacentElement("afterend", hint);

  const root = doc.documentElement;
  root.style.setProperty("--gaius-boot-dirt", "url(" + dirtTile() + ")");
  root.dataset.gaiusBootArt = "1";

  function paintLine(canvas, text, color) {
    const scale = 2;
    const width = Math.max(1, textWidth(text) * scale + scale);
    if (canvas.width !== width) canvas.width = width;
    canvas.height = 7 * scale + scale;
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (text) drawText(ctx, text, 0, 0, scale, color, "#2a2a2a");
  }

  // Progress after the client's main() returns. The launcher holds 82% until the first usable
  // screen, so this paints two calibrated phases on top of it: starting Minecraft (82-90%) and
  // the resource reload (90-99%). Each phase eases toward its cap over the duration the previous
  // boot measured (window.localStorage, per browser), and the reload phase also follows
  // Minecraft's own reload progress (window.__gaiusReloadProgress, published by
  // dev.gaius.browser.BrowserBootProgress), whichever is further along.
  const timingKey = "gaius.bootTimings";
  function readTimings() {
    try {
      const value = JSON.parse(localStorage.getItem(timingKey) || "{}");
      return value && typeof value === "object" ? value : {};
    } catch (error) {
      return {};
    }
  }
  const learned = readTimings();
  const estimate = {
    start: Math.min(60000, Math.max(3000, Number(learned.start) || 12000)),
    reload: Math.min(90000, Math.max(3000, Number(learned.reload) || 20000)),
  };
  let phase = "";
  let phaseStartedAt = 0;
  const measured = {};
  function ease(elapsed, expected) {
    return 1 - Math.exp(-2.2 * Math.max(0, elapsed) / expected);
  }
  function enterPhase(next, now) {
    if (phase && phaseStartedAt) measured[phase] = now - phaseStartedAt;
    phase = next;
    phaseStartedAt = now;
  }
  function rememberTimings() {
    try {
      const merged = Object.assign({}, learned);
      for (const key of Object.keys(measured)) {
        if (measured[key] > 500) merged[key] = Math.round(measured[key]);
      }
      localStorage.setItem(timingKey, JSON.stringify(merged));
    } catch (error) {
      // Calibration is optional.
    }
  }

  let lastText = "";
  let lastValue = -1;
  let lastChange = performance.now();
  function frame() {
    const now = performance.now();
    const pending = window.__gaiusInitialBootPending === true;
    const visible = !bootScreen.hidden;
    const setProgress = window.__gaiusSetBootProgress;
    if (pending && visible && typeof setProgress === "function") {
      const state = window.__gaiusMinecraftState || {};
      const reloading = String(state.overlay || "").endsWith("LoadingOverlay");
      const started = typeof window.__gaiusBootTimings === "object"
        && window.__gaiusBootTimings && Number(window.__gaiusBootTimings.mainReturned) > 0;
      if (reloading && phase !== "reload") enterPhase("reload", now);
      else if (!reloading && started && !phase) enterPhase("start", now);
      if (phase === "start") {
        setProgress(82 + 8 * ease(now - phaseStartedAt, estimate.start), "Starting Minecraft...");
      } else if (phase === "reload") {
        const timed = ease(now - phaseStartedAt, estimate.reload);
        const actual = Number(window.__gaiusReloadProgress);
        // Minecraft's own figure reaches its last stage early; trust it only half-way.
        const reported = Number.isFinite(actual) ? Math.min(1, actual) * 0.5 : 0;
        setProgress(90 + 9 * Math.max(timed, reported), "Loading game resources...");
      }
    } else if (phase && !pending) {
      enterPhase("done", now);
      rememberTimings();
      phase = "";
    }
    const text = progressText ? String(progressText.textContent || "") : "";
    if (text !== lastText) {
      lastText = text;
      const match = /^(\d+)%\s*(.*)$/.exec(text);
      const value = match ? Number(match[1]) : -1;
      if (value !== lastValue) {
        lastValue = value;
        lastChange = performance.now();
      }
      paintLine(label, match ? match[2] + "  " + match[1] + "%" : text, "#e0e0e0");
    }
    const stalled = visible && pending && performance.now() - lastChange > 20000;
    const hintText = stalled ? "Still working - large worlds and slow disks can take a while" : "";
    if (hint.dataset.text !== hintText) {
      hint.dataset.text = hintText;
      paintLine(hint, hintText, "#a0a0a0");
    }
    if (!bootScreen.hidden || pending) requestAnimationFrame(frame);
    else running = false;
  }
  let running = true;
  requestAnimationFrame(frame);
  // The launcher can show the boot overlay again (startup retry); resume painting then.
  new MutationObserver(() => {
    if (!running && !bootScreen.hidden) {
      running = true;
      requestAnimationFrame(frame);
    }
  }).observe(bootScreen, {attributes: true, attributeFilter: ["hidden"]});
})();
