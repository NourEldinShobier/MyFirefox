/* Lists the videos or tracks currently loaded on a playlist page and sends them to the app on request.
 * Nothing leaves the device: the list goes to the browser app over the extension's native port. */
const PORT_NAME = "mozacPlaylist";
let port = null;
let retryMs = 2000;

function connect() {
  try {
    port = browser.runtime.connectNative(PORT_NAME);
    port.onMessage.addListener(msg => {
      if (msg && msg.action === "collect") {
        port.postMessage({ action: "items", page: location.href, items: collect() });
      } else if (msg && msg.action === "collectAll") {
        scrollAll();
      }
    });
    port.onDisconnect.addListener(() => {
      port = null;
      setTimeout(connect, retryMs);
      retryMs = Math.min(retryMs * 2, 20000); // the app registers the page a moment after it loads
    });
  } catch (e) {
    setTimeout(connect, retryMs);
  }
}

function clean(text) {
  return (text || "").replace(/\s+/g, " ").trim();
}

function titleFor(anchor) {
  // The visible heading is the clean title; the link label also carries channel, views and duration.
  const box = anchor.closest("ytm-playlist-video-renderer, ytm-compact-video-renderer, ytm-video-with-context-renderer, ytmusic-responsive-list-item-renderer, ytd-playlist-video-renderer, li, article");
  const head = box && box.querySelector("h3, .media-item-headline, [role=heading], .title, yt-formatted-string");
  const fromHead = clean(head && head.textContent);
  if (fromHead.length > 2) return fromHead;
  const label = clean(anchor.getAttribute("title") || anchor.getAttribute("aria-label") || anchor.textContent);
  return label.replace(/\s+by\s+.+?\s+[\d,.]+[KMB]?\s+views.*$/i, "");
}

function collect() {
  const found = new Map();
  const host = location.hostname;
  if (host.endsWith("soundcloud.com")) {
    document.querySelectorAll("a.trackItem__trackTitle, a.soundTitle__title, .trackList__item a[href]").forEach(a => {
      const u = new URL(a.href, location.href);
      if (u.pathname.split("/").filter(Boolean).length < 2 || u.pathname.includes("/sets/")) return;
      const url = u.origin + u.pathname;
      const title = titleFor(a);
      if (!found.has(url) || (title.length > found.get(url).length)) found.set(url, title);
    });
  } else {
    const origin = host.startsWith("music.") ? "https://music.youtube.com" : "https://www.youtube.com";
    document.querySelectorAll("a[href*='/watch?v='], a[href*='/shorts/']").forEach(a => {
      const u = new URL(a.href, location.href);
      const v = u.searchParams.get("v") || (u.pathname.startsWith("/shorts/") ? u.pathname.split("/")[2] : null);
      if (!v) return;
      const url = origin + "/watch?v=" + v;
      const title = titleFor(a);
      if (!found.has(url) || (title.length > found.get(url).length)) found.set(url, title);
    });
  }
  return Array.from(found, ([url, title]) => ({ url, title: title || url }));
}

// Scrolls like a person would so the page loads the rest of the list itself (works for private playlists,
// no extra request outside the page). Stops after 4 rounds with nothing new, or after 150 rounds.
let scrolling = false;
async function scrollAll() {
  if (scrolling) return;
  scrolling = true;
  const all = new Map();
  let last = -1, still = 0;
  for (let i = 0; i < 150 && still < 4; i++) {
    window.scrollTo(0, document.documentElement.scrollHeight);
    await new Promise(r => setTimeout(r, 1500));
    collect().forEach(e => { if (!all.has(e.url) || e.title.length > all.get(e.url).title.length) all.set(e.url, e); });
    if (port) port.postMessage({ action: "progress", count: all.size });
    if (all.size === last) still++; else { still = 0; last = all.size; }
  }
  scrolling = false;
  window.scrollTo(0, 0);
  if (port) port.postMessage({ action: "items", page: location.href, items: Array.from(all.values()) });
}

