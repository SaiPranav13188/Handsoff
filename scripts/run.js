// Local server: the Next.js app and the WebSocket relay on the same ports, so
// phones on the same Wi-Fi can use it.
//   http://localhost:3000        → for this computer
//   https://<wifi-ip>:3443       → for phones (camera needs HTTPS; self-signed cert)
// The QR code shown on this computer points phones at the HTTPS address.
//
//   node scripts/run.js          → development (next dev)
//   node scripts/run.js prod     → production (run `npm run build` first)

const http = require("http");
const https = require("https");
const fs = require("fs");
const os = require("os");
const path = require("path");

const root = path.join(__dirname, "..");
const webDir = path.join(root, "web");
const certDir = path.join(root, ".cert");
const dev = process.argv[2] !== "prod";
process.env.NODE_ENV = dev ? "development" : "production";
const HTTP_PORT = Number(process.env.WEB_PORT) || 3000;
const HTTPS_PORT = Number(process.env.HTTPS_PORT) || 3443;

const { handleUpgrade } = require(path.join(root, "server", "index.js"));
const next = require(require.resolve("next", { paths: [webDir] }));

/** Wi-Fi / Ethernet IPv4 addresses, real adapters first (skips VirtualBox, WSL, VPN…). */
function lanAddresses() {
  const virtual = /virtual|vbox|vmware|vethernet|wsl|hyper-v|loopback|docker|tailscale|zerotier|bluetooth/i;
  const list = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family !== "IPv4" || a.internal) continue;
      let score = 0;
      if (/wi-?fi|wlan|wireless/i.test(name)) score += 2;
      if (virtual.test(name)) score -= 3;
      if (a.address.startsWith("192.168.56.")) score -= 3; // VirtualBox host-only default
      if (a.address.startsWith("169.254.")) score -= 5; // no DHCP lease
      list.push({ name, address: a.address, score });
    }
  }
  return list.sort((x, y) => y.score - x.score);
}

/** Self-signed certificate covering localhost and every LAN IP, cached in .cert/. */
function certificate(ips) {
  const keyFile = path.join(certDir, "key.pem");
  const certFile = path.join(certDir, "cert.pem");
  const metaFile = path.join(certDir, "ips.json");
  const want = JSON.stringify([...ips].sort());
  try {
    if (fs.readFileSync(metaFile, "utf8") === want) {
      return { key: fs.readFileSync(keyFile), cert: fs.readFileSync(certFile) };
    }
  } catch {
    /* no cached cert yet */
  }
  const selfsigned = require("selfsigned");
  const altNames = [{ type: 2, value: "localhost" }, { type: 7, ip: "127.0.0.1" }, ...ips.map((ip) => ({ type: 7, ip }))];
  const pems = selfsigned.generate([{ name: "commonName", value: "Handsoff local" }], {
    days: 365,
    keySize: 2048,
    algorithm: "sha256",
    extensions: [{ name: "subjectAltName", altNames }],
  });
  fs.mkdirSync(certDir, { recursive: true });
  fs.writeFileSync(keyFile, pems.private);
  fs.writeFileSync(certFile, pems.cert);
  fs.writeFileSync(metaFile, want);
  return { key: pems.private, cert: pems.cert };
}

(async () => {
  const lan = process.env.PORT ? [] : lanAddresses();
  const lanOrigin = lan.length ? `https://${lan[0].address}:${HTTPS_PORT}` : null;

  const app = next({ dev, dir: webDir });
  const handle = app.getRequestHandler();
  await app.prepare();
  const nextUpgrade = app.getUpgradeHandler ? app.getUpgradeHandler() : null;

  const onRequest = (req, res) => {
    if (req.url === "/health") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end('{"ok":true}');
      return;
    }
    // Lets the page (opened on localhost) put the phone-reachable address in its QR code.
    if (req.url === "/__lan") {
      res.writeHead(200, { "content-type": "application/json", "cache-control": "no-store" });
      res.end(JSON.stringify({ origin: lanOrigin }));
      return;
    }
    handle(req, res);
  };
  const onUpgrade = (req, socket, head) => {
    if (handleUpgrade(req, socket, head)) return;
    if (nextUpgrade) nextUpgrade(req, socket, head);
    else socket.destroy();
  };

  const portBusy = (port) => (err) => {
    if (err.code === "EADDRINUSE") {
      console.error(`
  Port ${port} is already in use — Handsoff (or another app) is already running.
  Close that terminal or press Ctrl+C there, then try again.
`);
      process.exit(1);
    }
    throw err;
  };

  // Hosted (Render etc.): the platform gives us PORT and terminates HTTPS with a
  // trusted certificate, so serve plain HTTP on that one port.
  if (process.env.PORT) {
    const port = Number(process.env.PORT);
    const server = http.createServer(onRequest);
    server.on("upgrade", onUpgrade);
    server.listen(port, "0.0.0.0", () => console.log(`Handsoff listening on :${port}`));
    return;
  }

  const httpServer = http.createServer(onRequest);
  httpServer.on("error", portBusy(HTTP_PORT));
  httpServer.on("upgrade", onUpgrade);
  httpServer.listen(HTTP_PORT, "0.0.0.0");

  const httpsServer = https.createServer(certificate(lan.map((l) => l.address)), onRequest);
  httpsServer.on("error", portBusy(HTTPS_PORT));
  httpsServer.on("upgrade", onUpgrade);
  httpsServer.listen(HTTPS_PORT, "0.0.0.0");

  console.log(`
  Handsoff is running (${dev ? "development" : "production"})

    On this computer:  http://localhost:${HTTP_PORT}
    On your phone:     ${lanOrigin ?? "(no Wi-Fi address found)"}
${lan.slice(1).map((l) => `                       https://${l.address}:${HTTPS_PORT}   (${l.name})`).join("\n")}
  Phone and computer must be on the same Wi-Fi. The phone will warn that the
  connection is not private (self-signed certificate): tap Advanced → Proceed.
  Press Ctrl+C to stop.
`);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
