"""Wire profile for offline-relay/1."""
import hashlib
import io

import cbor2
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, utils

VERSION = "offline-relay/1"
LIMIT = 16_384
P256_ORDER = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551


class ProtocolError(ValueError):
    pass


def require(condition, code="invalid_message"):
    if not condition:
        raise ProtocolError(code)


def digest(data):
    return hashlib.sha256(data).digest()


def encode(value):
    return cbor2.dumps(value, canonical=True)


def validate(value, depth=0):
    require(depth <= 8)
    kind = type(value)
    if kind is int:
        require(-(2**63) <= value < 2**63)
    elif kind is list:
        require(len(value) <= 64)
        for item in value:
            validate(item, depth + 1)
    elif kind is dict:
        require(len(value) <= 32)
        for key, item in value.items():
            require(type(key) in (str, int))
            validate(key, depth + 1)
            validate(item, depth + 1)
    else:
        require(kind in (bool, bytes, str))


def decode(data):
    require(isinstance(data, bytes) and 0 < len(data) <= LIMIT)
    try:
        stream = io.BytesIO(data)
        value = cbor2.CBORDecoder(stream, max_depth=8).decode()
        validate(value)
        require(stream.tell() == len(data) and encode(value) == data)
        return value
    except (cbor2.CBORDecodeError, ValueError, TypeError, RecursionError, OverflowError) as exc:
        raise ProtocolError("invalid_cbor") from exc


def public_bytes(key):
    return key.public_bytes(serialization.Encoding.DER,
                            serialization.PublicFormat.SubjectPublicKeyInfo)


def public_key(raw):
    require(isinstance(raw, bytes) and len(raw) == 91)
    key = serialization.load_der_public_key(raw)
    require(isinstance(key, ec.EllipticCurvePublicKey) and isinstance(key.curve, ec.SECP256R1))
    require(public_bytes(key) == raw)
    return key


def protected(key):
    return encode({1: -7, 4: digest(public_bytes(key))[:8]})


def sign(message, key):
    headers = protected(key.public_key())
    payload = encode(message)
    der = key.sign(encode(["Signature1", headers, b"", payload]), ec.ECDSA(hashes.SHA256()))
    r, s = utils.decode_dss_signature(der)
    s = min(s, P256_ORDER - s)
    return encode([headers, {}, payload, r.to_bytes(32, "big") + s.to_bytes(32, "big")])


def envelope(raw):
    value = decode(raw)
    require(isinstance(value, list) and len(value) == 4)
    headers, unprotected, payload, signature = value
    require(unprotected == {} and isinstance(headers, bytes) and isinstance(payload, bytes)
            and isinstance(signature, bytes) and len(signature) == 64)
    return headers, payload, signature


def payload(raw):
    value = decode(envelope(raw)[1])
    require(isinstance(value, dict) and value.get("protocol_version") == VERSION)
    return value


def verify(raw, key):
    headers, data, signature = envelope(raw)
    require(headers == protected(key), "invalid_signature")
    r, s = int.from_bytes(signature[:32], "big"), int.from_bytes(signature[32:], "big")
    require(0 < r < P256_ORDER and 0 < s <= P256_ORDER // 2, "invalid_signature")
    der = utils.encode_dss_signature(r, s)
    try:
        key.verify(der, encode(["Signature1", headers, b"", data]), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as exc:
        raise ProtocolError("invalid_signature") from exc
    return payload(raw)


def message(kind, **fields):
    return dict(type=kind, protocol_version=VERSION, **fields)
