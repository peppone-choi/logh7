import { isIPv4 } from "node:net";

// undefined selects loopback mode. An invalid explicit setting must never fall back to it.
export function publicPortalOrigin(): URL | null | undefined {
  const configured = process.env.LOGH7_PORTAL_ORIGIN?.trim();
  if (!configured) return undefined;
  try {
    const url = new URL(configured);
    return url.protocol === "https:" && (configured === url.origin || configured === url.origin + "/")
      ? url : null;
  } catch {
    return null;
  }
}

export function loginSettings() {
  const portal = publicPortalOrigin();
  if (portal === null) return null;
  const host = process.env.LOGH7_GAME_HOST?.trim() || (portal ? "" : "127.0.0.1");
  const port = process.env.LOGH7_GAME_PORT?.trim() ?? "";
  if (!isIPv4(host) || host === "0.0.0.0" || Number(host.split(".")[0]) >= 224 ||
      !/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) return null;
  return { host, port: Number(port) };
}
