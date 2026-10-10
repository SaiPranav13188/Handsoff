// Integration test: two clients in one room exchange text and a large payload;
// a client in another room must not receive anything.
const assert = require("assert");
const { WebSocket } = require("ws");
const { server, wss } = require("./index");

function connect(port, room) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?room=${room}`);
    const inbox = [];
    const waiters = [];
    ws.on("message", (data) => {
      const msg = JSON.parse(data.toString());
      const i = waiters.findIndex((w) => w.pred(msg));
      if (i >= 0) waiters.splice(i, 1)[0].resolve(msg);
      else inbox.push(msg);
    });
    ws.next = (pred, ms = 3000) =>
      new Promise((res, rej) => {
        const i = inbox.findIndex(pred);
        if (i >= 0) return res(inbox.splice(i, 1)[0]);
        const t = setTimeout(() => rej(new Error("timeout waiting for message")), ms);
        waiters.push({ pred, resolve: (m) => (clearTimeout(t), res(m)) });
      });
    ws.inbox = inbox;
    ws.on("open", () => resolve(ws));
    ws.on("error", reject);
  });
}

(async () => {
  await new Promise((r) => server.listen(0, r));
  const { port } = server.address();

  const a = await connect(port, "testroom1");
  const welcomeA = await a.next((m) => m.type === "welcome");
  assert.strictEqual(welcomeA.peers, 0);

  const b = await connect(port, "testroom1");
  const welcomeB = await b.next((m) => m.type === "welcome");
  assert.strictEqual(welcomeB.peers, 1);
  assert.strictEqual((await a.next((m) => m.type === "presence" && m.peers === 1)).peers, 1);

  const outsider = await connect(port, "otherroom");
  await outsider.next((m) => m.type === "welcome");

  a.send(JSON.stringify({ type: "text", text: "hello from A" }));
  assert.strictEqual((await b.next((m) => m.type === "text")).text, "hello from A");

  const big = "x".repeat(4 * 1024 * 1024);
  b.send(JSON.stringify({ type: "image", data: big }));
  assert.strictEqual((await a.next((m) => m.type === "image")).data.length, big.length);

  await new Promise((r) => setTimeout(r, 200));
  assert.ok(!outsider.inbox.some((m) => m.type === "text" || m.type === "image"), "leaked across rooms");

  b.close();
  assert.strictEqual((await a.next((m) => m.type === "presence" && m.peers === 0)).peers, 0);

  a.close();
  outsider.close();
  wss.close();
  server.close();
  console.log("relay tests passed");
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
