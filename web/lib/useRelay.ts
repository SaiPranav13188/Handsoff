"use client";

// Silent, self-healing WebSocket link to the room relay. It opens as soon as a
// room is known and reconnects with backoff — there is no visible "connect" step.

import { useCallback, useEffect, useRef, useState } from "react";
import { relayUrl } from "./session";

export type LinkStatus = "connecting" | "open" | "offline";
export type RelayMessage = { type: string; [key: string]: unknown };

export function useRelay(room: string | null, onMessage: (msg: RelayMessage) => void) {
  const [status, setStatus] = useState<LinkStatus>("connecting");
  const [peers, setPeers] = useState(0);
  const wsRef = useRef<WebSocket | null>(null);
  const onMessageRef = useRef(onMessage);
  onMessageRef.current = onMessage;

  useEffect(() => {
    if (!room) return;
    let disposed = false;
    let retry = 0;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const connect = () => {
      if (disposed) return;
      setStatus((s) => (s === "open" ? "connecting" : s));
      let ws: WebSocket;
      try {
        ws = new WebSocket(relayUrl(room));
      } catch {
        scheduleRetry();
        return;
      }
      wsRef.current = ws;

      ws.onopen = () => {
        retry = 0;
        setStatus("open");
      };
      ws.onmessage = (ev) => {
        if (typeof ev.data !== "string") return;
        let msg: RelayMessage;
        try {
          msg = JSON.parse(ev.data);
        } catch {
          return;
        }
        if (!msg || typeof msg.type !== "string") return;
        if ((msg.type === "welcome" || msg.type === "presence") && typeof msg.peers === "number") {
          setPeers(msg.peers);
        }
        onMessageRef.current(msg);
      };
      ws.onclose = () => {
        if (wsRef.current === ws) wsRef.current = null;
        setPeers(0);
        scheduleRetry();
      };
      ws.onerror = () => ws.close();
    };

    const scheduleRetry = () => {
      if (disposed) return;
      retry++;
      // Only surface "offline" after a few failed attempts; brief blips stay invisible.
      setStatus(retry > 3 ? "offline" : "connecting");
      const delay = Math.min(10_000, 400 * 2 ** Math.min(retry, 5)) + Math.random() * 300;
      timer = setTimeout(connect, delay);
    };

    const onVisible = () => {
      if (document.visibilityState === "visible" && !wsRef.current) {
        clearTimeout(timer);
        retry = 0;
        connect();
      }
    };

    connect();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      disposed = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
      wsRef.current?.close();
      wsRef.current = null;
    };
  }, [room]);

  const send = useCallback((msg: RelayMessage): boolean => {
    const ws = wsRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    ws.send(JSON.stringify(msg));
    return true;
  }, []);

  const buffered = useCallback(() => wsRef.current?.bufferedAmount ?? 0, []);

  return { status, peers, send, buffered };
}
