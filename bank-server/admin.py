"""Local maintenance only; never exposed through the payment HTTP API."""
import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import tarfile
import tempfile
from datetime import datetime, timedelta, timezone

import psycopg
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from protocol import payload


def atomic_write(path, data):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix='.write-', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        Path(temporary).unlink(missing_ok=True)


def packed(value):
    return json.dumps(value, sort_keys=True).encode()


def checksum(data):
    return hashlib.sha256(data).hexdigest()


def certificate_path(state, settings=None):
    settings = settings or json.loads((state / 'settings.json').read_text())
    name = settings.get('tls_certificate', 'tls.crt')
    path = Path(name)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('Недопустимый путь TLS-сертификата')
    return state / path


def public_bytes(key):
    return key.public_key().public_bytes(serialization.Encoding.DER,
                                       serialization.PublicFormat.SubjectPublicKeyInfo)


def validate_certificate(state, cert, host):
    key = serialization.load_pem_private_key((state / 'tls.key').read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('Нужен исходный TLS-ключ P-256')
    actual = cert.public_key().public_bytes(serialization.Encoding.DER,
                                          serialization.PublicFormat.SubjectPublicKeyInfo)
    if actual != public_bytes(key):
        raise ValueError('Сертификат выпущен для другого TLS-ключа')
    key.public_key().verify(cert.signature, cert.tbs_certificate_bytes,
                            ec.ECDSA(cert.signature_hash_algorithm))
    now = datetime.now(timezone.utc)
    if not cert.not_valid_before_utc <= now < cert.not_valid_after_utc:
        raise ValueError('TLS-сертификат ещё не действует или истёк')
    addresses = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
    for address in (host, '127.0.0.1'):
        if ipaddress.IPv4Address(address) not in addresses.get_values_for_type(x509.IPAddress):
            raise ValueError('В сертификате отсутствует нужный IP')
    return cert


def issue(state, host):
    host = str(ipaddress.IPv4Address(host))
    original = (state / 'settings.json').read_bytes()
    key = serialization.load_pem_private_key((state / 'tls.key').read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('Нужен исходный TLS-ключ P-256')
    now = datetime.now(timezone.utc)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'oasis pay sandbox')])
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=30))
            .add_extension(x509.SubjectAlternativeName([
                x509.IPAddress(ipaddress.IPv4Address(address))
                for address in dict.fromkeys((host, '127.0.0.1'))]), False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), False)
            .sign(key, hashes.SHA256()))
    validate_certificate(state, cert, host)
    pending = dict(host=host, base=checksum(original),
                   certificate=cert.public_bytes(serialization.Encoding.PEM).decode())
    atomic_write(state / 'tls-pending.json', packed(pending))
    print('Сертификат подготовлен для', host, '; активный сертификат не изменён.')
    print('Новый TLS SHA-256:', cert.fingerprint(hashes.SHA256()).hex())


def apply(state):
    pending = json.loads((state / 'tls-pending.json').read_text())
    original = (state / 'settings.json').read_bytes()
    if checksum(original) != pending['base']:
        raise ValueError('Настройки изменились после выпуска. Повторите tls-issue.')
    host = str(ipaddress.IPv4Address(pending['host']))
    cert = validate_certificate(state, x509.load_pem_x509_certificate(
        pending['certificate'].encode()), host)
    pin = cert.fingerprint(hashes.SHA256()).hex()
    name = f'tls-certificates/{pin}.pem'
    atomic_write(state / name, cert.public_bytes(serialization.Encoding.PEM))
    settings = json.loads(original)
    settings.update(host=host, pin=pin, tls_certificate=name)
    updated = packed(settings)
    # The one atomic settings replacement switches host, pin and certificate together.
    atomic_write(state / 'tls-rollback.json', packed(dict(
        previous=original.decode(), applied=checksum(updated))))
    atomic_write(state / 'settings.json', updated)
    (state / 'tls-pending.json').unlink(missing_ok=True)
    print('TLS-сертификат применён для', host)


