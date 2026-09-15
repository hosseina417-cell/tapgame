# -*- coding: utf-8 -*-
"""بررسیِ کاملِ APK پیش از انتشار — شبیه‌سازیِ همان قواعدی که اندروید اجرا می‌کند.

چرا این ابزار وجود دارد؟
    یک APK می‌تواند از نظرِ ابزارهای معمولی (aapt2، androguard) سالم به نظر برسد
    ولی هنگامِ نصب روی گوشی با پیامِ «مشکل در تجزیهٔ بسته» رد شود. علت معمولاً
    امضای v1 (JAR) است که اندروید آن را حتی در حضورِ امضای معتبرِ v2 هم بررسی
    می‌کند (جلوگیری از حملهٔ حذفِ امضای قوی‌تر). این ابزار هر دو طرح را با همان
    قواعدِ AOSP می‌سنجد.

مواردِ بررسی:
    1. ساختارِ ZIP و هم‌ترازی با Central Directory
    2. امضای v1: پوششِ همهٔ ورودی‌ها، چکیدهٔ بخش‌های مانیفست، چکیدهٔ کلِ مانیفست،
       امضای PKCS#7
    3. امضای v2: محلِ بلوک، چکیدهٔ قطعه‌ایِ ۱ مگابایتی، امضای RSA، تطابقِ
       کلیدِ عمومی با گواهی

اجرا:
    python tools/verify_apk.py dist/cardefect.apk
"""
import base64
import hashlib
import os
import struct
import sys
import zipfile

MAGIC = b"APK Sig Block 42"
V2_BLOCK_ID = 0x7109871A
SIG_ALG_RSA_PKCS1_V1_5_SHA256 = 0x0103
CHUNK = 1024 * 1024

GREEN, RED, YELLOW = "\033[92m", "\033[91m", "\033[93m"
RESET = "\033[0m" if sys.stdout.isatty() else ""


def ok(msg):
    print("  %s✓%s %s" % (GREEN, RESET, msg))


def bad(msg):
    print("  %s✗ %s%s" % (RED, msg, RESET))
    return False


# ------------------------------------------------------------------ ZIP ----

def check_zip(path):
    print("[۱] ساختارِ ZIP")
    good = True
    z = zipfile.ZipFile(path)
    if z.testzip() is not None:
        good = bad("ورودیِ خراب در ZIP")
    else:
        ok("همهٔ ورودی‌ها سالم (%d ورودی)" % len(z.infolist()))
    names = [i.filename for i in z.infolist()]
    for need in ("AndroidManifest.xml", "classes.dex"):
        if need not in names:
            good = bad("فایلِ ضروری نیست: %s" % need)
    if good:
        ok("AndroidManifest.xml و classes.dex موجودند")

    data = open(path, "rb").read()
    eocd = data.rfind(b"PK\x05\x06")
    cd_off = struct.unpack_from("<I", data, eocd + 16)[0]
    cd_size = struct.unpack_from("<I", data, eocd + 12)[0]
    if cd_off + cd_size != eocd:
        good = bad("Central Directory با EOCD هم‌خوان نیست")
    else:
        ok("Central Directory و EOCD هم‌خوان‌اند (افست %d)" % cd_off)
    if data[cd_off - 16:cd_off] == MAGIC:
        ok("بلوکِ امضای APK درست قبل از Central Directory است")
    else:
        good = bad("بلوکِ امضای APK پیدا نشد")
    return good, cd_off


# ------------------------------------------------------------- امضای v1 ----

def split_manifest(raw):
    """تقسیمِ مانیفست به بلوکِ اصلی و بخش‌ها، دقیقاً به روشِ اندروید.

    اندروید چکیدهٔ هر بخش را روی بایت‌های همان بخش (تا ابتدای بخشِ بعدی،
    و برای بخشِ آخر تا پایانِ فایل) حساب می‌کند.
    """
    end = raw.find(b"\r\n\r\n")
    main = raw[:end + 4]
    rest = raw[end + 4:]
    sections = []
    while rest:
        j = rest.find(b"\r\n\r\n")
        if j < 0:
            sections.append(rest)
            break
        sections.append(rest[:j + 4])
        rest = rest[j + 4:]
    return main, sections


def section_name(sec):
    for line in sec.split(b"\r\n"):
        if line.lower().startswith(b"name: "):
            return line[6:].decode("utf-8")
    return None


