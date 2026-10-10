import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// In development, Next.js only serves its dev resources to localhost. Phones on the
// same Wi-Fi open the app via this computer's LAN IP, so allow those addresses too.
const lanIps = Object.values(os.networkInterfaces())
  .flat()
  .filter((a) => a && a.family === "IPv4" && !a.internal)
  .map((a) => a.address);

/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  turbopack: { root: path.dirname(fileURLToPath(import.meta.url)) },
  allowedDevOrigins: lanIps,
  // Single page app: one build worker is plenty and keeps builds working on low-RAM machines.
  experimental: { cpus: 1 },
  // Camera is only requested by this origin; nothing else may embed it.
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [{ key: "Permissions-Policy", value: "camera=(self)" }],
      },
    ];
  },
};

export default nextConfig;
