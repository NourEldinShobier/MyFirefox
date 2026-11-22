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

connect();
