import json
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import patch

from cryptography import x509
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from admin import apply, atomic_write, certificate_path, issue, network_ready, rollback


class TlsMaintenanceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name) / 'keys'
        self.state.mkdir()
        key = ec.generate_private_key(ec.SECP256R1())
        (self.state / 'tls.key').write_bytes(key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        (self.state / 'bank.key').write_bytes(b'unchanged-bank-key')
        (self.state / 'settings.json').write_text(json.dumps(dict(
            host='127.0.0.1', pin='initial', terminal_code='terminal', buyer_code='buyer', secret='secret')))
        issue(self.state, '127.0.0.1')
        apply(self.state)

    def test_issue_does_not_activate_and_apply_preserves_identity_and_codes(self):
        before = (self.state / 'settings.json').read_bytes()
        old_key = (self.state / 'tls.key').read_bytes()
        issue(self.state, '192.168.42.10')
        self.assertEqual((self.state / 'settings.json').read_bytes(), before)
        apply(self.state)
        updated = json.loads((self.state / 'settings.json').read_text())
        for field in ('terminal_code', 'buyer_code', 'secret'):
            self.assertEqual(updated[field], json.loads(before)[field])
        self.assertEqual((self.state / 'tls.key').read_bytes(), old_key)
        self.assertEqual((self.state / 'bank.key').read_bytes(), b'unchanged-bank-key')
        self.assertTrue(network_ready(self.state, '192.168.42.10'))
        self.assertFalse(network_ready(self.state, '192.168.42.11'))
        # Server and health client can both load the selected certificate, with loopback SAN.
        server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        server.load_cert_chain(certificate_path(self.state), self.state / 'tls.key')
        ssl.create_default_context(cafile=str(certificate_path(self.state)))
        rollback(self.state)
        self.assertEqual((self.state / 'settings.json').read_bytes(), before)

    def test_stale_candidate_is_rejected_without_switch(self):
        issue(self.state, '192.168.42.10')
        settings = json.loads((self.state / 'settings.json').read_text())
        settings['buyer_code'] = 'changed'
        atomic_write(self.state / 'settings.json', json.dumps(settings).encode())
        before = (self.state / 'settings.json').read_bytes()
        with self.assertRaisesRegex(ValueError, 'изменились'):
            apply(self.state)
        self.assertEqual((self.state / 'settings.json').read_bytes(), before)

    def test_invalid_ip_and_wrong_key_do_not_switch(self):
        before = (self.state / 'settings.json').read_bytes()
        with self.assertRaises(ValueError):
            issue(self.state, 'bad-address')
        issue(self.state, '192.168.42.10')
        other = ec.generate_private_key(ec.SECP256R1())
        (self.state / 'tls.key').write_bytes(other.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()))
        with self.assertRaisesRegex(ValueError, 'другого'):
            apply(self.state)
        self.assertEqual((self.state / 'settings.json').read_bytes(), before)

    def test_failure_before_atomic_settings_switch_keeps_original(self):
        issue(self.state, '192.168.42.10')
        before = (self.state / 'settings.json').read_bytes()
        real_write = atomic_write
        def fail_settings(path, data):
            if path.name == 'settings.json':
                raise OSError('simulated storage failure')
            real_write(path, data)
        with patch('admin.atomic_write', side_effect=fail_settings):
            with self.assertRaises(OSError):
                apply(self.state)
        self.assertEqual((self.state / 'settings.json').read_bytes(), before)
        self.assertTrue(network_ready(self.state, '127.0.0.1'))
