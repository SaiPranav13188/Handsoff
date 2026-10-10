// Session (room) IDs and join links.

const ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no 0/o/1/l/i look-alikes
const SESSION_RE = /^[a-z0-9]{6,32}$/;

export function newSessionId(length = 8): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join("");
}

export function isValidSessionId(id: string | null | undefined): id is string {
  return !!id && SESSION_RE.test(id);
}

// The last session is remembered so a device opened without a link (e.g. from the
// Gallery's Share menu) silently rejoins the device it was paired with.
const REMEMBER_KEY = "handsoff.session";
const REMEMBER_MS = 7 * 24 * 60 * 60 * 1000;

function rememberedSession(): string | null {
  try {
    const saved = JSON.parse(localStorage.getItem(REMEMBER_KEY) || "null");
    if (saved && isValidSessionId(saved.id) && Date.now() - saved.at < REMEMBER_MS) return saved.id;
  } catch {
    /* storage blocked or corrupt */
  }
  return null;
}

export function rememberSession(id: string) {
  try {
    localStorage.setItem(REMEMBER_KEY, JSON.stringify({ id, at: Date.now() }));
  } catch {
    /* storage blocked */
  }
}

function writeSessionToUrl(id: string) {
  const url = new URL(window.location.href);
  url.searchParams.set("s", id);
  window.history.replaceState(null, "", url);
}

/**
 * Session for this page: ?s=<id> from the URL, else the remembered one, else a new
 * one. The result is written back to the URL (no reload) and remembered.
 */
export function ensureSessionInUrl(): string {
  const fromUrl = new URL(window.location.href).searchParams.get("s");
  const id = isValidSessionId(fromUrl) ? fromUrl : (rememberedSession() ?? newSessionId());
  if (id !== fromUrl) writeSessionToUrl(id);
  rememberSession(id);
  return id;
}

/** Drops the current pairing and starts a fresh session. */
export function startNewSession(): string {
  const id = newSessionId();
  writeSessionToUrl(id);
  rememberSession(id);
  return id;
}

/** True when this page was opened as localhost, which another device can't reach. */
export function isLoopbackHost(): boolean {
  return /^(localhost|127\.\d+\.\d+\.\d+|\[::1\])$/.test(window.location.hostname);
}

/**
 * The address the local dev server says phones can reach (https://<wifi-ip>:3443),
 * or null when it isn't available (e.g. when deployed).
 */
export async function fetchLanOrigin(): Promise<string | null> {
  try {
    const res = await fetch("/__lan", { cache: "no-store" });
    if (!res.ok) return null;
    const { origin } = await res.json();
    return typeof origin === "string" && /^https?:\/\//.test(origin) ? origin : null;
  } catch {
    return null;
  }
}

/** Join link for the QR code. `origin` overrides this page's origin (used for localhost). */
export function joinLink(id: string, origin: string = window.location.origin): string {
  const url = new URL(origin + window.location.pathname);
  url.searchParams.set("s", id);
  return url.toString();
}

/** Extracts a session ID from a scanned QR payload (a full join link or a bare ID). */
export function sessionFromScan(text: string): string | null {
  const trimmed = text.trim();
  if (isValidSessionId(trimmed)) return trimmed;
  try {
    const id = new URL(trimmed).searchParams.get("s");
    return isValidSessionId(id) ? id : null;
  } catch {
    return null;
  }
}

export function relayUrl(room: string): string {
  const configured = process.env.NEXT_PUBLIC_RELAY_URL;
  // Without a configured relay, it lives on the same server as the page (scripts/run.js).
  const base = configured
    ? configured.replace(/\/+$/, "")
    : `${window.location.protocol === "https:" ? "wss" : "ws"}://${window.location.host}/ws`;
  return `${base}?room=${encodeURIComponent(room)}`;
}

export function deviceLabel(): string {
  const ua = navigator.userAgent;
  if (/iPhone/.test(ua)) return "iPhone";
  if (/iPad/.test(ua)) return "iPad";
  if (/Android/.test(ua)) return /Mobile/.test(ua) ? "Android phone" : "Android tablet";
  if (/Macintosh/.test(ua)) return "Mac";
  if (/Windows/.test(ua)) return "Windows PC";
  if (/Linux/.test(ua)) return "Linux PC";
  return "Device";
}