def check_v1(path):
    print("[۲] امضای v1 (JAR)")
    good = True
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    if "META-INF/MANIFEST.MF" not in names:
        return bad("MANIFEST.MF ندارد")
    sf_names = [n for n in names if n.startswith("META-INF/") and n.endswith(".SF")]
    sig_names = [n for n in names if n.startswith("META-INF/")
                 and n.endswith((".RSA", ".DSA", ".EC"))]
    if not sf_names or not sig_names:
        return bad("فایل‌های .SF یا .RSA ندارد")
    sf_name = sf_names[0]
    if os.path.basename(sf_name)[:-3] != os.path.basename(sig_names[0])[:-4]:
        good = bad("نامِ فایل‌های SF و RSA هم‌خوان نیست")

    mf = z.read("META-INF/MANIFEST.MF")
    sf = z.read(sf_name)
    mf_main, mf_sections = split_manifest(mf)
    sf_main, sf_sections = split_manifest(sf)

    if not mf.endswith(b"\r\n\r\n"):
        good = bad("MANIFEST.MF با خط خالی پایان نیافته — چکیدهٔ بخشِ آخر غلط می‌شود")
    else:
        ok("MANIFEST.MF با خط خالی پایان یافته")

    # ۱) همهٔ ورودی‌ها (غیر از META-INF) باید در مانیفست باشند
    entries = set(n for n in z.namelist()
                  if not n.startswith("META-INF/") and not n.endswith("/"))
    listed = set()
    for sec in mf_sections:
        nm = section_name(sec)
        if nm:
            listed.add(nm)
    if entries != listed:
        good = bad("اختلاف بین ورودی‌های ZIP و مانیفست: %s" % (entries ^ listed))
    else:
        ok("همهٔ %d ورودی در مانیفست پوشش داده شده" % len(entries))

    # ۲) چکیدهٔ هر فایل در مانیفست
    for sec in mf_sections:
        nm = section_name(sec)
        if not nm:
            continue
        data = z.read(nm)
        want = None
        for line in sec.split(b"\r\n"):
            if line.lower().startswith(b"sha-256-digest: "):
                want = line[16:].decode()
        if want is None:
            good = bad("چکیده برای %s درج نشده" % nm)
            continue
        got = base64.b64encode(hashlib.sha256(data).digest()).decode()
        if want != got:
            good = bad("چکیدهٔ %s با محتوا هم‌خوان نیست" % nm)
    if good:
        ok("چکیدهٔ همهٔ فایل‌ها در MANIFEST.MF درست است")

    # ۳) چکیدهٔ بخش‌های مانیفست در CERT.SF
    sf_map = {}
    for sec in sf_sections:
        nm = section_name(sec)
        if not nm:
            continue
        for line in sec.split(b"\r\n"):
            if line.lower().startswith(b"sha-256-digest: "):
                sf_map[nm] = line[16:].decode()
    for sec in mf_sections:
        nm = section_name(sec)
        if nm not in sf_map:
            good = bad("بخشِ %s در CERT.SF نیست" % nm)
            continue
        got = base64.b64encode(hashlib.sha256(sec).digest()).decode()
        if sf_map[nm] != got:
            good = bad("چکیدهٔ بخشِ %s در CERT.SF غلط است" % nm)
    if good:
        ok("چکیدهٔ بخش‌های مانیفست در CERT.SF درست است")

    # ۴) چکیدهٔ کلِ مانیفست
    want = None
    for line in sf_main.split(b"\r\n"):
        if line.lower().startswith(b"sha-256-digest-manifest: "):
            want = line[25:].decode()
    if want is None:
        good = bad("SHA-256-Digest-Manifest در CERT.SF نیست")
    else:
        got = base64.b64encode(hashlib.sha256(mf).digest()).decode()
        if want != got:
            good = bad("SHA-256-Digest-Manifest غلط است")
        else:
            ok("SHA-256-Digest-Manifest درست است")

    # ۵) امضای PKCS#7 (با OpenSSL چون cryptography امضای detached را تأیید نمی‌کند)
    import shutil
    import subprocess
    import tempfile

    tmp = tempfile.mkdtemp(prefix="apkverify-")
    try:
        sf_path = os.path.join(tmp, "CERT.SF")
        rsa_path = os.path.join(tmp, "CERT.RSA")
        with open(sf_path, "wb") as fh:
            fh.write(sf)
        with open(rsa_path, "wb") as fh:
            fh.write(z.read(sig_names[0]))
        if shutil.which("openssl") is None:
            print("  %s!%s openssl در دسترس نیست — امضای PKCS#7 بررسی نشد" % (YELLOW, RESET))
            return good
        res = subprocess.run(
            ["openssl", "smime", "-verify", "-inform", "DER", "-in", rsa_path,
             "-content", sf_path, "-noverify", "-out", os.devnull],
            capture_output=True, text=True)
        if res.returncode == 0 and "Verification successful" in res.stderr:
            ok("امضای PKCS#7 روی CERT.SF معتبر است (OpenSSL)")
        else:
            good = bad("امضای PKCS#7 نامعتبر: %s" % (res.stderr.strip() or res.stdout.strip()))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return good


# ------------------------------------------------------------- امضای v2 ----

def chunked_digest(parts):
    digests = []
    for part in parts:
        for off in range(0, len(part), CHUNK):
            chunk = part[off:off + CHUNK]
            digests.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(chunk))
                                          + chunk).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(digests))
                          + b"".join(digests)).digest()


def read_lp32(buf, pos):
    (n,) = struct.unpack_from("<I", buf, pos)
    return buf[pos + 4:pos + 4 + n], pos + 4 + n


