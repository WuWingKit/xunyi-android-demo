"""Dependency-free HTTP entry point for the XunYi demo service."""
import hmac
import json
import os
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from amap import AmapClient, AmapError
from service import InputError, Service


class Handler(BaseHTTPRequestHandler):
    service: Service
    token: str

    def log_message(self, fmt, *args):
        # Avoid logging bodies, transcript text, GPS coordinates or credentials.
        print("http", self.command, self.path.split("?")[0], flush=True)

    def _send(self, status, data):
        body = json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        if not hmac.compare_digest(header, "Bearer " + self.token):
            self._send(401, {"error": "authorization required"})
            return False
        return True

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError as exc:
            raise InputError("invalid content length") from exc
        if length < 1 or length > 32768:
            raise InputError("JSON body must be 1..32768 bytes")
        try:
            value = json.loads(self.rfile.read(length))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise InputError("invalid JSON body") from exc
        if not isinstance(value, dict):
            raise InputError("JSON body must be an object")
        return value

    def _dispatch(self):
        path = self.path.split("?", 1)[0].rstrip("/") or "/"
        if path == "/health" and self.command == "GET":
            return 200, {"status": "ok", "service": "xunyi-demo"}
        if not self._authorized():
            return None
        if path == "/v1/recordings" and self.command == "POST":
            return 201, self.service.create_recording(self._body())
        match = re.fullmatch(r"/v1/recordings/([a-f0-9]{32})", path)
        if match and self.command == "GET":
            return 200, self.service.recording(match.group(1))
        if match and self.command == "DELETE":
            return 200, self.service.delete_recording(match.group(1))
        match = re.fullmatch(r"/v1/recordings/([a-f0-9]{32})/places/search", path)
        if match and self.command == "POST":
            body = self._body()
            return 200, self.service.search_places(match.group(1), body.get("query", ""), body.get("region", ""))
        match = re.fullmatch(r"/v1/recordings/([a-f0-9]{32})/places/gps", path)
        if match and self.command == "POST":
            body = self._body()
            return 200, self.service.bind_gps(match.group(1), body.get("gps"), body.get("placeMention"))
        match = re.fullmatch(r"/v1/recordings/([a-f0-9]{32})/places/confirm", path)
        if match and self.command == "POST":
            return 200, self.service.confirm_place(match.group(1), str(self._body().get("candidateId", "")))
        if path == "/v1/conversations" and self.command == "POST":
            return 201, self.service.create_conversation(self._body())
        match = re.fullmatch(r"/v1/conversations/([a-f0-9]{32})", path)
        if match and self.command == "DELETE":
            return 200, self.service.delete_conversation(match.group(1))
        match = re.fullmatch(r"/v1/conversations/([a-f0-9]{32})/turns", path)
        if match and self.command == "POST":
            return 201, self.service.add_turn(match.group(1), self._body())
        match = re.fullmatch(r"/v1/conversations/([a-f0-9]{32})/prompts/next", path)
        if match and self.command == "POST":
            return 200, self.service.next_prompt(match.group(1))
        return 404, {"error": "not found"}

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_DELETE(self):
        self._handle()

    def _handle(self):
        try:
            result = self._dispatch()
            if result:
                self._send(*result)
        except InputError as exc:
            self._send(400, {"error": str(exc)})
        except KeyError as exc:
            self._send(404, {"error": str(exc).strip("'")})
        except AmapError as exc:
            self._send(502, {"error": str(exc), "nextAction": "retry search; recording remains saved"})
        except Exception:
            self._send(500, {"error": "internal error"})
            raise


def main():
    token = os.environ.get("XUNYI_API_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("XUNYI_API_TOKEN must have at least 32 characters")
    Handler.token = token
    Handler.service = Service(os.environ.get("XUNYI_DB", "/var/lib/xunyi/xunyi.db"), AmapClient(os.environ.get("AMAP_KEY", "")))
    host = os.environ.get("XUNYI_HOST", "127.0.0.1")
    port = int(os.environ.get("XUNYI_PORT", "8765"))
    server = ThreadingHTTPServer((host, port), Handler)
    print(f"XunYi demo listening on {host}:{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
