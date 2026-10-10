"use client";

// Webcam + MediaPipe Hands, entirely client-side. MediaPipe is loaded from the
// CDN; the video never leaves the device.

import { useEffect, useRef, useState, type RefObject } from "react";
import type { Landmark } from "./gesture";

const MP_VERSION = "0.4.1675469240";
const MP_BASE = `https://cdn.jsdelivr.net/npm/@mediapipe/hands@${MP_VERSION}`;

export type CameraStatus = "idle" | "starting" | "ready" | "denied" | "unsupported" | "error";

type HandsResults = { multiHandLandmarks?: Landmark[][] };
type HandsInstance = {
  setOptions(o: Record<string, unknown>): void;
  onResults(cb: (r: HandsResults) => void): void;
  initialize(): Promise<void>;
  send(input: { image: HTMLVideoElement }): Promise<void>;
  close(): Promise<void>;
};
declare global {
  interface Window {
    Hands?: new (cfg: { locateFile: (f: string) => string }) => HandsInstance;
  }
}

let scriptPromise: Promise<void> | null = null;
function loadMediaPipe(): Promise<void> {
  if (window.Hands) return Promise.resolve();
  if (!scriptPromise) {
    scriptPromise = new Promise((resolve, reject) => {
      const s = document.createElement("script");
      s.src = `${MP_BASE}/hands.js`;
      s.crossOrigin = "anonymous";
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => {
        scriptPromise = null;
        reject(new Error("Could not load MediaPipe Hands"));
      };
      document.head.appendChild(s);
    });
  }
  return scriptPromise;
}

export type HandsFrame = (landmarks: Landmark[] | null, aspect: number) => void;

export function useHands(videoRef: RefObject<HTMLVideoElement | null>, enabled: boolean, onFrame: HandsFrame) {
  const [status, setStatus] = useState<CameraStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const onFrameRef = useRef(onFrame);
  onFrameRef.current = onFrame;

  useEffect(() => {
    if (!enabled) return;
    const video = videoRef.current;
    if (!video) return;
    if (!navigator.mediaDevices?.getUserMedia) {
      setStatus("unsupported");
      setError(
        window.isSecureContext
          ? "This browser does not support camera access."
          : "Camera access needs HTTPS (or localhost).",
      );
      return;
    }

    let stopped = false;
    let stream: MediaStream | null = null;
    let hands: HandsInstance | null = null;
    let raf = 0;

    (async () => {
      setStatus("starting");
      try {
        stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: "user", width: { ideal: 640 }, height: { ideal: 480 } },
          audio: false,
        });
      } catch (e) {
        if (stopped) return;
        const name = (e as DOMException)?.name;
        setStatus(name === "NotAllowedError" || name === "SecurityError" ? "denied" : "error");
        setError(
          name === "NotAllowedError"
            ? "Camera permission was blocked. Allow it in the address bar and reload."
            : `Could not start the camera (${name || "unknown error"}).`,
        );
        return;
      }
      if (stopped) {
        stream.getTracks().forEach((t) => t.stop());
        return;
      }
      video.srcObject = stream;
      video.muted = true;
      video.playsInline = true;
      await video.play().catch(() => {});

      try {
        await loadMediaPipe();
        if (stopped || !window.Hands) return;
        hands = new window.Hands({ locateFile: (f) => `${MP_BASE}/${f}` });
        const mobile = /Android|iPhone|iPad/.test(navigator.userAgent);
        hands.setOptions({
          maxNumHands: 1,
          modelComplexity: mobile ? 0 : 1,
          minDetectionConfidence: 0.6,
          minTrackingConfidence: 0.5,
        });
        hands.onResults((r) => {
          const lm = r.multiHandLandmarks?.[0] ?? null;
          const aspect = video.videoWidth && video.videoHeight ? video.videoWidth / video.videoHeight : 4 / 3;
          onFrameRef.current(lm, aspect);
        });
        await hands.initialize();
      } catch (e) {
        if (stopped) return;
        setStatus("error");
        setError((e as Error).message || "Hand tracking failed to load.");
        return;
      }
      if (stopped) return;
      setStatus("ready");

      let busy = false;
      const tick = async () => {
        if (stopped) return;
        if (!busy && hands && video.readyState >= 2 && !document.hidden) {
          busy = true;
          try {
            await hands.send({ image: video });
          } catch {
            /* dropped frame */
          }
          busy = false;
        }
        raf = requestAnimationFrame(tick);
      };
      raf = requestAnimationFrame(tick);
    })();

    return () => {
      stopped = true;
      cancelAnimationFrame(raf);
      stream?.getTracks().forEach((t) => t.stop());
      hands?.close().catch(() => {});
      video.srcObject = null;
    };
  }, [enabled, videoRef]);

  return { status, error };
}
