# -*- coding: utf-8 -*-
"""ساخت APK از وب‌اپ + Activity بومی، بدون نیاز به Android SDK یا Gradle.

مراحل:
  1. ساخت classes.dex (WebView Activity)
  2. ساخت AndroidManifest.xml باینری
  3. بسته‌بندیِ assets/www داخل ZIP
  4. امضای v1 (JAR signing) با کلید تولیدشده در لحظه
  5. هم‌ترازسازی (zipalign)

وابستگی‌ها: cryptography (pip)، که فقط برای امضا لازم است.
"""
import base64
import datetime
import hashlib
import io
import os
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from axmlgen import build_manifest   # noqa: E402
from make_dex import build_dex       # noqa: E402

PACKAGE = "ir.cardefect"
APP_LABEL = "دفترچه ایرادات خودرو"
VERSION_NAME = "1.0"
VERSION_CODE = 1
MIN_SDK = 24
TARGET_SDK = 33


def zip_entry_digest(data, algo="sha1"):
    return base64.b64encode(hashlib.new(algo, data).digest()).decode("ascii")


def build_unsigned_apk(www_dir, out_path):
    """ساخت APK امضا‌نشده"""
    manifest = build_manifest(PACKAGE, VERSION_CODE, VERSION_NAME, MIN_SDK,
                              TARGET_SDK, APP_LABEL, PACKAGE + ".MainActivity")
    dex = build_dex()

    files = [("AndroidManifest.xml", manifest), ("classes.dex", dex)]
    assets = []
    for dirpath, _dirnames, filenames in os.walk(www_dir):
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, www_dir).replace(os.sep, "/")
            assets.append((rel, full))
    assets.sort()

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for name, data in files:
            zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            zf.writestr(zi, data)
        for rel, full in assets:
            zi = zipfile.ZipInfo("assets/www/" + rel, date_time=(1980, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                zf.writestr(zi, fh.read())
    with open(out_path, "wb") as fh:
        fh.write(buf.getvalue())
    return out_path


def sign_v1(apk_path, out_path):
    """امضای JAR (طرحِ v1) با کلید تازه‌ساخته‌شده"""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.hazmat.primitives.serialization.pkcs7 import (
        PKCS7Options, PKCS7SignatureBuilder,
    )
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([
        x509.NameAttribute(NameOID.COUNTRY_NAME, "IR"),
        x509.NameAttribute(NameOID.ORGANIZATIONAL_UNIT_NAME, "CarDefect"),
        x509.NameAttribute(NameOID.COMMON_NAME, "Car Defect Log"),
    ])
    now = datetime.datetime.utcnow()
    cert = (x509.CertificateBuilder()
            .subject_name(subject).issuer_name(subject)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=365 * 25))
            .sign(key, hashes.SHA256()))

    with open(apk_path, "rb") as fh:
        raw = fh.read()
    zin = zipfile.ZipFile(io.BytesIO(raw))
    entries = [n for n in zin.namelist()
               if not n.startswith("META-INF/") and not n.endswith("/")]
    zin.close()

    manifest_lines = ["Manifest-Version: 1.0",
                      "Created-By: cardefect-build (python)",
                      ""]
    for name in sorted(entries):
        data = zipfile.ZipFile(io.BytesIO(raw)).read(name)
        manifest_lines.append("Name: %s" % name)
        manifest_lines.append("SHA1-Digest: %s" % zip_entry_digest(data, "sha1"))
        manifest_lines.append("")
    manifest_bytes = ("\r\n".join(manifest_lines)).encode("utf-8")

    sf_lines = ["Signature-Version: 1.0",
                "Created-By: cardefect-build (python)",
                "SHA1-Digest-Manifest: %s" % zip_entry_digest(manifest_bytes, "sha1"),
                ""]
    # بازسازیِ بخش‌های مانیفست برای محاسبهٔ درستِ چکیده
    for name in sorted(entries):
        section = ("Name: %s\r\nSHA1-Digest: %s\r\n\r\n"
                   % (name, zip_entry_digest(
                       zipfile.ZipFile(io.BytesIO(raw)).read(name), "sha1")))
        sf_lines.append("Name: %s" % name)
        sf_lines.append("SHA1-Digest: %s" % zip_entry_digest(section.encode("utf-8"), "sha1"))
        sf_lines.append("")
    sf_bytes = ("\r\n".join(sf_lines)).encode("utf-8")

    signature = (PKCS7SignatureBuilder()
                 .set_data(sf_bytes)
                 .add_signer(cert, key, hashes.SHA256())
                 .sign(serialization.Encoding.DER, [PKCS7Options.DetachedSignature]))

    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(raw), "r") as zin:
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
            for name in ("META-INF/MANIFEST.MF", "META-INF/CERT.SF", "META-INF/CERT.RSA"):
                pass
            zout.writestr(_zipinfo("META-INF/MANIFEST.MF"), manifest_bytes)
            zout.writestr(_zipinfo("META-INF/CERT.SF"), sf_bytes)
            zout.writestr(_zipinfo("META-INF/CERT.RSA"), signature)
            for item in zin.infolist():
                if item.filename.startswith("META-INF/"):
                    continue
                zout.writestr(item, zin.read(item.filename))
    with open(out_path, "wb") as fh:
        fh.write(out.getvalue())

    # کلید را هم کنار APK ذخیره می‌کنیم تا امضاها در آینده یکسان بمانند
    keystore = out_path + ".pem"
    with open(keystore, "wb") as fh:
        fh.write(key.private_bytes(serialization.Encoding.PEM,
                                   serialization.PrivateFormat.PKCS8,
                                   serialization.NoEncryption()))
        fh.write(cert.public_bytes(serialization.Encoding.PEM))
    return out_path


def _zipinfo(name):
    zi = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    return zi


def zipalign(src, dst, alignment=4):
    """هم‌ترازسازیِ داده‌های فشرده‌نشده روی مرزِ ۴ بایت"""
    zin = zipfile.ZipFile(src)
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.compress_type == zipfile.ZIP_STORED:
                pad = (-len(data)) % alignment
                if pad:
                    data += b"\x00" * pad
            zout.writestr(item, data)
    with open(dst, "wb") as fh:
        fh.write(out.getvalue())


def build(www_dir, out_path):
    unsigned = os.path.join(os.path.dirname(out_path), "unsigned.apk")
    signed = os.path.join(os.path.dirname(out_path), "signed.apk")
    build_unsigned_apk(www_dir, unsigned)
    sign_v1(unsigned, signed)
    zipalign(signed, out_path)
    size = os.path.getsize(out_path)
    print("APK ساخته شد: %s (%.1f KB)" % (out_path, size / 1024.0))
    return out_path


if __name__ == "__main__":
    www = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "www")
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "dist", "cardefect.apk")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    build(www, out)
