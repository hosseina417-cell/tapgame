# -*- coding: utf-8 -*-
"""امضای APK Signature Scheme v2 (طرحِ امضای v2) در پایتون خالص.

اندروید ۱۱ (API 30) به بالا نصبِ برنامه‌ای را که targetSdk آن ۳۰+ است و فقط
امضای v1 (JAR) دارد رد می‌کند؛ بنابراین امضای v2 الزامی است.

قالبِ APK Signing Block (بین آخرین ورودیِ ZIP و Central Directory):

    uint64  block_size   (اندازهٔ بلوک بدون این فیلد)
    جفت‌های  ID/مقدار:
        uint64  pair_size (= 4 + طولِ مقدار)
        uint32  id
        bytes   value
    uint64  block_size   (همان مقدار قبل)
    bytes   magic  "APK Sig Block 42"  (۱۶ بایت)

مقدارِ بلوکِ v2 (شناسهٔ 0x7109871a):

    signers = lp32( concat( lp32(signer_i) ) )
    signer  = lp32(signed_data) + lp32(signatures) + lp32(public_key)
    signed_data = lp32(digests) + lp32(certificates) + lp32(attributes)
    digests  = concat( lp32(u32(alg_id) + u32(len) + digest) )
    certs    = concat( lp32(cert_der) )
    sigs     = concat( lp32(u32(alg_id) + u32(len) + signature) )
    lp32(x)  = uint32_le(len(x)) + x

چکیده‌ها روی سه بخش و در قطعاتِ ۱ مگابایتی گرفته می‌شوند:
محتوای ZIP، Central Directory و EOCD (با جایگزینیِ افستِ CD با ابتدای بلوک).
هر قطعه:  H(0xa5 + uint32_le(len(chunk)) + chunk)
و چکیدهٔ نهایی: H(اتصالِ چکیدهٔ قطعات)
"""
import hashlib
import struct
import zipfile

APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
APK_SIGNATURE_SCHEME_V2_BLOCK_ID = 0x7109871A

# RSASSA-PKCS1-v1_5 با SHA2-256 (پشتیبانی از اندروید ۷ / API 24 به بالا)
SIG_ALG_RSA_PKCS1_V1_5_SHA256 = 0x0103
# RSASSA-PSS با SHA2-256
SIG_ALG_RSA_PSS_SHA256 = 0x0101

CHUNK_SIZE = 1024 * 1024


def _u32(n):
    return struct.pack("<I", n)


def _u64(n):
    return struct.pack("<Q", n)


def _lp32(payload):
    return _u32(len(payload)) + payload


def _seq32(elements):
    return b"".join(_lp32(e) for e in elements)


def _pair_seq32(pairs):
    """دنباله‌ای از جفت‌های (uint32, bytes) که هر کدام طول-پیشوند دارند"""
    return _seq32([_u32(alg) + _u32(len(val)) + val for alg, val in pairs])


# ---------------------------------------------------------------- کدِ ZIP ----

def find_eocd(data):
    """یافتنِ EOF record و استخراجِ محلِ Central Directory"""
    max_comment = 0xFFFF
    start = max(0, len(data) - (22 + max_comment))
    pos = data.rfind(b"PK\x05\x06", start)
    if pos < 0:
        raise ValueError("EOCD پیدا نشد")
    comment_len = struct.unpack_from("<H", data, pos + 20)[0]
    if pos + 22 + comment_len != len(data):
        raise ValueError("طولِ EOCD با انتهای فایل هم‌خوان نیست")
    cd_size = struct.unpack_from("<I", data, pos + 12)[0]
    cd_offset = struct.unpack_from("<I", data, pos + 16)[0]
    if cd_offset + cd_size != pos:
        raise ValueError("Central Directory در جای درستی نیست")
    return pos, cd_offset, cd_size, comment_len


def chunked_digest(parts, algo="sha256"):
    """چکیدهٔ قطعه‌ای (۱ مگابایتی) روی چند بخش

    هر قطعه:  H(0xa5 + uint32_le(len(chunk)) + chunk)
    چکیدهٔ سطحِ بالا: H(0x5a + uint32_le(تعدادِ قطعات) + اتصالِ چکیدهٔ قطعات)
    """
    digests = []
    for part in parts:
        for off in range(0, len(part), CHUNK_SIZE):
            chunk = part[off:off + CHUNK_SIZE]
            h = hashlib.new(algo)
            h.update(b"\xa5" + _u32(len(chunk)) + chunk)
            digests.append(h.digest())
    h = hashlib.new(algo)
    h.update(b"\x5a" + _u32(len(digests)) + b"".join(digests))
    return h.digest()


