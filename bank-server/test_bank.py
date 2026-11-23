import concurrent.futures
import os
import ssl
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from unittest.mock import patch

import psycopg
from cryptography.hazmat.primitives.asymmetric import ec
from psycopg.conninfo import make_conninfo
from psycopg import sql

from admin import apply, certificate_path, issue, reset_clients, reset_sandbox
from bank import BINDINGS, Bank, Server, initialize, migrate, now
from protocol import P256_ORDER, ProtocolError, decode, digest, encode, message, public_bytes, public_key, sign, verify


class ProtocolTests(unittest.TestCase):
    def test_canonical_cbor(self):
        value = {"amount": 19900, "currency": "RUB"}
        self.assertEqual(encode(value).hex(), "a266616d6f756e74194dbc6863757272656e637963525542")
        for invalid in (b"\x18\x01", b"\x01\x01", b"\xa2\x61a\x01\x61a\x02", b"\x9f\xff",
                        b"\x81" * 9 + b"\x01", b"\x81" * 10_000 + b"\x01"):
            with self.subTest(prefix=invalid[:16], size=len(invalid)):
                with self.assertRaises(ProtocolError):
                    decode(invalid)

    def test_es256_tampering(self):
        key = ec.generate_private_key(ec.SECP256R1())
        wire = sign(message("vector", environment="sandbox", amount=19900), key)
        self.assertEqual(verify(wire, key.public_key())["amount"], 19900)
        fields = decode(wire)
        r_and_s = fields[3]
        alternative_s = P256_ORDER - int.from_bytes(r_and_s[32:], "big")
        fields[3] = r_and_s[:32] + alternative_s.to_bytes(32, "big")
        with self.assertRaises(ProtocolError):
            verify(encode(fields), key.public_key())
        fields[3] = r_and_s
        fields[2] = encode(message("vector", environment="sandbox", amount=1))
        with self.assertRaises(ProtocolError):
            verify(encode(fields), key.public_key())