def rollback(state):
    record = json.loads((state / 'tls-rollback.json').read_text())
    current = (state / 'settings.json').read_bytes()
    if checksum(current) != record['applied']:
        raise ValueError('Настройки уже изменились; автоматический откат отменён')
    previous = json.loads(record['previous'])
    validate_certificate(state, x509.load_pem_x509_certificate(
        certificate_path(state, previous).read_bytes()), previous['host'])
    atomic_write(state / 'settings.json', record['previous'].encode())
    print('Восстановлены прежние TLS-настройки.')


def network_ready(state, host):
    settings = json.loads((state / 'settings.json').read_text())
    if settings['host'] != str(ipaddress.IPv4Address(host)):
        return False
    try:
        cert = validate_certificate(state, x509.load_pem_x509_certificate(
            certificate_path(state, settings).read_bytes()), host)
    except ValueError:
        return False
    return (cert.fingerprint(hashes.SHA256()).hex() == settings['pin'] and
            cert.not_valid_after_utc > datetime.now(timezone.utc) + timedelta(days=1))


def clients(dsn):
    with psycopg.connect(dsn, connect_timeout=4) as db:
        rows = db.execute('SELECT id, role, certificate, valid_to, revoked FROM devices ORDER BY role')
        count = 0
        for device, role, raw, valid_to, revoked in rows:
            fields = payload(bytes(raw))
            print(json.dumps(dict(id=device, role=role, registration_number=fields.get('registration_number'), model=fields.get('device_model'),
                                  valid_until=datetime.fromtimestamp(valid_to, timezone.utc).isoformat(),
                                  revoked=revoked), ensure_ascii=False))
            count += 1
        print('Зарегистрировано устройств:', count)


def reset_clients(dsn, role):
    with psycopg.connect(dsn, connect_timeout=4) as db:
        db.execute("SET LOCAL lock_timeout = '10s'")
        db.execute('LOCK TABLE devices, payments IN ACCESS EXCLUSIVE MODE')
        rows = db.execute('SELECT id, role FROM devices WHERE (%s = \'all\' OR role=%s)',
                          (role, role)).fetchall()
        ids = [row[0] for row in rows]
        referenced = db.execute('SELECT EXISTS (SELECT 1 FROM payments WHERE '
                                'terminal_id = ANY(%s) OR buyer_id = ANY(%s))', (ids, ids)).fetchone()[0]
        if referenced:
            raise ValueError('Сброс отменён: устройства связаны с платежами. '
                             'Для новой регистрации запустите отдельный тестовый банк.')
        db.execute('DELETE FROM devices WHERE id = ANY(%s)', (ids,))
        print('Удалено регистраций:', len(ids))


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, default=Path('/var/lib/oasis/keys'))
    sub = parser.add_subparsers(dest='action', required=True)
    for action in ('tls-issue', 'network-ready'):
        sub.add_parser(action).add_argument('--host', required=True)
    for action in ('tls-apply', 'tls-rollback', 'state-status', 'snapshot', 'clients'):
        sub.add_parser(action)
    sub.add_parser('clients-reset').add_argument('--role', choices=('all', 'buyer', 'terminal'), default='all')
    args = parser.parse_args()
    state = args.state
    state.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with (state.parent / '.maintenance.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            if args.action == 'state-status':
                if not state.exists():
                    return 3
                json.loads((state / 'settings.json').read_text())
                return 0
            if args.action == 'tls-issue':
                issue(state, args.host)
            elif args.action == 'tls-apply':
                apply(state)
            elif args.action == 'tls-rollback':
                rollback(state)
            elif args.action == 'network-ready':
                return 0 if network_ready(state, args.host) else 3
            elif args.action == 'snapshot':
                import sys
                with tarfile.open(fileobj=sys.stdout.buffer, mode='w|') as archive:
                    archive.add(state, arcname='keys')
            elif args.action == 'clients':
                clients(os.environ['OASIS_DATABASE_URL'])
            elif args.action == 'clients-reset':
                reset_clients(os.environ['OASIS_DATABASE_URL'], args.role)
        except (ValueError, OSError, KeyError, psycopg.Error) as error:
            parser.exit(1, f'Ошибка обслуживания: {error}\n')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
