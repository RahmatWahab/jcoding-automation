"""Controller-side Responses gateway. Secrets never cross the Unix socket boundary."""
import hmac
import http.client
import http.server
import ipaddress
import json
import os
import socket
import socketserver
import ssl
import threading
import time
import urllib.parse

TOKEN = os.environ.pop('JCODING_GATEWAY_TOKEN')
SECRET = os.environ.pop('JCODING_PROVIDER_SECRET')
MODEL = os.environ['JCODING_GATEWAY_MODEL']
BASE = urllib.parse.urlsplit(os.environ['JCODING_GATEWAY_UPSTREAM'])
ALLOWED = json.loads(os.environ['JCODING_GATEWAY_HOSTS'])
DEADLINE = time.monotonic() + int(os.environ['JCODING_GATEWAY_TTL'])
MAX_REQUESTS = int(os.environ['JCODING_GATEWAY_REQUESTS'])
MAX_OUTPUT = int(os.environ['JCODING_GATEWAY_OUTPUT'])
MAX_BODY = 8 * 1024 * 1024
LOCK = threading.Lock()
CALLS = 0
if BASE.scheme != 'https' or BASE.hostname not in ALLOWED or BASE.username or BASE.password or BASE.query or BASE.fragment:
    raise ValueError('Gateway requires an allowlisted HTTPS provider origin')


class PinnedHTTPS(http.client.HTTPSConnection):
    def connect(self):
        addresses = socket.getaddrinfo(self.host, self.port, type=socket.SOCK_STREAM)
        if not addresses or any(not ipaddress.ip_address(a[4][0]).is_global for a in addresses):
            raise ValueError('Provider resolved to a non-public address')
        family, socktype, proto, _, address = addresses[0]
        raw = socket.socket(family, socktype, proto)
        raw.settimeout(self.timeout)
        try:
            raw.connect(address)
            self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=self.host)
        except BaseException:
            raw.close()
            raise


def validate_payload(body, compact=False):
    if not isinstance(body, dict) or body.get('model') != MODEL:
        raise ValueError('Model outside run scope')
    for tool in body.get('tools', []):
        if not isinstance(tool, dict) or tool.get('type') not in ('function', 'custom'):
            raise ValueError('Hosted tools are outside run scope')
    def check(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if key in ('file_url', 'image_url') and isinstance(item, str) and not item.startswith('data:'):
                    raise ValueError('Remote media fetching is outside run scope')
                check(item)
        elif isinstance(value, list):
            for item in value:
                check(item)
    check(body)
    if not compact:
        requested = body.get('max_output_tokens', MAX_OUTPUT)
        if not isinstance(requested, int) or isinstance(requested, bool) or requested < 1:
            raise ValueError('Invalid output ceiling')
        body['max_output_tokens'] = min(requested, MAX_OUTPUT)
        body['store'] = False
        body.pop('background', None)
    return body


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.0'

    def log_message(self, *_):
        pass

    def body(self):
        if self.headers.get('Transfer-Encoding'):
            if self.headers.get('Transfer-Encoding').lower() != 'chunked' or self.headers.get('Content-Length'):
                raise ValueError('Ambiguous framing')
            result = bytearray()
            while True:
                line = self.rfile.readline(128)
                size = int(line.strip(), 16)
                if size < 0 or len(result) + size > MAX_BODY:
                    raise ValueError('Request body exceeds limit')
                if size == 0:
                    if self.rfile.readline(8192) != b'\r\n':
                        raise ValueError('Trailers unsupported')
                    return bytes(result)
                chunk = self.rfile.read(size)
                if len(chunk) != size or self.rfile.read(2) != b'\r\n':
                    raise ValueError('Incomplete request')
                result.extend(chunk)
        length = int(self.headers.get('Content-Length', '-1'))
        if not 0 <= length <= MAX_BODY:
            raise ValueError('Invalid request length')
        result = self.rfile.read(length)
        if len(result) != length:
            raise ValueError('Incomplete request')
        return result

    def do_POST(self):
        global CALLS
        if self.path not in ('/v1/responses', '/v1/responses/compact'):
            self.send_error(403, 'Operation outside run scope')
            return
        if not hmac.compare_digest(self.headers.get('Authorization', ''), 'Bearer ' + TOKEN):
            self.send_error(401, 'Run credential required')
            return
        upstream = None
        try:
            payload = validate_payload(json.loads(self.body()), self.path.endswith('/compact'))
            with LOCK:
                if time.monotonic() >= DEADLINE or CALLS >= MAX_REQUESTS:
                    self.send_error(429, 'Run grant exhausted or expired')
                    return
                CALLS += 1  # A failed or uncertain request consumes its reservation.
            timeout = max(1, min(300, int(DEADLINE - time.monotonic())))
            upstream = PinnedHTTPS(BASE.hostname, BASE.port or 443, timeout=timeout)
            path = BASE.path.rstrip('/') + self.path.removeprefix('/v1')
            upstream.request('POST', path, json.dumps(payload).encode(), {
                'Authorization': 'Bearer ' + SECRET, 'Content-Type': 'application/json',
                'Accept': 'text/event-stream, application/json',
            })
            response = upstream.getresponse()
            # No redirects, provider credentials, cookies or arbitrary headers forwarded.
            if 300 <= response.status < 400:
                self.send_error(502, 'Provider redirects forbidden')
                return
            self.send_response(response.status)
            self.send_header('Content-Type', response.getheader('Content-Type', 'application/json'))
            self.send_header('Connection', 'close')
            self.end_headers()
            total = 0
            while time.monotonic() < DEADLINE:
                chunk = response.read1(8192)
                if not chunk:
                    break
                total += len(chunk)
                if total > 32 * 1024 * 1024:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (ValueError, KeyError, TypeError):
            self.send_error(400, 'Invalid scoped model request')
        except (OSError, http.client.HTTPException):
            self.close_connection = True
        finally:
            if upstream:
                upstream.close()


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    slots = threading.BoundedSemaphore(8)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close()
            return
        request.settimeout(30)
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()

    def handle_error(self, *_):
        pass


with Server(os.environ['JCODING_GATEWAY_SOCKET'], Handler) as server:
    os.chmod(os.environ['JCODING_GATEWAY_SOCKET'], 0o600)
    server.timeout = 0.5
    while time.monotonic() < DEADLINE:
        server.handle_request()
