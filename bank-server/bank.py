import argparse
import hmac
import ipaddress
import json
import logging
import os
import re
import secrets
import ssl
import socket
import tempfile
import threading
import time
import uuid
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID
from admin import BANK_MAINTENANCE_LOCK, certificate_path

from protocol import (LIMIT, ProtocolError, decode, digest, encode, message, payload,
                      public_bytes, public_key, require, sign, verify)

MERCHANT = "demo-merchant"
BUYER = "demo-buyer"
ENVIRONMENT = "sandbox"
BINDINGS = ("terminal_id", "transaction_id", "order_id", "amount", "currency", "nonce", "merchant_id")


def now():
    return int(time.time())


def uuid_text(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{32}", value) is not None


def fresh(created, expires, maximum):
    moment = now()
    return (type(created) is int and type(expires) is int
            and moment - 30 <= expires and created <= moment + 30
            and 0 < expires - created <= maximum)


class Bank:
    def __init__(self, state, dsn):
        self.key = serialization.load_pem_private_key((state / "bank.key").read_bytes(), None)
        self.settings = json.loads((state / "settings.json").read_text())
        self.secret = bytes.fromhex(self.settings["secret"])
        self.dsn = dsn

    def connect(self):
        options = psycopg.conninfo.conninfo_to_dict(self.dsn).get("options", "")
        return psycopg.connect(self.dsn, row_factory=dict_row, connect_timeout=4,
                               options=f"{options} -c statement_timeout=5000 -c lock_timeout=3500")

    def token(self, device, nonce):
        identity = ("buyer:" + device).encode()
        # Existing registrations without a nonce retain their original bearer token.
        if nonce is not None:
            identity += b":" + nonce
        return hmac.digest(self.secret, identity, "sha256").hex()

    def enroll(self, db, role, raw, code):
        expected = self.settings[role + "_code"]
        require(hmac.compare_digest(expected, code), "unauthorized")
        entry = payload(raw)
        key = public_key(entry.get("public_key"))
        verify(raw, key)
        require(entry.get("type") == "enroll" and entry.get("role") == role
                and entry.get("environment") == ENVIRONMENT)
        model = entry.get("device_model")
        require(isinstance(model, str) and 0 < len(model) <= 80)
        device = digest(public_bytes(key)).hex()
        db.execute("SELECT pg_advisory_xact_lock(%s)", (1 if role == "buyer" else 2,))
        row = db.execute("SELECT * FROM devices WHERE id=%s", (device,)).fetchone()
        if row:
            require(row["role"] == role and not row["revoked"],
                    "enrollment_unavailable")
            certificate = row["certificate"]
            if row["valid_to"] < now() + 86400 or "registration_number" not in payload(certificate):
                moment = now()
                validity = moment + 30 * 86400
                certificate = sign(message("device_certificate", environment=ENVIRONMENT, device_id=device,
                                           role=role, public_key=public_bytes(key), merchant_id=MERCHANT,
                                           device_model=model, registration_number=self.registration_number(row),
                                           valid_from=moment, valid_to=validity), self.key)
                db.execute("UPDATE devices SET certificate=%s, valid_to=%s WHERE id=%s",
                           (certificate, validity, device))
        else:
            account = None
            if role == "buyer":
                legacy_used = db.execute("SELECT 1 FROM devices WHERE account_id=%s", (BUYER,)).fetchone()
                account = ("buyer-" + device) if legacy_used else BUYER
                # Sandbox credit only; never a source of real money.
                db.execute("INSERT INTO accounts(id,balance) VALUES(%s,500000) ON CONFLICT DO NOTHING", (account,))
            token_nonce = secrets.token_bytes(32) if role == "buyer" else None
            row = db.execute("""INSERT INTO devices
                (id,role,public_key,certificate,valid_to,account_id,token_nonce) VALUES(%s,%s,%s,%s,%s,%s,%s)
                RETURNING *""", (device, role, public_bytes(key), b"", 0, account, token_nonce)).fetchone()
            moment = now()
            validity = moment + 30 * 86400
            certificate = sign(message("device_certificate", environment=ENVIRONMENT, device_id=device,
                                       role=role, public_key=public_bytes(key), merchant_id=MERCHANT, device_model=model,
                                       registration_number=self.registration_number(row),
                                       valid_from=moment, valid_to=validity), self.key)
            db.execute("UPDATE devices SET certificate=%s,valid_to=%s WHERE id=%s", (certificate, validity, device))
        response = dict(certificate=certificate, bank_key=public_bytes(self.key.public_key()))
        if role == "buyer":
            response["access_token"] = self.token(device, row["token_nonce"])
        return response

    @staticmethod
    def registration_number(row):
        prefix = "T" if row["role"] == "terminal" else "C"
        return f"OP-{prefix}-{row['registration_number']:06d}"

    def account(self, db, raw, token):
        device, query = self.buyer(db, raw, token)
        require(query.get("type") == "account_query" and
                isinstance(query.get("nonce"), bytes) and len(query["nonce"]) == 32 and
                type(query.get("created_at")) is int and abs(now() - query["created_at"]) <= 30,
                "invalid_account_query")
        row = db.execute("SELECT * FROM devices WHERE id=%s", (device,)).fetchone()
        balance = db.execute("SELECT balance FROM accounts WHERE id=%s", (row["account_id"],)).fetchone()["balance"]
        return dict(account=sign(message("account_snapshot", environment=ENVIRONMENT,
            device_id=device, registration_number=self.registration_number(row),
            balance=balance, currency="RUB", request_nonce=query["nonce"], observed_at=now()), self.key))

    def buyer(self, db, raw, token):
        authorization = payload(raw)
        device = authorization.get("phone_device_id")
        require(isinstance(device, str) and re.fullmatch(r"[0-9a-f]{64}", device), "unauthorized")
        row = db.execute("SELECT * FROM devices WHERE id=%s AND role='buyer' FOR SHARE", (device,)).fetchone()
        require(row and not row["revoked"] and row["valid_to"] >= now() and
                hmac.compare_digest(self.token(device, row["token_nonce"]), token), "unauthorized")
        verify(raw, public_key(row["public_key"]))
        require(authorization.get("environment") == ENVIRONMENT)
        return device, authorization

    def terminal_request(self, db, raw):
        req = payload(raw)
        terminal = req.get("terminal_id")
        require(isinstance(terminal, str) and re.fullmatch(r"[0-9a-f]{64}", terminal))
        # Serialize operations for one offline terminal before inspecting idempotency and sequence.
        device = db.execute("SELECT * FROM devices WHERE id=%s AND role='terminal' FOR UPDATE",
                            (terminal,)).fetchone()
        require(device and not device["revoked"], "unknown_terminal")
        verify(raw, public_key(device["public_key"]))
        nonce = req.get("nonce")
        description = req.get("description")
        require(req.get("type") == "payment_request" and req.get("environment") == ENVIRONMENT
                and req.get("merchant_id") == MERCHANT and req.get("currency") == "RUB"
                and type(req.get("amount")) is int and 1 <= req["amount"] <= 100000
                and uuid_text(req.get("transaction_id")) and uuid_text(req.get("order_id"))
                and isinstance(nonce, bytes) and len(nonce) == 32
                and type(req.get("terminal_sequence")) is int and 0 < req["terminal_sequence"] < 2**63
                and isinstance(description, str) and 0 < len(description) <= 80
                and req.get("capabilities") in (["ble-l2cap/1"], ["nfc-isodep/1"], ["ble-gatt/1"], ["ble-gatt/2"])
                and type(req.get("created_at")) is int and 0 <= req["created_at"] < 2**62
                and type(req.get("expires_at")) is int and 0 < req["expires_at"] - req["created_at"] <= 120)
        reset_at = db.execute("SELECT reset_at FROM sandbox_state").fetchone()["reset_at"]
        require(not reset_at or req["created_at"] > reset_at, "request_expired")
        return device, req

    def result_response(self, result):
        fields = payload(result)
        moment = now()
        permit = sign(message(
            "result_permit", environment=ENVIRONMENT,
            terminal_id=fields["terminal_id"], transaction_id=fields["transaction_id"],
            phone_device_id=fields["phone_device_id"], request_hash=fields["request_hash"],
            result_hash=digest(result), issued_at=moment, expires_at=moment + 300,
        ), self.key)
        return dict(result=result, permit=permit)

    def payment(self, db, raw, token, mode):
        preview = mode == "preview"
        resolution = mode == "resolve"
        buyer, authorization = self.buyer(db, raw, token)
        kind = {"preview": "payment_preview", "resolve": "payment_resolution", "pay": "payment_authorization"}[mode]
        require(authorization.get("type") == kind)
        terminal_request = authorization.get("terminal_request")
        device, req = self.terminal_request(db, terminal_request)
        for name in BINDINGS:
            require(type(req[name]) is type(authorization.get(name)) and req[name] == authorization[name])
        key = (ENVIRONMENT, req["terminal_id"], req["transaction_id"])
        request_hash = digest(terminal_request)
        existing = db.execute("""SELECT * FROM payments
            WHERE environment=%s AND terminal_id=%s AND transaction_id=%s""", key).fetchone()
        if existing:
            require(existing["request_hash"] == request_hash and existing["buyer_id"] == buyer, "conflict")
            return (dict(amount=req["amount"], settled=True, merchant_id=MERCHANT) if preview
                    else self.result_response(existing["result"]))
        if resolution:
            require(now() > req["expires_at"] + 30, "request_still_active")
        else:
            require(device["valid_to"] >= now(), "terminal_certificate_expired")
            require(fresh(req["created_at"], req["expires_at"], 120), "request_expired")
            require(req["terminal_sequence"] > device["last_sequence"], "sequence_replayed")
        require(not db.execute("""SELECT 1 FROM payments WHERE environment=%s AND terminal_id=%s
            AND (nonce=%s OR order_id=%s)""", (ENVIRONMENT, req["terminal_id"], req["nonce"],
                                              req["order_id"])).fetchone(), "conflict")
        if preview:
            return dict(amount=req["amount"], settled=False, merchant_id=MERCHANT)
        amount = req["amount"]
        account = db.execute("SELECT account_id FROM devices WHERE id=%s", (buyer,)).fetchone()["account_id"]
        debit = None
        status = "CANCELLED"
        if not resolution:
            debit = db.execute("UPDATE accounts SET balance=balance-%s WHERE id=%s AND balance >= %s RETURNING id",
                               (amount, account, amount)).fetchone()
            status = "PAID" if debit else "DECLINED"
        if debit:
            db.execute("UPDATE accounts SET balance=balance+%s WHERE id=%s", (amount, MERCHANT))
        operation = uuid.uuid4()
        moment = now()
        result = sign(message("payment_result", environment=ENVIRONMENT,
                              **{name: req[name] for name in BINDINGS},
                              status=status, server_operation_id=operation.hex, phone_device_id=buyer,
                              request_hash=request_hash, processed_at=moment,
                              result_nonce=secrets.token_bytes(32), expires_at=moment + 86400), self.key)
        db.execute("""INSERT INTO payments
            (environment,terminal_id,transaction_id,buyer_id,order_id,nonce,terminal_sequence,
             request_hash,amount,status,operation_id,result) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                   (*key, buyer, req["order_id"], req["nonce"], req["terminal_sequence"],
                    request_hash, amount, status, operation, result))
        if debit:
            db.execute("INSERT INTO ledger VALUES (%s,%s,%s,%s)", (operation, account, MERCHANT, amount))
        db.execute("UPDATE devices SET last_sequence=GREATEST(last_sequence,%s) WHERE id=%s", (req["terminal_sequence"], device["id"]))
        db.execute("INSERT INTO outbox (operation_id,event) VALUES (%s,%s)", (operation, status))
        return self.result_response(result)

    def confirmation(self, db, raw, token, transaction):
        buyer, relay = self.buyer(db, raw, token)
        require(relay.get("type") == "relay_ack" and uuid_text(transaction))
        ack_raw = relay.get("terminal_ack")
        ack = payload(ack_raw)
        terminal = ack.get("terminal_id")
        require(isinstance(terminal, str) and re.fullmatch(r"[0-9a-f]{64}", terminal))
        device = db.execute("SELECT * FROM devices WHERE id=%s AND role='terminal' FOR SHARE",
                            (terminal,)).fetchone()
        require(device and not device["revoked"], "unknown_terminal")
        verify(ack_raw, public_key(device["public_key"]))
        require(ack.get("type") == "payment_result_ack" and ack.get("environment") == ENVIRONMENT
                and ack.get("transaction_id") == transaction and type(ack.get("received_at")) is int
                and ack["received_at"] <= now() + 30)
        row = db.execute("""SELECT * FROM payments WHERE environment=%s AND terminal_id=%s
            AND transaction_id=%s AND buyer_id=%s FOR UPDATE""",
                         (ENVIRONMENT, terminal, transaction, buyer)).fetchone()
        state = ack.get("terminal_state")
        require(row and ack.get("result_hash") == digest(row["result"])
                and state in ("RECEIVED", "FULFILLED"))
        require(state != "FULFILLED" or row["status"] == "PAID")
        operation = row["operation_id"]
        db.execute("UPDATE payments SET received_at=coalesce(received_at,now()) WHERE operation_id=%s", (operation,))
        db.execute("INSERT INTO outbox (operation_id,event) VALUES (%s,'RECEIVED') ON CONFLICT DO NOTHING", (operation,))
        if state == "FULFILLED":
            db.execute("UPDATE payments SET fulfilled_at=coalesce(fulfilled_at,now()) WHERE operation_id=%s", (operation,))
            db.execute("INSERT INTO outbox (operation_id,event) VALUES (%s,'FULFILLED') ON CONFLICT DO NOTHING", (operation,))
        return dict(terminal_state="FULFILLED" if state == "FULFILLED" or row["fulfilled_at"] else "RECEIVED")

    def dispatch(self, path, raw, token):
        with self.connect() as db:
            db.execute("SELECT pg_advisory_xact_lock_shared(%s)", (BANK_MAINTENANCE_LOCK,))
            if path in ("/api/v1/enroll/terminal", "/api/v1/enroll/buyer"):
                return self.enroll(db, path.rsplit("/", 1)[1], raw, token)
            if path == "/api/v1/account":
                return self.account(db, raw, token)
            if path in ("/api/v1/payments", "/api/v1/payments/preview", "/api/v1/payments/resolve"):
                mode = "pay" if path == "/api/v1/payments" else path.rsplit("/", 1)[1]
                return self.payment(db, raw, token, mode)
            match = re.fullmatch(r"/api/v1/payments/([0-9a-f]{32})/delivery-confirmation", path)
            require(match, "not_found")
            return self.confirmation(db, raw, token, match[1])


class Server(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, bank, tls):
        self.bank, self.tls = bank, tls
        self.slots = threading.BoundedSemaphore(24)
        super().__init__(address, Handler)

    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except BaseException:
            self.slots.release()
            raise

    @staticmethod
    def expire_connection(connection):
        try:
            connection.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass

    def process_request_thread(self, request, address):
        try:
            request.settimeout(8)
            with self.tls.wrap_socket(request, server_side=True) as secured:
                deadline = threading.Timer(20, self.expire_connection, args=(secured,))
                deadline.daemon = True
                deadline.start()
                try:
                    self.finish_request(secured, address)
                finally:
                    deadline.cancel()
        except (OSError, TimeoutError):
            pass
        finally:
            self.shutdown_request(request)
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def reply(self, status, value):
        body = encode(value)
        self.send_response(status)
        self.send_header("Content-Type", "application/cbor")
        self.send_header("Cache-Control", "no-store")
        # This bounded server handles one HTTP request per TLS connection.
        # Explicitly prevent clients from pooling a socket that is about to close.
        self.send_header("Connection", "close")
        self.close_connection = True
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path != "/health":
            self.reply(404, {"error": "not_found"})
            return
        try:
            with self.server.bank.connect() as db:
                db.execute("SELECT 1 FROM accounts LIMIT 1")
            self.reply(200, {"status": "ok", "environment": ENVIRONMENT, "server_time": now()})
        except psycopg.Error:
            self.reply(503, {"error": "database_unavailable"})

    def do_POST(self):
        try:
            require(self.headers.get("Content-Type") == "application/cbor", "invalid_content_type")
            require(not self.headers.get("Transfer-Encoding"), "invalid_length")
            lengths = self.headers.get_all("Content-Length", [])
            require(len(lengths) == 1 and re.fullmatch(r"[0-9]{1,5}", lengths[0]), "invalid_length")
            size = int(lengths[0])
            require(0 < size <= LIMIT, "invalid_length")
            require(len(self.headers.get_all("Authorization", [])) == 1, "unauthorized")
            auth = self.headers.get("Authorization", "")
            require(re.fullmatch(r"Bearer [A-Za-z0-9_-]{32,64}", auth), "unauthorized")
            raw = self.rfile.read(size)
            require(len(raw) == size)
            response = self.server.bank.dispatch(self.path, raw, auth[7:])
        except ProtocolError as exc:
            code = str(exc)
            self.reply({"unauthorized": 401, "not_found": 404, "conflict": 409}.get(code, 400), {"error": code})
        except ValueError:
            self.reply(400, {"error": "invalid_message"})
        except psycopg.Error as exc:
            logging.error("Database operation failed (%s)", exc.sqlstate)
            self.reply(503, {"error": "database_unavailable"})
        else:
            self.reply(200, response)


def pem_key(key):
    return key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                             serialization.NoEncryption())


def initialize(state, host, dsn):
    ip = ipaddress.IPv4Address(host)
    require(not state.exists(), "bank_state_exists")
    with psycopg.connect(dsn, connect_timeout=4) as db:
        db.execute(Path(__file__).with_name("schema.sql").read_text())
    tls_key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "oasis pay sandbox")])
    date = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(tls_key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(date - timedelta(minutes=5)).not_valid_after(date + timedelta(days=30))
            .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ip), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False)
            .sign(tls_key, hashes.SHA256()))
    with tempfile.TemporaryDirectory(prefix=".initializing-", dir=state.parent) as temporary:
        staging = Path(temporary)
        (staging / "tls.crt").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        (staging / "tls.key").write_bytes(pem_key(tls_key))
        (staging / "bank.key").write_bytes(pem_key(ec.generate_private_key(ec.SECP256R1())))
        settings = dict(host=host, terminal_code=secrets.token_urlsafe(24), buyer_code=secrets.token_urlsafe(24),
                        secret=secrets.token_hex(32), pin=cert.fingerprint(hashes.SHA256()).hex())
        (staging / "settings.json").write_text(json.dumps(settings))
        staging.rename(state)


def migrate(bank):
    with bank.connect() as db:
        db.execute("SELECT pg_advisory_xact_lock(%s)", (BANK_MAINTENANCE_LOCK,))
        db.execute(Path(__file__).with_name("schema.sql").read_text())


def show(state, port):
    settings = json.loads((state / "settings.json").read_text())
    print(f"URL              https://{settings['host']}:{port}")
    for field, label in (("pin", "TLS SHA-256"), ("terminal_code", "Код кассы"), ("buyer_code", "Код покупателя")):
        print(f"{label:16} {settings[field]}")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("--state", type=Path, default=Path(".bank"))
    parser.add_argument("--port", type=int, default=8443)
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("init").add_argument("--host", required=True)
    commands.add_parser("show").add_argument("--expect-host")
    for name in ("serve", "migrate", "ledger", "health"):
        commands.add_parser(name)
    args = parser.parse_args()
    dsn = os.environ.get("OASIS_DATABASE_URL", "")
    if args.action == "show":
        if args.expect_host:
            stored = json.loads((args.state / "settings.json").read_text())["host"]
            if stored != args.expect_host:
                parser.error(f"Сохранённый сертификат выдан для {stored}")
        show(args.state, args.port)
        return
    if args.action == "health":
        context = ssl.create_default_context(cafile=str(certificate_path(args.state)))
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        with urllib.request.urlopen(f"https://127.0.0.1:{args.port}/health", context=context, timeout=2) as response:
            require(decode(response.read()).get("status") == "ok", "unhealthy")
        return
    if not dsn:
        parser.error("Set OASIS_DATABASE_URL")
    if args.action == "init":
        initialize(args.state, args.host, dsn)
        show(args.state, args.port)
        return
    bank = Bank(args.state, dsn)
    if args.action == "migrate":
        migrate(bank)
    elif args.action == "ledger":
        with bank.connect() as db:
            for row in db.execute("SELECT * FROM accounts ORDER BY id"):
                print(row)
            for row in db.execute("""SELECT transaction_id, amount, status, received_at, fulfilled_at
                FROM payments ORDER BY operation_id"""):
                print(row)
    else:
        migrate(bank)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = context.maximum_version = ssl.TLSVersion.TLSv1_3
        context.num_tickets = 0
        context.load_cert_chain(certificate_path(args.state), args.state / "tls.key")
        print(f"oasis pay sandbox is listening on port {args.port}", flush=True)
        with Server(("0.0.0.0", args.port), bank, context) as server:
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass


if __name__ == "__main__":
    main()
