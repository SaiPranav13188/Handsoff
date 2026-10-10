"use client";

// Scans QR codes from the already-running camera <video> (no second camera stream).

import { useEffect, useRef, type RefObject } from "react";
import jsQR from "jsqr";

export function useQrScanner(
  videoRef: RefObject<HTMLVideoElement | null>,
  active: boolean,
  onResult: (text: string) => boolean | void,
) {
  const onResultRef = useRef(onResult);
  onResultRef.current = onResult;

  useEffect(() => {
    if (!active) return;
    const canvas = document.createElement("canvas");
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    if (!ctx) return;

    const timer = setInterval(() => {
      const video = videoRef.current;
      if (!video || video.readyState < 2 || !video.videoWidth) return;
      const scale = Math.min(1, 640 / video.videoWidth);
      canvas.width = Math.round(video.videoWidth * scale);
      canvas.height = Math.round(video.videoHeight * scale);
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const { data, width, height } = ctx.getImageData(0, 0, canvas.width, canvas.height);
      // The raw frame is un-mirrored (only the preview is flipped), so codes read normally.
      const code = jsQR(data, width, height, { inversionAttempts: "attemptBoth" });
      if (code?.data) onResultRef.current(code.data);
    }, 200);

    return () => clearInterval(timer);
  }, [active, videoRef]);
}
