"""HTTPS demo bank. Python 3.9+, SQLite, OpenSSL CLI."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import ssl
import subprocess
import tempfile
import time

DOMAIN = b"oasis-pay/v1\n"
MAX_AMOUNT = 100_000
OPENSSL = os.environ.get("OASIS_PAY_OPENSSL") or next(
    (str(path) for path in (Path("/opt/homebrew/opt/openssl@3/bin/openssl"),
                            Path("/usr/local/opt/openssl@3/bin/openssl")) if path.is_file()),
    "openssl",
)


class Rejected(Exception):
    def __init__(self, status, message):
        self.status, self.message = status, message


def check(condition, message="invalid_request", status=400):
    if not condition:
        raise Rejected(status, message)


def openssl(*args, data=None):
    return subprocess.run([OPENSSL, *map(str, args)], input=data, capture_output=True, check=True).stdout


def encode(value):
    return json.dumps(value, separators=(",", ":"), ensure_ascii=True).encode()


def b64(data):
    return base64.b64encode(data).decode("ascii")


def unb64(value):
    return base64.b64decode(value, validate=True)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sign(key, value):
    payload = encode(value)
    signature = openssl("dgst", "-sha256", "-sign", key, data=DOMAIN + payload)
    return {"payload": b64(payload), "signature": b64(signature)}


def unpack(envelope):
    check(isinstance(envelope, dict) and set(envelope) == {"payload", "signature"})
    payload, signature = unb64(envelope["payload"]), unb64(envelope["signature"])
    check(len(payload) <= 3000 and 8 <= len(signature) <= 80)
    value = json.loads(payload)
    check(isinstance(value, dict))
    return payload, signature, value


def verify(public_der, payload, signature):
    with tempfile.TemporaryDirectory(prefix="oasis-verify-") as directory:
        key, sig = Path(directory) / "key.der", Path(directory) / "signature"
        key.write_bytes(public_der)
        sig.write_bytes(signature)
        try:
            openssl("dgst", "-sha256", "-verify", key, "-keyform", "DER",
                    "-signature", sig, data=DOMAIN + payload)
        except subprocess.CalledProcessError:
            raise Rejected(403, "invalid_signature") from None


SCHEMA = """
CREATE TABLE IF NOT EXISTS terminals (
    id TEXT PRIMARY KEY, public_key BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS account (
    id INTEGER PRIMARY KEY CHECK (id = 1), balance INTEGER NOT NULL CHECK (balance >= 0)
);
CREATE TABLE IF NOT EXISTS payments (
    terminal_id TEXT NOT NULL REFERENCES terminals(id),
    transaction_id TEXT NOT NULL,
    nonce TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    amount INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PAID', 'DECLINED')),
    result BLOB NOT NULL,
    delivered_at INTEGER,
    PRIMARY KEY (terminal_id, transaction_id),
    UNIQUE (terminal_id, nonce)
);
INSERT OR IGNORE INTO account VALUES (1, 500000);
"""


class Bank:
    def __init__(self, state):
        self.state = Path(state)
        self.config = json.loads((self.state / "config.json").read_text())
        self.signing_key = self.state / "bank.key"
        self.public_key = openssl("pkey", "-in", self.signing_key, "-pubout", "-outform", "DER")
        with self.db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    @contextmanager
    def db(self):
        db = sqlite3.connect(self.state / "ledger.sqlite3", timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                db.execute("PRAGMA foreign_keys=ON")
                yield db
        finally:
            db.close()

    def auth(self, role, authorization):
        actual = authorization.encode("utf-8")
        expected = ("Bearer " + self.config[role]).encode()
        check(secrets.compare_digest(actual, expected), "unauthorized", 401)

    def enroll(self, value):
        public = unb64(value["public_key"])
        check(len(public) == 91)
        p256_prefix = bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")
        check(public.startswith(p256_prefix), "expected_p256_key")
        try:
            openssl("pkey", "-pubin", "-inform", "DER", "-pubcheck", "-noout", data=public)
        except subprocess.CalledProcessError:
            raise Rejected(400, "invalid_public_key") from None
        terminal_id = digest(public)
        with self.db() as db:
            db.execute("INSERT OR IGNORE INTO terminals VALUES (?, ?)", (terminal_id, public))
        return {"terminal_id": terminal_id, "bank_key": b64(self.public_key)}

    def signed_terminal_message(self, db, envelope, kind):
        payload, signature, value = unpack(envelope)
        check(value.get("type") == kind)
        terminal_id = value["terminal_id"]
        check(isinstance(terminal_id, str) and re.fullmatch("[0-9a-f]{64}", terminal_id))
        terminal = db.execute("SELECT public_key FROM terminals WHERE id=?", (terminal_id,)).fetchone()
        check(terminal is not None, "unknown_terminal", 403)
        verify(terminal["public_key"], payload, signature)
        return payload, value

    def payment(self, envelope, *, preview=False):
        with self.db() as db:
            raw, request = self.signed_terminal_message(db, envelope, "payment_request")
            fields = {"type", "terminal_id", "transaction_id", "nonce", "amount",
                      "currency", "created_at", "expires_at"}
            check(set(request) == fields and request["currency"] == "RUB")
            terminal_id, tx, nonce, amount, created, expires = (
                request[k] for k in ("terminal_id", "transaction_id", "nonce", "amount",
                                     "created_at", "expires_at")
            )
            check(isinstance(tx, str) and re.fullmatch("[0-9a-f]{32}", tx))
            check(isinstance(nonce, str) and re.fullmatch("[0-9a-f]{64}", nonce))
            check(type(amount) is int and 0 < amount <= MAX_AMOUNT)
            check(type(created) is int and type(expires) is int and 0 < expires - created <= 300)
            if not preview:
                db.execute("BEGIN IMMEDIATE")
            existing = db.execute(
                "SELECT * FROM payments WHERE terminal_id=? AND transaction_id=?", (terminal_id, tx)
            ).fetchone()
            if existing:
                check(existing["request_hash"] == digest(raw), "transaction_conflict", 409)
                if not preview:
                    return json.loads(existing["result"])
            else:
                now = int(time.time())
                check(created <= now + 60 and now <= expires, "request_expired_or_clock_skew", 409)
            if preview:
                return {"amount": amount, "currency": "RUB", "transaction_id": tx,
                        "settled": existing["status"] if existing else None}
            check(db.execute("SELECT 1 FROM payments WHERE terminal_id=? AND nonce=?",
                             (terminal_id, nonce)).fetchone() is None, "nonce_reused", 409)
            debit = db.execute("UPDATE account SET balance=balance-? WHERE id=1 AND balance>=?",
                               (amount, amount))
            status = "PAID" if debit.rowcount == 1 else "DECLINED"
            now = int(time.time())
            result = sign(self.signing_key, {
                "type": "payment_result", "terminal_id": terminal_id, "transaction_id": tx,
                "nonce": nonce, "amount": amount, "currency": "RUB", "status": status,
                "request_hash": digest(raw), "processed_at": now, "expires_at": now + 86400,
            })
            db.execute("INSERT INTO payments VALUES (?, ?, ?, ?, ?, ?, ?, NULL)",
                       (terminal_id, tx, nonce, digest(raw), amount, status, encode(result)))
            return result

    def acknowledge(self, envelope):
        with self.db() as db:
            _, ack = self.signed_terminal_message(db, envelope, "delivery_ack")
            check(set(ack) == {"type", "terminal_id", "transaction_id", "result_hash"})
            row = db.execute("SELECT result FROM payments WHERE terminal_id=? AND transaction_id=?",
                             (ack["terminal_id"], ack["transaction_id"])).fetchone()
            check(row is not None, "unknown_transaction", 404)
            result_payload = unb64(json.loads(row["result"])["payload"])
            check(ack["result_hash"] == digest(result_payload), "wrong_result")
            db.execute(
                "UPDATE payments SET delivered_at=COALESCE(delivered_at, ?) "
                "WHERE terminal_id=? AND transaction_id=?",
                (int(time.time()), ack["terminal_id"], ack["transaction_id"]),
            )
        return {"received": True}

    def handle(self, method, path, authorization, body):
        if method == "GET" and path == "/health":
            return {"status": "ok"}
        check(method == "POST", "not_found", 404)
        if path == "/v1/enroll":
            self.auth("enrollment_code", authorization)
            return self.enroll(body)
        if path in ("/v1/preview", "/v1/pay", "/v1/ack"):
            self.auth("payer_code", authorization)
            if path == "/v1/ack":
                return self.acknowledge(body)
            return self.payment(body, preview=path == "/v1/preview")
        raise Rejected(404, "not_found")


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(10)
        super().setup()

    def respond(self):
        try:
            body = None
            if self.command == "POST":
                size = int(self.headers.get("Content-Length", "0"))
                check(0 < size <= 8192, "invalid_body_length", 413)
                check(self.headers.get_content_type() == "application/json", "expected_json", 415)
                body = json.loads(self.rfile.read(size))
                check(isinstance(body, dict))
            value = self.server.bank.handle(self.command, self.path, self.headers.get("Authorization", ""), body)
            code = 200
        except Rejected as error:
            code, value = error.status, {"error": error.message}
        except (ValueError, TypeError, KeyError, TimeoutError, RecursionError):
            code, value = 400, {"error": "invalid_request"}
        except (sqlite3.Error, subprocess.CalledProcessError, OSError) as error:
            self.log_error("%s", type(error).__name__)
            code, value = 500, {"error": "bank_unavailable"}
        output = encode(value)
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(output)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(output)
        except OSError:
            pass

    do_GET = respond
    do_POST = respond


def initialize(state, host):
    host = str(ipaddress.IPv4Address(host))
    state.mkdir(mode=0o700, parents=True, exist_ok=False)
    openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
            "-out", state / "bank.key")
    openssl("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256",
            "-nodes", "-days", "30", "-subj", "/CN=Oasis Pay Demo Bank",
            "-addext", f"subjectAltName=IP:{host},IP:127.0.0.1",
            "-addext", "basicConstraints=critical,CA:FALSE",
            "-addext", "keyUsage=critical,digitalSignature",
            "-addext", "extendedKeyUsage=serverAuth",
            "-keyout", state / "tls.key", "-out", state / "tls.crt")
    config = {"host": host, "enrollment_code": secrets.token_urlsafe(24),
              "payer_code": secrets.token_urlsafe(24)}
    (state / "config.json").write_bytes(encode(config))
    Bank(state)


def show(state, port):
    bank = Bank(state)
    certificate = ssl.PEM_cert_to_DER_cert((state / "tls.crt").read_text())
    print("URL:", f"https://{bank.config['host']}:{port}")
    print("Отпечаток TLS (SHA-256):", digest(certificate))
    print("Код терминала:", bank.config["enrollment_code"])
    print("Код покупателя:", bank.config["payer_code"])


def serve(state, bind, port):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(state / "tls.crt", state / "tls.key")

    class BankServer(ThreadingHTTPServer):
        def finish_request(self, request, address):
            request.settimeout(10)
            try:
                with context.wrap_socket(request, server_side=True) as secure:
                    super().finish_request(secure, address)
            except (ssl.SSLError, OSError):
                return

    with BankServer((bind, port), Handler) as server:
        server.bank = Bank(state)
        print(f"Банк запущен на {bind}:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", type=Path, default=Path(__file__).resolve().parent / ".bank")
    parser.add_argument("--port", type=int, default=8443)
    commands = parser.add_subparsers(dest="command", required=True)
    init = commands.add_parser("init")
    init.add_argument("--host", required=True, help="IPv4-адрес Mac, доступный покупателю")
    commands.add_parser("show")
    run = commands.add_parser("serve")
    run.add_argument("--bind", default="0.0.0.0")
    commands.add_parser("ledger")
    args = parser.parse_args()
    if args.command == "init":
        initialize(args.state, args.host)
    if args.command in ("init", "show"):
        show(args.state, args.port)
    elif args.command == "serve":
        serve(args.state, args.bind, args.port)
    elif args.command == "ledger":
        with Bank(args.state).db() as db:
            print("Баланс, коп.:", db.execute("SELECT balance FROM account").fetchone()[0])
            for row in db.execute("SELECT transaction_id, amount, status, delivered_at FROM payments"):
                print(dict(row))


if __name__ == "__main__":
    main()
