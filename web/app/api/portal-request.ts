const formType = "application/x-www-form-urlencoded";
const limit = 512;

function reply(body: string, status: number) {
  const headers = new Headers({ "Cache-Control": "no-store" });
  if (status === 429) headers.set("Retry-After", "60");
  return new Response(body, { status, headers });
}

export async function portalRequest(
  request: Request,
  path: "/register" | "/password",
  results: Record<number, readonly string[]>,
) {
  const host = request.headers.get("host") ?? "";
  if (!/^(127\.0\.0\.1|localhost):\d{1,5}$/.test(host) ||
      request.headers.get("origin") !== `http://${host}`) {
    return reply("forbidden", 403);
  }
  if (request.headers.get("content-type")?.split(";")[0] !== formType) {
    return reply("content_type", 415);
  }
  const port = Number(process.env.LOGH7_REGISTRATION_PORT);
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !request.body) {
    return reply("unavailable", 503);
  }

  try {
    const reader = request.body.getReader();
    const body = new Uint8Array(limit);
    let length = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (length + value.byteLength > limit) {
        void reader.cancel().catch(() => {});
        return reply("too_large", 413);
      }
      body.set(value, length);
      length += value.byteLength;
    }
    const response = await fetch(`http://127.0.0.1:${port}${path}`, {
      method: "POST",
      headers: { "Content-Type": formType, "X-Logh7-Portal": "1" },
      body: body.slice(0, length),
      redirect: "error",
      signal: AbortSignal.timeout(5000),
    });
    const result = await response.text();
    if (results[response.status]?.includes(result)) return reply(result, response.status);
  } catch {
    return reply("unavailable", 503);
  }
  return reply("unavailable", 503);
}
