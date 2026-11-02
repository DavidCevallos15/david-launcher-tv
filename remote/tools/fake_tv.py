#!/usr/bin/env python3
"""
TV Android simulado para probar el cliente Kotlin sin un TV físico.

Implementa el lado servidor de Android TV Remote v2 usando los mensajes protobuf
oficiales (vía androidtvremote2), así cualquier error de wire format del cliente
lo detecta el parser real de Google, no uno escrito por nosotros.

  pip install androidtvremote2
  python3 remote/tools/fake_tv.py --out /tmp/fake-tv

Genera en --out: client.p12 (identidad emparejada), stranger.p12 (no emparejada),
server.sha256 (cert del TV) y, durante el pairing, pin.txt (lo que "se ve en pantalla").
Al terminar escribe result.txt con OK o la lista de fallos.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import os
import secrets
import socket
import ssl
import sys
import threading

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12
from cryptography.x509.oid import NameOID
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes

from androidtvremote2 import polo_pb2, remotemessage_pb2 as rm

FAILURES: list[str] = []
LOG: list[str] = []


def check(cond: bool, what: str) -> None:
    (LOG if cond else FAILURES).append(("✓ " if cond else "✗ ") + what)


def make_identity(cn: str):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, cn)])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(days=1)).not_valid_after(now + dt.timedelta(days=365))
            .sign(key, hashes.SHA256()))
    return key, cert


def write_pem(path, key, cert):
    with open(path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
        f.write(cert.public_bytes(serialization.Encoding.PEM))


def write_p12(path, key, cert):
    with open(path, "wb") as f:
        f.write(pkcs12.serialize_key_and_certificates(
            b"client", key, cert, None, serialization.BestAvailableEncryption(b"test")))


class Framed:
    def __init__(self, sock: ssl.SSLSocket):
        self.sock = sock
        self.buf = b""

    def recv(self, msg_type):
        while True:
            try:
                ln, pos = _DecodeVarint(self.buf, 0)
                if len(self.buf) >= pos + ln:
                    raw, self.buf = self.buf[pos:pos + ln], self.buf[pos + ln:]
                    m = msg_type()
                    m.ParseFromString(raw)  # parser oficial: falla si el wire format está mal
                    return m
            except IndexError:
                pass
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("cliente cerró")
            self.buf += chunk

    def send(self, msg):
        data = msg.SerializeToString()
        self.sock.sendall(_VarintBytes(len(data)) + data)


def mod_exp(cert):
    n = cert.public_key().public_numbers()
    return n.n, n.e


def tls_server(port, server_pem, trusted_client_pem):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(server_pem)
    ctx.verify_mode = ssl.CERT_REQUIRED          # como el TV real: exige cert de cliente…
    ctx.load_verify_locations(trusted_client_pem)  # …y solo acepta el emparejado
    srv = socket.socket()
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen()
    return srv, ctx


def outer(**kw):
    m = polo_pb2.OuterMessage(protocol_version=2, status=polo_pb2.OuterMessage.Status.STATUS_OK)
    for k, v in kw.items():
        getattr(m, k).CopyFrom(v)
    return m


def run_pairing(conn: ssl.SSLSocket, server_cert, out_dir):
    f = Framed(conn)
    m = f.recv(polo_pb2.OuterMessage)
    check(m.protocol_version == 2 and m.status == 200, "pairing: protocol_version=2, status=200")
    check(m.HasField("pairing_request") and m.pairing_request.service_name == "atvremote",
          "pairing: pairing_request.service_name")
    check(bool(m.pairing_request.client_name), f"pairing: client_name='{m.pairing_request.client_name}'")
    f.send(outer(pairing_request_ack=polo_pb2.PairingRequestAck(server_name="BeyondTV2")))

    m = f.recv(polo_pb2.OuterMessage)
    enc = m.options.input_encodings[0] if m.options.input_encodings else None
    check(m.HasField("options") and enc and enc.type == 3 and enc.symbol_length == 6
          and m.options.preferred_role == 1, "pairing: options HEX/6/ROLE_INPUT")
    f.send(outer(options=m.options))

    m = f.recv(polo_pb2.OuterMessage)
    check(m.HasField("configuration") and m.configuration.encoding.type == 3
          and m.configuration.client_role == 1, "pairing: configuration")
    f.send(outer(configuration_ack=polo_pb2.ConfigurationAck()))

    # El TV elige 2 bytes aleatorios y muestra PIN = hash[0] + esos bytes
    client_cert = x509.load_der_x509_certificate(conn.getpeercert(binary_form=True))
    cm, ce = mod_exp(client_cert)
    sm, se = mod_exp(server_cert)
    suffix = secrets.token_hex(2).upper()
    h = hashlib.sha256()
    h.update(bytes.fromhex(f"{cm:X}"))
    h.update(bytes.fromhex(f"0{ce:X}"))
    h.update(bytes.fromhex(f"{sm:X}"))
    h.update(bytes.fromhex(f"0{se:X}"))
    h.update(bytes.fromhex(suffix))
    expected = h.digest()
    pin = f"{expected[0]:02X}{suffix}"
    with open(os.path.join(out_dir, "pin.txt"), "w") as fp:
        fp.write(pin)

    m = f.recv(polo_pb2.OuterMessage)
    check(m.HasField("secret") and m.secret.secret == expected, "pairing: secret correcto (SHA-256 de certs + PIN)")
    f.send(outer(secret_ack=polo_pb2.SecretAck(secret=m.secret.secret)))


def run_remote(conn: ssl.SSLSocket):
    f = Framed(conn)
    tv_features = 2**0 | 2**1 | 2**2 | 2**3 | 2**5 | 2**6 | 2**9
    f.send(rm.RemoteMessage(remote_configure=rm.RemoteConfigure(
        code1=tv_features, device_info=rm.RemoteDeviceInfo(model="BeyondTV2", vendor="TCL"))))
    m = f.recv(rm.RemoteMessage)
    check(m.HasField("remote_configure") and m.remote_configure.code1 == 623,
          f"remote: configure features={m.remote_configure.code1}")
    check(m.remote_configure.device_info.package_name == "atvremote", "remote: device_info")

    f.send(rm.RemoteMessage(remote_set_active=rm.RemoteSetActive()))
    m = f.recv(rm.RemoteMessage)
    check(m.HasField("remote_set_active") and m.remote_set_active.active == 623, "remote: set_active")

    f.send(rm.RemoteMessage(remote_start=rm.RemoteStart(started=True)))
    f.send(rm.RemoteMessage(remote_ping_request=rm.RemotePingRequest(val1=7)))
    m = f.recv(rm.RemoteMessage)
    check(m.HasField("remote_ping_response") and m.remote_ping_response.val1 == 7, "remote: ping/pong")

    # El TV avisa que hay un campo de texto enfocado
    f.send(rm.RemoteMessage(remote_ime_batch_edit=rm.RemoteImeBatchEdit(ime_counter=5, field_counter=9)))
    f.send(rm.RemoteMessage(remote_set_volume_level=rm.RemoteSetVolumeLevel(volume_level=12, volume_max=100)))

    expected_keys = [(19, 3), (23, 3), (4, 3), (24, 3), (23, 1), (23, 2)]
    for code, direction in expected_keys:
        m = f.recv(rm.RemoteMessage)
        ok = m.HasField("remote_key_inject") and m.remote_key_inject.key_code == code \
            and m.remote_key_inject.direction == direction
        check(ok, f"remote: key {code}/{direction} → recibido {m.remote_key_inject.key_code}/{m.remote_key_inject.direction}")

    m = f.recv(rm.RemoteMessage)
    be = m.remote_ime_batch_edit
    ok = m.HasField("remote_ime_batch_edit") and be.ime_counter == 5 and be.field_counter == 9 \
        and be.edit_info[0].insert == 1 and be.edit_info[0].text_field_status.value == "Canción ñandú 🎬" \
        and be.edit_info[0].text_field_status.start == len("Canción ñandú 🎬".encode("utf-16-le")) // 2 - 1  # índices UTF-16, como String de Java en el TV
    check(ok, f"remote: texto Unicode vía IME → '{be.edit_info[0].text_field_status.value if be.edit_info else ''}'")

    m = f.recv(rm.RemoteMessage)
    check(m.remote_app_link_launch_request.app_link == "market://launch?id=com.netflix.ninja",
          f"remote: app link → {m.remote_app_link_launch_request.app_link}")

    # Voz: el cliente presiona SEARCH; el TV abre sesión
    m = f.recv(rm.RemoteMessage)
    check(m.remote_key_inject.key_code == 84, "voz: KEYCODE_SEARCH")
    f.send(rm.RemoteMessage(remote_voice_begin=rm.RemoteVoiceBegin(session_id=42)))
    m = f.recv(rm.RemoteMessage)
    check(m.HasField("remote_voice_begin") and m.remote_voice_begin.session_id == 42, "voz: voice_begin eco")
    total = 0
    while True:
        m = f.recv(rm.RemoteMessage)
        if m.HasField("remote_voice_payload"):
            p = m.remote_voice_payload
            check(p.session_id == 42 and 8192 <= len(p.samples) <= 20480,
                  f"voz: payload {len(p.samples)} bytes")
            total += len(p.samples)
        else:
            check(m.HasField("remote_voice_end") and m.remote_voice_end.session_id == 42,
                  f"voz: voice_end tras {total} bytes")
            break


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--pair-port", type=int, default=16467)
    ap.add_argument("--remote-port", type=int, default=16466)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)

    skey, scert = make_identity("BeyondTV2")
    ckey, ccert = make_identity("atvremote")
    xkey, xcert = make_identity("intruso")
    server_pem, client_pem = os.path.join(a.out, "server.pem"), os.path.join(a.out, "client.pem")
    write_pem(server_pem, skey, scert)
    write_pem(client_pem, ckey, ccert)
    write_p12(os.path.join(a.out, "client.p12"), ckey, ccert)
    write_p12(os.path.join(a.out, "stranger.p12"), xkey, xcert)
    with open(os.path.join(a.out, "server.sha256"), "w") as fp:
        fp.write(hashlib.sha256(scert.public_bytes(serialization.Encoding.DER)).hexdigest())

    pair_srv, pair_ctx = tls_server(a.pair_port, server_pem, client_pem)
    rem_srv, rem_ctx = tls_server(a.remote_port, server_pem, client_pem)
    with open(os.path.join(a.out, "ready"), "w") as fp:
        fp.write("1")
    print("fake TV listo", flush=True)

    def serve_pairing():
        s, _ = pair_srv.accept()
        with pair_ctx.wrap_socket(s, server_side=True) as c:
            run_pairing(c, scert, a.out)

    t = threading.Thread(target=serve_pairing)
    t.start()
    t.join(timeout=60)

    # Remote: el primer intento viene del intruso (debe fallar el TLS); el segundo, del emparejado
    while True:
        s, _ = rem_srv.accept()
        try:
            c = rem_ctx.wrap_socket(s, server_side=True)
        except ssl.SSLError:
            LOG.append("✓ remote: TLS rechaza celular no emparejado")
            continue
        with c:
            run_remote(c)
        break

    result = "OK\n" if not FAILURES else "FAIL\n" + "\n".join(FAILURES) + "\n"
    with open(os.path.join(a.out, "result.txt"), "w") as fp:
        fp.write(result + "\n".join(LOG) + "\n")
    print(result + "\n".join(LOG + FAILURES))
    sys.exit(0 if not FAILURES else 1)


if __name__ == "__main__":
    main()
