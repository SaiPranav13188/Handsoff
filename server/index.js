// Gesture Transfer — stateless WebSocket room relay.
//
// Clients connect to  ws(s)://<host>/ws?room=<sessionId>
// Every message a client sends is forwarded verbatim to the other clients in
// the same room. Nothing is stored: rooms exist only while sockets are open.
// The server itself only ever emits small JSON control messages:
//   { type: "welcome",  id, peers }   – sent to a client right after it joins
//   { type: "presence", peers }       – sent to everyone when membership changes
//   { type: "error",    reason }      – sent before closing a rejected client

const http = require("http");
const crypto = require("crypto");
const { WebSocketServer, WebSocket } = require("ws");

const PORT = Number(process.env.PORT) || 8080;
const MAX_PAYLOAD = Number(process.env.MAX_PAYLOAD_BYTES) || 12 * 1024 * 1024;
const MAX_PEERS_PER_ROOM = Number(process.env.MAX_PEERS_PER_ROOM) || 8;
const HEARTBEAT_MS = 25_000;
const ROOM_ID_RE = /^[a-zA-Z0-9_-]{4,64}$/;
const ALLOWED_ORIGINS = (process.env.ALLOWED_ORIGINS || "")
  .split(",")
  .map((s) => s.trim())
  .filter(Boolean);

/** @type {Map<string, Set<WebSocket>>} */
const rooms = new Map();

const server = http.createServer((req, res) => {
  if (req.url === "/" || req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size }));
    return;
  }
  res.writeHead(404).end();
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD });

/**
 * Handles a WebSocket upgrade for /ws?room=<id>. Returns false (and leaves the
 * socket alone) for any other path, so a host server can route it elsewhere.
 */
function handleUpgrade(req, socket, head) {
  const url = new URL(req.url, "http://localhost");
  if (url.pathname !== "/ws") return false;
  if (ALLOWED_ORIGINS.length && !ALLOWED_ORIGINS.includes(req.headers.origin)) {
    socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
    socket.destroy();
    return true;
  }
  const room = url.searchParams.get("room") || "";
  if (!ROOM_ID_RE.test(room)) {
    socket.write("HTTP/1.1 400 Bad Request\r\n\r\n");
    socket.destroy();
    return true;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, room));
  return true;
}

server.on("upgrade", (req, socket, head) => {
  if (!handleUpgrade(req, socket, head)) socket.destroy();
});

function send(ws, obj) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function broadcastPresence(room) {
  const members = rooms.get(room);
  if (!members) return;
  for (const ws of members) send(ws, { type: "presence", peers: members.size - 1 });
}

wss.on("connection", (ws, room) => {
  let members = rooms.get(room);
  if (!members) {
    members = new Set();
    rooms.set(room, members);
  }
  if (members.size >= MAX_PEERS_PER_ROOM) {
    send(ws, { type: "error", reason: "room-full" });
    ws.close(4001, "room full");
    return;
  }

  ws.id = crypto.randomBytes(4).toString("hex");
  ws.isAlive = true;
  members.add(ws);
  send(ws, { type: "welcome", id: ws.id, peers: members.size - 1 });
  broadcastPresence(room);

  ws.on("pong", () => {
    ws.isAlive = true;
  });

  ws.on("message", (data, isBinary) => {
    for (const peer of members) {
      if (peer !== ws && peer.readyState === WebSocket.OPEN) {
        peer.send(data, { binary: isBinary });
      }
    }
  });

  ws.on("close", () => {
    members.delete(ws);
    if (members.size === 0) rooms.delete(room);
    else broadcastPresence(room);
  });

  ws.on("error", () => ws.terminate());
});

// Drop sockets that stopped answering pings (phones going to sleep, etc.).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_MS);

wss.on("close", () => clearInterval(heartbeat));

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`Gesture Transfer relay listening on :${PORT}`);
  });
}

module.exports = { server, wss, rooms, handleUpgrade };
