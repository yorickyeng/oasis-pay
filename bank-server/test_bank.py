from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path
import secrets
import ssl
import tempfile
import time
import unittest

import bank


class PaymentTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.path = Path(cls.temp.name) / "state"
        bank.initialize(cls.path, "127.0.0.1")
        cls.bank = bank.Bank(cls.path)
        cls.key = cls.path / "test-terminal.key"
        bank.openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
                     "-out", cls.key)
        cls.der = bank.openssl("pkey", "-in", cls.key, "-pubout", "-outform", "DER")
        cls.terminal = bank.digest(cls.der)
        cls.bank.enroll({"public_key": bank.b64(cls.der)})

    def request(self, **changes):
        timestamp = int(time.time())
        return {
            "type": "payment_request", "terminal_id": self.terminal,
            "transaction_id": secrets.token_hex(16), "nonce": secrets.token_hex(32),
            "amount": 19900, "currency": "RUB", "created_at": timestamp,
            "expires_at": timestamp + 300, **changes,
        }

    def test_concurrent_retries_and_acknowledgement(self):
        request = self.request()
        signed = bank.sign(self.key, request)
        self.assertEqual(self.bank.payment(signed, preview=True)["amount"], 19900)
        with self.bank.db() as db:
            before = db.execute("SELECT balance FROM account").fetchone()[0]
        with ThreadPoolExecutor(max_workers=5) as pool:
            results = list(pool.map(self.bank.payment, [signed] * 5))
        self.assertTrue(all(result == results[0] for result in results))
        raw, signature, result = bank.unpack(results[0])
        bank.verify(self.bank.public_key, raw, signature)
        self.assertEqual(result["status"], "PAID")
        self.assertEqual(result["request_hash"], bank.digest(bank.unpack(signed)[0]))
        self.assertEqual(result["nonce"], request["nonce"])
        ack = bank.sign(self.key, {
            "type": "delivery_ack", "terminal_id": self.terminal,
            "transaction_id": request["transaction_id"], "result_hash": bank.digest(raw),
        })
        for _ in range(2):
            self.assertTrue(self.bank.acknowledge(ack)["received"])
        with self.bank.db() as db:
            self.assertEqual(db.execute("SELECT balance FROM account").fetchone()[0], before - 19900)
            self.assertIsNotNone(db.execute(
                "SELECT delivered_at FROM payments WHERE transaction_id=?", (request["transaction_id"],)
            ).fetchone()[0])

    def test_tampering_expiry_and_reused_identifiers(self):
        request = self.request()
        signed = bank.sign(self.key, request)
        modified = {**signed, "payload": bank.b64(bank.encode({**request, "amount": 1}))}
        with self.assertRaisesRegex(bank.Rejected, "invalid_signature"):
            self.bank.payment(modified)
        self.bank.payment(signed)
        with self.assertRaisesRegex(bank.Rejected, "transaction_conflict"):
            self.bank.payment(bank.sign(self.key, {**request, "amount": 1}))
        with self.assertRaisesRegex(bank.Rejected, "nonce_reused"):
            self.bank.payment(bank.sign(self.key, self.request(nonce=request["nonce"])))
        expired = int(time.time()) - 1
        with self.assertRaisesRegex(bank.Rejected, "request_expired"):
            self.bank.payment(bank.sign(self.key, self.request(created_at=expired - 20, expires_at=expired)))

    def test_insufficient_funds_are_signed_and_stable(self):
        with self.bank.db() as db:
            balance = db.execute("SELECT balance FROM account").fetchone()[0]
            db.execute("UPDATE account SET balance=0")
        try:
            signed = bank.sign(self.key, self.request())
            declined = self.bank.payment(signed)
            raw, signature, result = bank.unpack(declined)
            bank.verify(self.bank.public_key, raw, signature)
            self.assertEqual(result["status"], "DECLINED")
            self.assertEqual(self.bank.payment(signed), declined)
        finally:
            with self.bank.db() as db:
                db.execute("UPDATE account SET balance=?", (balance,))


