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

// Players are often inside an iframe from another host. Only the top frame holds the app port, so a frame hands its
// address up with postMessage and the top frame forwards it.
const isTop = window === window.top;
function sendToApp(kind) {
  if (isTop) { if (port) port.postMessage({ action: kind, page: location.href }); }
  else if (/^https?:/.test(location.href)) window.top.postMessage({ mfRelay: 1, kind, frame: location.href }, "*");
}
if (isTop) {
  window.addEventListener("message", e => {
    const d = e.data;
    if (!port || !d || d.mfRelay !== 1 || (d.kind !== "media" && d.kind !== "offer") || !/^https?:/.test(d.frame)) return;
    port.postMessage({ action: d.kind, page: location.href, frame: d.frame });
  });
}

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
  // Tabler "download" icon (MIT) on a frosted-glass disc.
  b.innerHTML = '<svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round"><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2"/>' +
    '<path d="M7 11l5 5l5 -5"/><path d="M12 4l0 12"/></svg>';
  b.setAttribute("aria-label", "Download this video");
  b.style.cssText = "position:fixed;width:46px;height:46px;padding:0;border-radius:50%;color:#fff;display:none;" +
    "align-items:center;justify-content:center;pointer-events:auto;" +
    "background:linear-gradient(145deg,rgba(255,255,255,.34),rgba(255,255,255,.08));" +
    "border:1px solid rgba(255,255,255,.45);-webkit-backdrop-filter:blur(14px) saturate(1.8);" +
    "backdrop-filter:blur(14px) saturate(1.8);" +
    "box-shadow:0 4px 14px rgba(0,0,0,.35),inset 0 1px 0 rgba(255,255,255,.6)";
  b.addEventListener("click", e => {
    e.preventDefault(); e.stopPropagation();
    sendToApp("offer");
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
  chip.style.display = "flex";
  clearTimeout(chipTimer);
  chipTimer = setTimeout(() => { if (chip) chip.style.display = "none"; }, 5000);
}

let wasPlaying = false;
setInterval(() => {
  if (document.visibilityState !== "visible") return;
  const v = mainVideo();
  if (!v) { wasPlaying = false; if (chip) chip.style.display = "none"; return; }
  // A frame repeats its report: the top frame may not have its port yet.
  if (!isTop) sendToApp("media");
  else if (reported !== location.href && port) {
    reported = location.href;
    sendToApp("media");
  }
  // A shadow-DOM player's play event does not reach document, so notice the pause-to-play change here.
  if (!v.paused && !wasPlaying) showChip();
  wasPlaying = !v.paused;
  if (chip && chip.style.display === "flex") placeChip(v);
}, 2000);
document.addEventListener("play", e => { if (e.target && e.target.tagName === "VIDEO") showChip(); }, true);
document.addEventListener("touchstart", e => {
  const v = mainVideo();
  if (!v || !e.touches.length) return;
  const r = v.getBoundingClientRect(), t = e.touches[0];
  if (t.clientX >= r.left && t.clientX <= r.right && t.clientY >= r.top && t.clientY <= r.bottom) showChip();
}, { capture: true, passive: true });

if (isTop) connect();
