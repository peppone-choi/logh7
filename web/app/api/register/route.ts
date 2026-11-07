import { portalRequest } from "../portal-request";

export function POST(request: Request) {
  return portalRequest(request, "/register", {
    201: ["created"],
    409: ["duplicate"],
    422: ["invalid_account", "invalid_password"],
    429: ["retry_later"],
  });
}