@unittest.skipUnless(os.environ.get("OASIS_TEST_DATABASE_URL"), "Set OASIS_TEST_DATABASE_URL")
class BankIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base_dsn = os.environ["OASIS_TEST_DATABASE_URL"]
        cls.schema = "qa_" + uuid.uuid4().hex
        with psycopg.connect(cls.base_dsn) as db:
            db.execute(sql.SQL("CREATE SCHEMA {}").format(sql.Identifier(cls.schema)))
        cls.addClassCleanup(cls.remove_schema)

        directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(directory.cleanup)
        state = Path(directory.name) / "bank"
        # Isolate tests even if the connection points at an existing sandbox database.
        cls.dsn = make_conninfo(cls.base_dsn, options=f"-c search_path={cls.schema}")
        initialize(state, "127.0.0.1", cls.dsn)
        cls.state = state
        cls.bank = Bank(state, cls.dsn)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = tls.maximum_version = ssl.TLSVersion.TLSv1_3
        tls.load_cert_chain(state / "tls.crt", state / "tls.key")
        cls.http = Server(("127.0.0.1", 0), cls.bank, tls)
        thread = threading.Thread(target=cls.http.serve_forever, daemon=True)
        thread.start()
        cls.addClassCleanup(cls.http.server_close)
        cls.addClassCleanup(cls.http.shutdown)
        cls.client_tls = ssl.create_default_context(cafile=str(state / "tls.crt"))
        cls.client_tls.minimum_version = cls.client_tls.maximum_version = ssl.TLSVersion.TLSv1_3
        cls.url = f"https://127.0.0.1:{cls.http.server_port}"

    @classmethod
    def remove_schema(cls):
        with psycopg.connect(cls.base_dsn) as db:
            db.execute(sql.SQL("DROP SCHEMA {} CASCADE").format(sql.Identifier(cls.schema)))

    def setUp(self):
        with self.bank.connect() as db:
            db.execute("TRUNCATE outbox, ledger, payments, devices")
            db.execute("DELETE FROM accounts WHERE id NOT IN ('demo-buyer','demo-merchant')")
            db.execute("UPDATE accounts SET balance=CASE WHEN id='demo-buyer' THEN 500000 ELSE 0 END")
            db.execute("UPDATE sandbox_state SET reset_at=0")
        self.terminal = ec.generate_private_key(ec.SECP256R1())
        self.phone = ec.generate_private_key(ec.SECP256R1())
        terminal = self.enroll("terminal", self.terminal)
        buyer = self.enroll("buyer", self.phone)
        self.bank_key = public_key(terminal["bank_key"])
        self.terminal_id = verify(terminal["certificate"], self.bank_key)["device_id"]
        self.phone_id = verify(buyer["certificate"], self.bank_key)["device_id"]
        self.token = buyer["access_token"]

    def post(self, route, body, token, expected=200):
        req = urllib.request.Request(self.url + "/api/v1/" + route, data=body, method="POST",
                                     headers={"Authorization": "Bearer " + token,
                                              "Content-Type": "application/cbor"})
        try:
            response = urllib.request.urlopen(req, context=self.client_tls, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            value = decode(response.read())
            self.assertEqual(response.status, expected, value)
            return value

    def enroll(self, role, key, expected=200):
        return self.post("enroll/" + role,
                         sign(message("enroll", environment="sandbox", role=role,
                                      public_key=public_bytes(key.public_key()), device_model="Test Android"), key),
                         self.bank.settings[role + "_code"], expected)

    def invoice(self, sequence=1, **changes):
        moment = now()
        fields = message("payment_request", environment="sandbox", terminal_id=self.terminal_id,
                         merchant_id="demo-merchant", transaction_id=uuid.uuid4().hex, order_id=uuid.uuid4().hex,
                         amount=19900, currency="RUB", nonce=os.urandom(32), created_at=moment,
                         expires_at=moment + 120, description="Покупка", terminal_sequence=sequence,
                         capabilities=["nfc-isodep/1"])
        fields.update(changes)
        return fields, sign(fields, self.terminal)

    def authorization(self, invoice, *, kind="payment_authorization", key=None, **changes):
        fields, raw = invoice
        values = {name: fields[name] for name in BINDINGS}
        values.update(changes)
        return sign(message(kind, environment="sandbox", phone_device_id=self.phone_id,
                            terminal_request=raw, **values), key or self.phone)

    def confirm(self, fields, result, state, *, result_hash=None, expected=200):
        ack = sign(message("payment_result_ack", environment="sandbox", terminal_id=self.terminal_id,
                           transaction_id=fields["transaction_id"], result_hash=result_hash or digest(result),
                           received_at=now(), terminal_state=state), self.terminal)
        relay = sign(message("relay_ack", environment="sandbox", phone_device_id=self.phone_id,
                             terminal_ack=ack), self.phone)
        return self.post(f'payments/{fields["transaction_id"]}/delivery-confirmation', relay, self.token, expected)

    def test_ble_l2cap_invoice_is_payable(self):
        invoice = self.invoice(capabilities=["ble-l2cap/1"])
        reply = self.post("payments", self.authorization(invoice), self.token)
        self.assertEqual(verify(reply["result"], self.bank_key)["status"], "PAID")

    def test_two_buyers_cannot_pay_the_same_invoice(self):
        other = ec.generate_private_key(ec.SECP256R1())
        registered = self.enroll("buyer", other)
        other_id = verify(registered["certificate"], self.bank_key)["device_id"]
        invoice = self.invoice()
        other_auth = sign(message("payment_authorization", environment="sandbox", phone_device_id=other_id,
            terminal_request=invoice[1], **{name: invoice[0][name] for name in BINDINGS}), other)
        def submit(value):
            raw, token = value
            try:
                return verify(self.bank.dispatch("/api/v1/payments", raw, token)["result"], self.bank_key)["status"]
            except ProtocolError as error:
                return str(error)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            outcomes = list(pool.map(submit, [(self.authorization(invoice), self.token),
                                             (other_auth, registered["access_token"])]))
        self.assertCountEqual(outcomes, ["PAID", "conflict"])
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 1)
            balances = db.execute("SELECT balance FROM accounts WHERE id <> 'demo-merchant'").fetchall()
            self.assertCountEqual([r["balance"] for r in balances], [480100, 500000])

    def test_v3_migration_preserves_legacy_registration_and_paid_ledger(self):
        self.post("payments", self.authorization(self.invoice()), self.token)
        with self.bank.connect() as db:
            original = db.execute("SELECT result FROM payments").fetchone()["result"]
            for row in db.execute("SELECT id,certificate FROM devices").fetchall():
                fields = verify(row["certificate"], self.bank_key)
                fields.pop("registration_number")
                legacy = sign(fields, self.bank.key)
                db.execute("UPDATE devices SET certificate=%s WHERE id=%s", (legacy, row["id"]))
            db.execute("ALTER TABLE devices DROP CONSTRAINT devices_account_role_check")
            db.execute("ALTER TABLE devices DROP COLUMN account_id CASCADE")
            db.execute("ALTER TABLE devices DROP COLUMN registration_number CASCADE")
            db.execute("ALTER TABLE devices DROP COLUMN token_nonce")
            db.execute("ALTER TABLE devices ADD CONSTRAINT devices_role_key UNIQUE(role)")
            db.execute("DELETE FROM schema_migrations WHERE version IN (3,4)")
        migrate(self.bank)
        with self.bank.connect() as db:
            buyer = db.execute("SELECT * FROM devices WHERE id=%s", (self.phone_id,)).fetchone()
            self.assertEqual(buyer["account_id"], "demo-buyer")
            self.assertGreater(buyer["registration_number"], 0)
            self.assertEqual(db.execute("SELECT balance FROM accounts WHERE id='demo-buyer'").fetchone()["balance"], 480100)
            self.assertEqual(db.execute("SELECT result FROM payments").fetchone()["result"], original)
        renewed = self.enroll("buyer", self.phone)
        self.assertEqual(verify(renewed["certificate"], self.bank_key)["device_id"], self.phone_id)
        self.assertEqual(renewed["access_token"], self.bank.token(self.phone_id, None))
        self.enroll("terminal", ec.generate_private_key(ec.SECP256R1()))

    def test_multiple_buyers_have_independent_accounts_and_stable_bank_numbers(self):
        other = ec.generate_private_key(ec.SECP256R1())
        enrolled = self.enroll("buyer", other)
        other_id = verify(enrolled["certificate"], self.bank_key)["device_id"]
        original = verify(self.enroll("buyer", self.phone)["certificate"], self.bank_key)
        renewed = verify(self.enroll("buyer", self.phone)["certificate"], self.bank_key)
        self.assertEqual(original["registration_number"], renewed["registration_number"])
        self.assertNotEqual(original["registration_number"], verify(enrolled["certificate"], self.bank_key)["registration_number"])
        def account(device, key, token):
            nonce = os.urandom(32)
            query = sign(message("account_query", environment="sandbox", phone_device_id=device,
                                 nonce=nonce, created_at=now()), key)
            fields = verify(self.post("account", query, token)["account"], self.bank_key)
            self.assertEqual(fields["device_id"], device)
            self.assertEqual(fields["request_nonce"], nonce)
            return fields["balance"]
        self.assertEqual(account(other_id, other, enrolled["access_token"]), 500000)
        invoice = self.invoice()
        self.post("payments", self.authorization(invoice), self.token)
        self.assertEqual(account(self.phone_id, self.phone, self.token), 480100)
        self.assertEqual(account(other_id, other, enrolled["access_token"]), 500000)
        nonce = os.urandom(32)
        forged = sign(message("account_query", environment="sandbox", phone_device_id=self.phone_id,
                              nonce=nonce, created_at=now()), other)
        self.post("account", forged, self.token, 400)

    def test_tls_rotation_keeps_pending_invoice_and_registration(self):
        invoice = self.invoice()
        original_bank_key = (self.state / "bank.key").read_bytes()
        original_tls_key = (self.state / "tls.key").read_bytes()
        issue(self.state, "192.168.42.10")
        apply(self.state)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = tls.maximum_version = ssl.TLSVersion.TLSv1_3
        tls.load_cert_chain(certificate_path(self.state), self.state / "tls.key")
        server = Server(("127.0.0.1", 0), Bank(self.state, self.dsn), tls)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        self.client_tls = ssl.create_default_context(cafile=str(certificate_path(self.state)))
        self.client_tls.minimum_version = self.client_tls.maximum_version = ssl.TLSVersion.TLSv1_3
        self.url = f"https://127.0.0.1:{server.server_port}"
        receipt = self.post("payments", self.authorization(invoice), self.token)["result"]
        self.assertEqual(verify(receipt, self.bank_key)["status"], "PAID")
        self.assertEqual(self.post("payments", self.authorization(invoice), self.token)["result"], receipt)
        self.assertEqual((self.state / "bank.key").read_bytes(), original_bank_key)
        self.assertEqual((self.state / "tls.key").read_bytes(), original_tls_key)
        self.confirm(invoice[0], receipt, "RECEIVED")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 1)

    def test_two_terminals_and_buyers_pay_independent_orders_concurrently(self):
        second_terminal = ec.generate_private_key(ec.SECP256R1())
        second_buyer = ec.generate_private_key(ec.SECP256R1())
        terminal_cert = verify(self.enroll("terminal", second_terminal)["certificate"], self.bank_key)
        original_cert = verify(self.enroll("terminal", self.terminal)["certificate"], self.bank_key)
        self.assertNotEqual(terminal_cert["registration_number"], original_cert["registration_number"])
        buyer_enrolled = self.enroll("buyer", second_buyer)
        buyer_id = verify(buyer_enrolled["certificate"], self.bank_key)["device_id"]

        first = self.invoice()
        # The sequence, order ID, nonce and transaction ID are scoped to a terminal.
        second = first[0] | {"terminal_id": terminal_cert["device_id"]}
        second_auth = sign(message("payment_authorization", environment="sandbox",
            phone_device_id=buyer_id, terminal_request=sign(second, second_terminal),
            **{name: second[name] for name in BINDINGS}), second_buyer)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            payments = [pool.submit(self.post, "payments", self.authorization(first), self.token),
                        pool.submit(self.post, "payments", second_auth, buyer_enrolled["access_token"])]
            for payment in payments:
                self.assertEqual(verify(payment.result()["result"], self.bank_key)["status"], "PAID")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 2)
            accounts = dict((row["id"], row["balance"]) for row in db.execute("SELECT * FROM accounts"))
            self.assertEqual(accounts, {"demo-buyer": 480100, f"buyer-{buyer_id}": 480100,
                                        "demo-merchant": 39800})

    def test_full_reset_restores_balances_and_requires_fresh_enrollment_and_orders(self):
        invoice = self.invoice()
        self.post("payments", self.authorization(invoice), self.token)
        self.enroll("buyer", ec.generate_private_key(ec.SECP256R1()))
        reset_sandbox(self.dsn)
        with self.bank.connect() as db:
            for table in ("devices", "payments", "ledger", "outbox"):
                count = db.execute(sql.SQL("SELECT count(*) AS n FROM {}").format(sql.Identifier(table)))
                self.assertEqual(count.fetchone()["n"], 0)
            self.assertEqual(db.execute("SELECT balance FROM accounts ORDER BY id").fetchall(),
                             [{"balance": 500000}, {"balance": 0}])
            cutoff = db.execute("SELECT reset_at FROM sandbox_state").fetchone()["reset_at"]
        self.post("payments", self.authorization(invoice), self.token, 401)
        self.enroll("terminal", self.terminal)
        enrolled = self.enroll("buyer", self.phone)
        new_token = enrolled["access_token"]
        self.assertNotEqual(new_token, self.token)
        self.post("payments", self.authorization(invoice), self.token, 401)
        expired = self.post("payments", self.authorization(invoice), new_token, 400)
        self.assertEqual(expired["error"], "request_expired")
        new_invoice = self.invoice(created_at=cutoff + 1, expires_at=cutoff + 121)
        result = self.post("payments", self.authorization(new_invoice), new_token)
        self.assertEqual(verify(result["result"], self.bank_key)["status"], "PAID")

    def test_reset_clients_preserves_balances_and_allows_new_device(self):
        reset_clients(self.dsn, "terminal")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT role FROM devices").fetchall(), [{"role": "buyer"}])
            self.assertEqual(db.execute("SELECT sum(balance) AS total FROM accounts").fetchone()["total"], 500000)
        self.enroll("terminal", ec.generate_private_key(ec.SECP256R1()))
        reset_clients(self.dsn, "all")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM devices").fetchone()["n"], 0)

    def test_reset_clients_rejects_settled_payment_without_erasing_history(self):
        self.post("payments", self.authorization(self.invoice()), self.token)
        with self.assertRaisesRegex(ValueError, "связаны с платежами"):
            reset_clients(self.dsn, "all")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM devices").fetchone()["n"], 2)
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 1)
            self.assertEqual(db.execute("SELECT balance FROM accounts WHERE id='demo-buyer'").fetchone()["balance"], 480100)

    def test_concurrent_retries_charge_once_and_return_same_result(self):
        invoice = self.invoice()
        preview = self.post("payments/preview", self.authorization(invoice, kind="payment_preview"), self.token)
        self.assertEqual(preview, {"amount": 19900, "settled": False, "merchant_id": "demo-merchant"})
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            receipts = list(pool.map(lambda _: self.post("payments", self.authorization(invoice), self.token)["result"],
                                     range(6)))
        self.assertTrue(all(receipt == receipts[0] for receipt in receipts))
        result = verify(receipts[0], self.bank_key)
        self.assertEqual(result["status"], "PAID")
        self.assertEqual(result["nonce"], invoice[0]["nonce"])
        self.assertEqual(result["phone_device_id"], self.phone_id)
        self.assertEqual(result["request_hash"], digest(invoice[1]))
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 1)
            self.assertEqual(db.execute("SELECT balance FROM accounts ORDER BY id").fetchall(),
                             [{"balance": 480100}, {"balance": 19900}])
            self.assertEqual(db.execute("SELECT event FROM outbox").fetchall(), [{"event": "PAID"}])
        self.confirm(invoice[0], receipts[0], "RECEIVED", result_hash=bytes(32), expected=400)
        self.assertEqual(self.confirm(invoice[0], receipts[0], "RECEIVED")["terminal_state"], "RECEIVED")
        self.assertEqual(self.confirm(invoice[0], receipts[0], "FULFILLED")["terminal_state"], "FULFILLED")
        self.assertEqual(self.confirm(invoice[0], receipts[0], "RECEIVED")["terminal_state"], "FULFILLED")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT event FROM outbox ORDER BY id").fetchall(),
                             [{"event": event} for event in ("PAID", "RECEIVED", "FULFILLED")])

    def test_rejects_forgery_expiry_and_replay(self):
        invoice = self.invoice()
        unknown_key = ec.generate_private_key(ec.SECP256R1())
        self.assertEqual(self.enroll("terminal", self.phone, 400)["error"], "enrollment_unavailable")
        self.post("payments", self.authorization(invoice), "a" * 64, 401)
        self.post("payments", self.authorization(invoice, key=unknown_key), self.token, 400)
        self.post("payments", self.authorization(invoice, amount=1), self.token, 400)
        bad = bytearray(invoice[1])
        bad[-1] ^= 1
        self.post("payments", self.authorization((invoice[0], bytes(bad))), self.token, 400)
        expired = self.invoice(created_at=now() - 200, expires_at=now() - 80)
        self.assertEqual(self.post("payments", self.authorization(expired), self.token, 400)["error"], "request_expired")
        self.post("payments", self.authorization(invoice), self.token)
        stale_sequence = self.invoice()
        self.assertEqual(self.post("payments", self.authorization(stale_sequence), self.token, 400)["error"],
                         "sequence_replayed")
        reused_nonce = self.invoice(sequence=2, nonce=invoice[0]["nonce"])
        self.post("payments", self.authorization(reused_nonce), self.token, 409)
        changed_request = self.invoice(sequence=2, transaction_id=invoice[0]["transaction_id"])
        self.post("payments", self.authorization(changed_request), self.token, 409)

    def test_expired_resolution_is_signed_final_and_never_debits(self):
        active = self.invoice()
        self.post("payments/resolve", self.authorization(active, kind="payment_resolution"), self.token, 400)
        expired = self.invoice(created_at=now() - 200, expires_at=now() - 80)
        response = self.post("payments/resolve", self.authorization(expired, kind="payment_resolution"), self.token)
        result = verify(response["result"], self.bank_key)
        permit = verify(response["permit"], self.bank_key)
        self.assertEqual(result["status"], "CANCELLED")
        self.assertEqual(permit["result_hash"], digest(response["result"]))
        self.assertEqual(permit["request_hash"], digest(expired[1]))
        replay = self.post("payments", self.authorization(expired), self.token)
        self.assertEqual(replay["result"], response["result"])
        self.confirm(expired[0], response["result"], "RECEIVED")
        self.confirm(expired[0], response["result"], "FULFILLED", expected=400)
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 0)
            self.assertEqual(db.execute("SELECT balance FROM accounts WHERE id='demo-buyer'").fetchone()["balance"], 500000)

    def test_late_recovery_keeps_original_receipt_and_refreshes_only_the_permit(self):
        invoice = self.invoice()
        first = self.post("payments", self.authorization(invoice), self.token)
        moment = now() + 2 * 86400
        with patch("bank.now", return_value=moment):
            recovered = self.post("payments/resolve", self.authorization(invoice, kind="payment_resolution"), self.token)
        self.assertEqual(first["result"], recovered["result"])
        permit = verify(recovered["permit"], self.bank_key)
        self.assertEqual(permit["issued_at"], moment)
        self.assertEqual(permit["expires_at"], moment + 300)
        self.assertEqual(permit["result_hash"], digest(first["result"]))
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 1)

    def test_certificate_renewal_preserves_device_identity(self):
        original = self.enroll("buyer", self.phone)
        with patch("bank.now", return_value=now() + 31 * 86400):
            renewed = self.enroll("buyer", self.phone)
        old = verify(original["certificate"], self.bank_key)
        new = verify(renewed["certificate"], self.bank_key)
        self.assertEqual(old["device_id"], new["device_id"])
        self.assertEqual(original["access_token"], renewed["access_token"])
        self.assertGreater(new["valid_to"], old["valid_to"])
        self.assertEqual(old["public_key"], new["public_key"])

    def test_schema_migration_preserves_existing_balances_and_payments(self):
        invoice = self.invoice()
        response = self.post("payments", self.authorization(invoice), self.token)
        with self.bank.connect() as db:
            db.execute("DELETE FROM schema_migrations WHERE version=2")
            db.execute("ALTER TABLE payments DROP CONSTRAINT payments_status_check")
            db.execute("ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK(status IN ('PAID','DECLINED'))")
            db.execute("ALTER TABLE outbox DROP CONSTRAINT outbox_event_check")
            db.execute("ALTER TABLE outbox ADD CONSTRAINT outbox_event_check CHECK(event IN ('PAID','DECLINED','RECEIVED','FULFILLED'))")
        migrate(self.bank)
        replay = self.post("payments", self.authorization(invoice), self.token)
        self.assertEqual(replay["result"], response["result"])
        expired = self.invoice(sequence=2, created_at=now() - 200, expires_at=now() - 80)
        cancelled = self.post("payments/resolve", self.authorization(expired, kind="payment_resolution"), self.token)
        self.assertEqual(verify(cancelled["result"], self.bank_key)["status"], "CANCELLED")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT balance FROM accounts WHERE id='demo-buyer'").fetchone()["balance"], 480100)

    def test_decline_and_tls(self):
        with self.bank.connect() as db:
            db.execute("UPDATE accounts SET balance=0 WHERE id='demo-buyer'")
        invoice = self.invoice()
        reply = self.post("payments", self.authorization(invoice), self.token)
        self.assertEqual(verify(reply["result"], self.bank_key)["status"], "DECLINED")
        self.confirm(invoice[0], reply["result"], "FULFILLED", expected=400)
        self.confirm(invoice[0], reply["result"], "RECEIVED")
        with self.bank.connect() as db:
            self.assertEqual(db.execute("SELECT count(*) AS n FROM ledger").fetchone()["n"], 0)
        tls12 = ssl.create_default_context()
        tls12.maximum_version = ssl.TLSVersion.TLSv1_2
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(self.url + "/health", context=tls12, timeout=4)
        with self.assertRaises(urllib.error.URLError):
            urllib.request.urlopen(self.url + "/health", context=ssl.create_default_context(), timeout=4)
        with urllib.request.urlopen(self.url + "/health", context=self.client_tls, timeout=4) as response:
            self.assertEqual(decode(response.read())["status"], "ok")


if __name__ == "__main__":
    unittest.main()
