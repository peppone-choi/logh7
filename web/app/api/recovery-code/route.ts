import { portalRequest } from "../portal-request";

export function POST(request: Request) {
  return portalRequest(request, "/recovery-code", {
    201: /^code:[A-Za-z0-9_-]{22}$/,
    401: ["invalid_credentials"],
    429: ["retry_later"],
  });
}