// IDM-style: when a real video (not a short preview) is on the page, tell the app and show a small download chip on it.
// Only the chip receives taps; the page and the player keep working as usual.
let chip = null, chipTimer = 0, reported = "";

// The page's main player: the largest visible video, preferring the one that is playing. Small previews, thumbnails
// that autoplay, hidden and off-screen videos are ignored.
// Players such as archive.org's live inside shadow roots, which plain querySelectorAll does not enter.
function allVideos(root, out) {
  root.querySelectorAll("video").forEach(v => out.push(v));
  root.querySelectorAll("*").forEach(el => { if (el.shadowRoot) allVideos(el.shadowRoot, out); });
  return out;
}

function mainVideo() {
  let best = null, bestScore = 0;
  for (const v of allVideos(document, [])) {
    const r = v.getBoundingClientRect();
    const w = Math.min(r.right, window.innerWidth) - Math.max(r.left, 0);
    const h = Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0);
    if (w < 200 || h < 110 || v.readyState < 1) continue;
    if (!(v.duration > 15) && v.duration !== Infinity) continue; // shorter clips are previews; Infinity is a live stream
    const score = w * h * (v.paused ? 1 : 3);
    if (score > bestScore) { best = v; bestScore = score; }
  }
  return best;
}

function ensureChip() {
  if (chip) return chip;
  const host = document.createElement("div");
  host.style.cssText = "position:fixed;z-index:2147483647;width:0;height:0;pointer-events:none";
  const root = host.attachShadow({ mode: "closed" });
  const b = document.createElement("button");
  b.textContent = "⬇";
  b.setAttribute("aria-label", "Download this video");
  b.style.cssText = "position:fixed;width:44px;height:44px;border-radius:22px;border:0;font-size:22px;color:#fff;" +
    "background:rgba(20,20,24,.82);pointer-events:auto;display:none;box-shadow:0 2px 8px rgba(0,0,0,.5)";
  b.addEventListener("click", e => {
    e.preventDefault(); e.stopPropagation();
    if (port) port.postMessage({ action: "offer", page: location.href });
  }, true);
  root.appendChild(b);
  document.documentElement.appendChild(host);
  chip = b;
  return b;
}

function placeChip(v) {
  const r = v.getBoundingClientRect();
  const b = ensureChip();
  b.style.left = Math.max(8, Math.min(window.innerWidth - 52, r.right - 56)) + "px";
  b.style.top = Math.max(8, Math.min(window.innerHeight - 52, r.top + 12)) + "px";
}

function showChip() {
  const v = mainVideo();
  if (!v) return;
  placeChip(v);
  chip.style.display = "block";
  clearTimeout(chipTimer);
  chipTimer = setTimeout(() => { if (chip) chip.style.display = "none"; }, 5000);
}

let wasPlaying = false, ticks = 0;
setInterval(() => {
  if (document.visibilityState !== "visible") return;
  const v = mainVideo();
  // [DEBUG-vid1] every 20 s, what the scan sees (remove once the chip is verified)
  if (port && ticks++ % 10 === 0) port.postMessage({ action: "log", msg: `[DEBUG-vid1] ${location.hostname} videos=${allVideos(document, []).length} main=${!!v}` });
  if (!v) { wasPlaying = false; if (chip) chip.style.display = "none"; return; }
  if (reported !== location.href && port) {
    reported = location.href;
    port.postMessage({ action: "media", page: location.href });
  }
  // A shadow-DOM player's play event does not reach document, so notice the pause-to-play change here.
  if (!v.paused && !wasPlaying) showChip();
  wasPlaying = !v.paused;
  if (chip && chip.style.display === "block") placeChip(v);
}, 2000);
document.addEventListener("play", e => { if (e.target && e.target.tagName === "VIDEO") showChip(); }, true);
document.addEventListener("touchstart", e => {
  const v = mainVideo();
  if (!v || !e.touches.length) return;
  const r = v.getBoundingClientRect(), t = e.touches[0];
  if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) showChip();
}, { capture: true, passive: true });

connect();