def check_v2(path, cd_off):
    print("[۳] امضای v2 (APK Signature Scheme v2)")
    data = open(path, "rb").read()
    total = None
    # خواندنِ اندازهٔ بلوک از «پانوشتِ» ۲۴ بایتیِ قبل از Central Directory
    footer = data[cd_off - 24:cd_off]
    if footer[8:] != MAGIC:
        return bad("جادوِ بلوکِ امضا یافت نشد")
    (size_in_footer,) = struct.unpack_from("<Q", footer, 0)
    total = int(size_in_footer) + 8
    sb_off = cd_off - total
    if data[sb_off:sb_off + 8] != struct.pack("<Q", size_in_footer):
        return bad("دو مقدارِ اندازهٔ بلوک یکی نیستند")
    ok("بلوکِ امضا: %d بایت در افست %d" % (total, sb_off))

    # یافتنِ مقدارِ بلوکِ v2 در میانِ جفت‌های ID/مقدار
    body = data[sb_off + 8:sb_off + 8 + int(size_in_footer) - 24]
    pos, value = 0, None
    while pos < len(body):
        (plen,) = struct.unpack_from("<Q", body, pos)
        (pid,) = struct.unpack_from("<I", body, pos + 8)
        if pid == V2_BLOCK_ID:
            value = body[pos + 12:pos + 8 + plen]
        pos += 8 + plen
    if value is None:
        return bad("بلوکِ v2 (0x7109871a) پیدا نشد")
    ok("بلوکِ v2 یافت شد")

    # --- چکیده ---
    eocd = data.rfind(b"PK\x05\x06")
    cd_size = struct.unpack_from("<I", data, eocd + 12)[0]
    eocd_bytes = bytearray(data[eocd:])
    struct.pack_into("<I", eocd_bytes, 16, sb_off)
    digest = chunked_digest([data[:sb_off],
                             data[cd_off:cd_off + cd_size],
                             bytes(eocd_bytes)])

    signers, _ = read_lp32(value, 0)
    pos = 0
    verified = False
    while pos < len(signers):
        signer, pos = read_lp32(signers, pos)
        signed_data, p = read_lp32(signer, 0)
        sigs, p = read_lp32(signer, p)
        pubkey, p = read_lp32(signer, p)

        digests, q = read_lp32(signed_data, 0)
        certs, q = read_lp32(signed_data, q)
        attrs, q = read_lp32(signed_data, q)

        # چکیده
        stored, r = read_lp32(digests, 0)
        alg = struct.unpack_from("<I", stored, 0)[0]
        stored_digest = stored[8:]
        if alg != SIG_ALG_RSA_PKCS1_V1_5_SHA256:
            return bad("الگوریتمِ پشتیبانی‌نشده: 0x%04x" % alg)
        if stored_digest != digest:
            return bad("چکیدهٔ APK با امضا هم‌خوان نیست (محتوا دست‌خورده؟)")
        ok("چکیدهٔ قطعه‌ایِ APK با مقدارِ امضاشده یکی است")

        # گواهی و کلیدِ عمومی
        cert_der, _ = read_lp32(certs, 0)
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

        cert = x509.load_der_x509_certificate(cert_der)
        spki = cert.public_key().public_bytes(Encoding.DER,
                                              PublicFormat.SubjectPublicKeyInfo)
        if spki != pubkey:
            return bad("کلیدِ عمومی با گواهی یکی نیست")
        ok("کلیدِ عمومی با گواهی مطابقت دارد")

        # امضا
        sig_entry, _ = read_lp32(sigs, 0)
        sig_alg = struct.unpack_from("<I", sig_entry, 0)[0]
        sig_bytes = sig_entry[8:]
        try:
            cert.public_key().verify(sig_bytes, signed_data,
                                     padding.PKCS1v15(), hashes.SHA256())
            ok("امضای RSA روی signed-data معتبر است (alg=0x%04x)" % sig_alg)
            verified = True
        except Exception as exc:                               # noqa: BLE001
            return bad("امضا نامعتبر: %s" % exc)
        ok("گواهی: %s (RSA %d بیت، معتبر تا %s)"
           % (cert.subject.rfc4514_string(), cert.public_key().key_size,
              cert.not_valid_after.date()))
        break
    if not verified:
        return bad("هیچ امضاکننده‌ای تأیید نشد")
    return True


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "dist/cardefect.apk"
    print("بررسیِ %s (%d بایت)\n" % (path, os.path.getsize(path)))
    good, cd_off = check_zip(path)
    good = check_v1(path) and good
    good = check_v2(path, cd_off) and good
    print()
    if good:
        print("%s✅ همهٔ بررسی‌ها موفق — APK آمادهٔ نصب است%s" % (GREEN, RESET))
        return 0
    print("%s❌ بررسی ناموفق — روی گوشی نصب نخواهد شد%s" % (RED, RESET))
    return 1


if __name__ == "__main__":
    sys.exit(main())