# ------------------------------------------------------------- امضای v2 ----

def build_v2_block(key, cert_der, contents, central_dir, eocd, cd_offset,
                   algorithms=(SIG_ALG_RSA_PKCS1_V1_5_SHA256,)):
    """ساخت مقدارِ بلوکِ v2 و امضای آن"""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    # EOCD با افستِ جایگزین‌شده (اشاره به ابتدای بلوکِ امضا)
    eocd_for_digest = bytearray(eocd)
    struct.pack_into("<I", eocd_for_digest, 16, cd_offset)
    eocd_for_digest = bytes(eocd_for_digest)

    digest = chunked_digest([contents, central_dir, eocd_for_digest], "sha256")

    # signed_data خودش مجموعه‌ای است که هر عضوش طول-پیشوند دارد؛
    # پیشوندِ بیرونی را امضاکننده (signer) اضافه می‌کند، نه اینجا.
    signed_data = _seq32([
        _pair_seq32([(alg, digest) for alg in algorithms]),
        _seq32([cert_der]),
        b"",  # ویژگی‌های اضافی (اختیاری)
    ])

    signatures = []
    for alg in algorithms:
        if alg in (SIG_ALG_RSA_PKCS1_V1_5_SHA256,):
            sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
        elif alg == SIG_ALG_RSA_PSS_SHA256:
            sig = key.sign(signed_data,
                           padding.PSS(mgf=padding.MGF1(hashes.SHA256()),
                                       salt_length=32),
                           hashes.SHA256())
        else:
            raise ValueError("الگوریتم پشتیبانی نمی‌شود: 0x%04x" % alg)
        signatures.append((alg, sig))

    public_key = key.public_key().public_bytes(
        __import__("cryptography.hazmat.primitives.serialization",
                   fromlist=["x"]).Encoding.DER,
        __import__("cryptography.hazmat.primitives.serialization",
                   fromlist=["x"]).PublicFormat.SubjectPublicKeyInfo)

    signer = _lp32(signed_data) + _lp32(_pair_seq32(signatures)) + _lp32(public_key)
    return _lp32(_seq32([signer]))


def build_apk_signing_block(pairs):
    """چیدنِ بلوکِ امضای APK از روی جفت‌های (id, value)"""
    body = b"".join(_u64(4 + len(value)) + _u32(pid) + value for pid, value in pairs)
    size = len(body) + 8 + 16  # اندازهٔ دوم + جادو
    return _u64(size) + body + _u64(size) + APK_SIG_BLOCK_MAGIC


def sign_v2(apk_path, key, cert_der, out_path):
    """درجِ بلوکِ امضای v2 در APK (فایل باید از پیش zipalign شده باشد)"""
    with open(apk_path, "rb") as fh:
        data = fh.read()

    eocd_off, cd_offset, cd_size, _comment = find_eocd(data)
    contents = data[:cd_offset]
    central_dir = data[cd_offset:cd_offset + cd_size]
    eocd = data[eocd_off:]

    # محتوای ZIP باید دقیقاً در ابتدای بلوک تمام شود (بدون فاصله)
    zf = zipfile.ZipFile(apk_path)
    end_of_last_entry = max(i.header_offset + (zipfile.sizeFileHeader +
                            len(i.filename.encode("utf-8")) + len(i.extra) +
                            i.compress_size) for i in zf.infolist())
    zf.close()
    if end_of_last_entry != cd_offset:
        raise ValueError("بین آخرین ورودی و Central Directory دادهٔ اضافه وجود دارد")

    value = build_v2_block(key, cert_der, contents, central_dir, eocd, cd_offset)
    block = build_apk_signing_block([(APK_SIGNATURE_SCHEME_V2_BLOCK_ID, value)])

    new_eocd = bytearray(eocd)
    struct.pack_into("<I", new_eocd, 16, cd_offset + len(block))
    new_eocd = bytes(new_eocd)

    with open(out_path, "wb") as fh:
        fh.write(contents + block + central_dir + new_eocd)
    return out_path
