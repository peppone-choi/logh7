import { portalRequest } from "../portal-request";

export function POST(request: Request) {
  return portalRequest(request, "/password-reset", {
    200: ["changed"],
    401: ["invalid_recovery"],
    422: ["invalid_password"],
    429: ["retry_later"],
  });
}
