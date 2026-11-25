/* Remembers the video, audio and stream playlist (m3u8 / mpd) requests of every tab, whatever frame or site makes them,
 * so the app can download the real stream the way a download manager does. Nothing leaves the device: the content
 * script of the tab asks for this list and passes it to the app over its native port. */
const found = new Map(); // tabId -> Map(url -> stream)
const SKIP_HOST = /(googlevideo|youtube|youtu\.be|ytimg|instagram|fbcdn|soundcloud|sndcdn)\./i; // yt-dlp reads these pages itself
const NOT_MEDIA = /\.(ts|m4s|cmfv|cmfa|vtt|srt|jpe?g|png|gif|webp|svg|ico|js|css|html?|json|woff2?)$/;
const VIDEO_EXT = /\.(mp4|m4v|webm|mkv|mov|flv|ogv|avi|3gp)$/;
const AUDIO_EXT = /\.(mp3|m4a|aac|ogg|oga|opus|wav|flac)$/;

function classify(path, ct) {
  if (path.endsWith(".m3u8") || ct.includes("mpegurl")) return "hls";
  if (path.endsWith(".mpd") || ct.includes("dash+xml")) return "dash";
  if (NOT_MEDIA.test(path)) return null;
  if (ct.startsWith("video/") && !ct.includes("mp2t")) return "video";
  if (VIDEO_EXT.test(path)) return "video";
  if (ct.startsWith("audio/") && !ct.includes("mp2t")) return "audio";
  if (AUDIO_EXT.test(path)) return "audio";
  return null;
}

function headerMap(list) {
  const m = {};
  for (const h of list || []) m[h.name.toLowerCase()] = h.value || "";
  return m;
}

// A 206 answer carries only a slice; its Content-Range ends with the whole file's size.
function totalSize(h) {
  const m = /\/(\d+)$/.exec(h["content-range"] || "");
  if (m) return Number(m[1]);
  const n = Number(h["content-length"]);
  return n > 0 ? n : 0;
}

browser.webRequest.onHeadersReceived.addListener(d => {
  if (d.tabId < 0 || (d.statusCode !== 200 && d.statusCode !== 206)) return;
  let u;
  try { u = new URL(d.url); } catch (e) { return; }
  if (!/^https?:$/.test(u.protocol) || SKIP_HOST.test(u.hostname)) return;
  const h = headerMap(d.responseHeaders);
  const kind = classify(u.pathname.toLowerCase(), (h["content-type"] || "").toLowerCase());
  if (!kind) return;
  const size = totalSize(h);
  // Small files are ads, sound effects and segments; playlists are small by nature.
  if (kind === "video" && size && size < 1000000) return;
  if (kind === "audio" && size && size < 200000) return;
  let tab = found.get(d.tabId);
  if (!tab) { tab = new Map(); found.set(d.tabId, tab); }
  const key = d.url.split("#")[0];
  const old = tab.get(key);
  tab.delete(key); // re-insert so the newest request is last
  tab.set(key, { url: key, kind, size: size || (old ? old.size : 0), ref: d.documentUrl || d.originUrl || "", ua: navigator.userAgent });
  if (tab.size > 40) tab.delete(tab.keys().next().value);
}, { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object"] }, ["responseHeaders"]);

// A new page replaces what the tab had found.
browser.webRequest.onBeforeRequest.addListener(d => {
  if (d.tabId >= 0) found.delete(d.tabId);
}, { urls: ["<all_urls>"], types: ["main_frame"] });

browser.tabs.onRemoved.addListener(id => found.delete(id));

// Playlists first (their segments are not worth listing); otherwise the biggest files first.
function list(tabId) {
  const all = Array.from((found.get(tabId) || new Map()).values());
  const manifests = all.filter(s => s.kind === "hls" || s.kind === "dash").reverse();
  if (manifests.length) return manifests.slice(0, 8);
  const files = all.filter(s => s.kind === "video" ? (!s.size || s.size >= 3000000) : true);
  const rank = s => (s.kind === "video" ? 1e15 : 0) + s.size;
  return files.sort((a, b) => rank(b) - rank(a)).slice(0, 8);
}

browser.runtime.onMessage.addListener((msg, sender) => {
  if (msg && msg.q === "streams" && sender.tab) return Promise.resolve(list(sender.tab.id));
});