class HttpsTest(unittest.TestCase):
    def test_https_endpoints(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "state"
            bank.initialize(state, "127.0.0.1")
            application = bank.Bank(state)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_3
            context.load_cert_chain(state / "tls.crt", state / "tls.key")
            trust = ssl.create_default_context(cafile=str(state / "tls.crt"))
            trust.minimum_version = ssl.TLSVersion.TLSv1_3
            client_in, client_out, server_in, server_out = (ssl.MemoryBIO() for _ in range(4))
            client = trust.wrap_bio(client_in, client_out, server_hostname="127.0.0.1")
            server = context.wrap_bio(server_in, server_out, server_side=True)

            for _ in range(20):
                ready = 0
                for session, outbound, peer in ((client, client_out, server_in), (server, server_out, client_in)):
                    try:
                        session.do_handshake()
                        ready += 1
                    except ssl.SSLWantReadError:
                        pass
                    peer.write(outbound.read())
                if ready == 2:
                    break
            self.assertEqual(client.version(), "TLSv1.3")

            class Connection:
                def settimeout(self, _):
                    pass

                def makefile(self, *args):
                    return self.input

                def sendall(self, data):
                    self.output.write(data)

            def call(method, path, body=b"", code=""):
                header = (
                    f"{method} {path} HTTP/1.0\r\n"
                    f"Authorization: Bearer {code}\r\n"
                    f"Content-Type: application/json\r\n"
                    f"Content-Length: {len(body)}\r\n\r\n"
                ).encode()
                client.write(header + body)
                server_in.write(client_out.read())
                request = Connection()
                request.input = io.BytesIO(server.read(16384))
                request.output = io.BytesIO()
                server_config = type("Server", (), {"bank": application})()
                bank.Handler(request, ("127.0.0.1", 0), server_config)
                server.write(request.output.getvalue())
                client_in.write(server_out.read())
                headers, content = client.read(16384).split(b"\r\n\r\n", 1)
                return int(headers.split(b" ")[1]), json.loads(content)

            self.assertEqual(call("GET", "/health"), (200, {"status": "ok"}))
            key = state / "terminal.key"
            bank.openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256",
                         "-out", key)
            public = bank.openssl("pkey", "-in", key, "-pubout", "-outform", "DER")
            body = bank.encode({"public_key": bank.b64(public)})
            self.assertEqual(call("POST", "/v1/enroll", body)[0], 401)
            code, enrollment = call("POST", "/v1/enroll", body, application.config["enrollment_code"])
            self.assertEqual(code, 200)
            now = int(time.time())
            payment = {"type": "payment_request", "terminal_id": enrollment["terminal_id"],
                       "transaction_id": secrets.token_hex(16), "nonce": secrets.token_hex(32),
                       "amount": 19900, "currency": "RUB", "created_at": now, "expires_at": now + 300}
            body = bank.encode(bank.sign(key, payment))
            buyer = application.config["payer_code"]
            self.assertEqual(call("POST", "/v1/preview", body, buyer)[1]["amount"], 19900)
            status, result = call("POST", "/v1/pay", body, buyer)
            self.assertEqual(status, 200)
            payload, signature, value = bank.unpack(result)
            bank.verify(bank.unb64(enrollment["bank_key"]), payload, signature)
            self.assertEqual(value["status"], "PAID")
            self.assertEqual(call("POST", "/v1/pay", body, buyer), (200, result))
            ack = bank.sign(key, {"type": "delivery_ack", "terminal_id": payment["terminal_id"],
                                  "transaction_id": payment["transaction_id"],
                                  "result_hash": bank.digest(payload)})
            self.assertEqual(call("POST", "/v1/ack", bank.encode(ack), buyer), (200, {"received": True}))


if __name__ == "__main__":
    unittest.main()
