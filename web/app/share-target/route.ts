// Reached only when the service worker isn't active yet (first launch). The relay
// is stateless, so the photo isn't kept; the page asks the user to share again.
export function POST(req: Request) {
  return Response.redirect(new URL("/?shared=0", req.url), 303);
}

export function GET(req: Request) {
  return Response.redirect(new URL("/", req.url), 303);
}
