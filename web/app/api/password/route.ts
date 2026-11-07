import { portalRequest } from "../portal-request";

export function POST(request: Request) {
  return portalRequest(request, "/password", {
    200: ["changed"],
    401: ["invalid_credentials"],
    422: ["invalid_password"],
    429: ["retry_later"],
  });
}
