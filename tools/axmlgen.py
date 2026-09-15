# -*- coding: utf-8 -*-
"""تولیدکنندهٔ AndroidManifest.xml به صورت باینری (AXML).

چون در این محیط android.jar در دسترس نیست، مانیفست را مستقیماً با
شناسه‌های رسمیِ ویژگی‌های اندروید (از public.xml) می‌سازیم.
"""
import struct

RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
RES_XML_RESOURCE_MAP_TYPE = 0x0180
RES_XML_START_NAMESPACE_TYPE = 0x0100
RES_XML_END_NAMESPACE_TYPE = 0x0101
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_END_ELEMENT_TYPE = 0x0103

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# شناسه‌های ویژگی‌های پرکاربرد (از جدول رسمیِ منابع اندروید)
ATTRS = {
    "theme": 0x01010000,
    "label": 0x01010001,
    "icon": 0x01010002,
    "name": 0x01010003,
    "debuggable": 0x0101000F,
    "exported": 0x01010010,
    "launchMode": 0x0101001D,
    "screenOrientation": 0x0101001E,
    "configChanges": 0x0101001F,
    "minSdkVersion": 0x0101020C,
    "versionCode": 0x0101021B,
    "versionName": 0x0101021C,
    "windowSoftInputMode": 0x0101022B,
    "targetSdkVersion": 0x01010270,
    "allowBackup": 0x01010280,
    "hardwareAccelerated": 0x010102D3,
    "usesCleartextTraffic": 0x010104EC,
    "resizeableActivity": 0x010104E3,
}

NO_ENTRY = 0xFFFFFFFF


class AXMLBuilder:
    def __init__(self):
        self._strings = {}
        self._string_list = []
        self._attr_ids = {}
        self._body = bytearray()   # namespace/element chunks

    # --- رشته‌ها ---
    def string(self, text):
        if text not in self._strings:
            self._strings[text] = len(self._string_list)
            self._string_list.append(text)
        return self._strings[text]

    def attr(self, name, res_id=None):
        """ثبتِ نامِ ویژگی و شناسهٔ منبعِ آن"""
        idx = self.string(name)
        self._attr_ids[idx] = res_id if res_id is not None else ATTRS.get(name, 0)
        return idx

    # --- گره‌ها ---
    def start_namespace(self, prefix, uri):
        # سرآیندِ گره (۸ بایت) + خط و توضیح (۸ بایت) + پیشوند و فضای‌نام (۸ بایت)
        self._body += struct.pack("<HHI", RES_XML_START_NAMESPACE_TYPE, 16, 24)
        self._body += struct.pack("<II", 1, NO_ENTRY)
        self._body += struct.pack("<II", self.string(prefix), self.string(uri))

    def end_namespace(self, prefix, uri):
        self._body += struct.pack("<HHI", RES_XML_END_NAMESPACE_TYPE, 16, 24)
        self._body += struct.pack("<II", 1, NO_ENTRY)
        self._body += struct.pack("<II", self.string(prefix), self.string(uri))

    def start_element(self, name, attributes=()):
        """attributes: فهرستی از (نام، نوع، مقدار)"""
        encoded = []
        for attr_name, kind, value in attributes:
            name_idx = self.attr(attr_name)
            if kind == "string":
                value_idx = self.string(value)
                raw = value_idx
                dtype, data = TYPE_STRING, value_idx
            elif kind == "int":
                raw = NO_ENTRY
                dtype, data = TYPE_INT_DEC, int(value)
            elif kind == "bool":
                raw = NO_ENTRY
                dtype, data = TYPE_INT_BOOLEAN, 0xFFFFFFFF if value else 0
            elif kind == "ref":
                raw = NO_ENTRY
                dtype, data = TYPE_REFERENCE, int(value)
            else:
                raise ValueError("نوع نامعتبر: %s" % kind)
            encoded.append((name_idx, raw, dtype, data))

        size = 16 + 20 + 20 * len(encoded)
        chunk = bytearray()
        chunk += struct.pack("<HHI", RES_XML_START_ELEMENT_TYPE, 16, size)
        chunk += struct.pack("<II", 1, NO_ENTRY)        # lineNumber, comment
        chunk += struct.pack("<II", NO_ENTRY, self.string(name))
        chunk += struct.pack("<HHHHHH", 20, 20, len(encoded), 0, 0, 0)
        for name_idx, raw, dtype, data in encoded:
            chunk += struct.pack("<III", NO_ENTRY, name_idx, raw)
            chunk += struct.pack("<HBBI", 8, 0, dtype, data & 0xFFFFFFFF)
        self._body += chunk

    def end_element(self, name):
        self._body += struct.pack("<HHI", RES_XML_END_ELEMENT_TYPE, 16, 24)
        self._body += struct.pack("<II", 1, NO_ENTRY)
        self._body += struct.pack("<II", NO_ENTRY, self.string(name))

    # --- خروجی ---
    def build(self):
        # استخرِ رشته‌ها (UTF-16)
        pool_strings = bytearray()
        offsets = []
        for text in self._string_list:
            offsets.append(len(pool_strings))
            encoded = text.encode("utf-16-le")
            length = len(encoded) // 2
            pool_strings += struct.pack("<H", length)
            pool_strings += encoded
            pool_strings += b"\x00\x00"
        while len(pool_strings) % 4:
            pool_strings += b"\x00\x00"

        string_offsets = bytearray()
        for off in offsets:
            string_offsets += struct.pack("<I", off)

        pool_header_size = 0x1C
        pool_size = pool_header_size + len(string_offsets) + len(pool_strings)
        pool = bytearray()
        pool += struct.pack("<HHI", RES_STRING_POOL_TYPE, pool_header_size, pool_size)
        pool += struct.pack("<IIII", len(self._string_list), 0, 0,
                            pool_header_size + len(string_offsets))
        pool += struct.pack("<I", 0)                    # stylesStart
        pool += bytes(string_offsets)
        pool += bytes(pool_strings)

        # نگاشتِ منابع: به‌ازای هر رشته، شناسهٔ ویژگی (یا صفر)
        resmap_size = 8 + 4 * len(self._string_list)
        resmap = bytearray()
        resmap += struct.pack("<HHI", RES_XML_RESOURCE_MAP_TYPE, 8, resmap_size)
        for idx in range(len(self._string_list)):
            resmap += struct.pack("<I", self._attr_ids.get(idx, 0))

        total = 8 + len(pool) + len(resmap) + len(self._body)
        out = bytearray()
        out += struct.pack("<HHI", RES_XML_TYPE, 8, total)  # سرآیندِ کلِ فایل
        out += pool
        out += resmap
        out += self._body
        struct.pack_into("<I", out, 4, total)
        return bytes(out)


def build_manifest(package, version_code, version_name, min_sdk, target_sdk,
                   app_label, activity_name):
    """ساخت یک مانیفستِ استاندارد برای اپ تک‌صفحه‌ایِ WebView"""
    axml = AXMLBuilder()
    axml.start_namespace("android", ANDROID_NS)
    axml.start_element("manifest", [
        ("package", "string", package),
        ("versionCode", "int", version_code),
        ("versionName", "string", version_name),
    ])
    axml.start_element("uses-sdk", [
        ("minSdkVersion", "int", min_sdk),
        ("targetSdkVersion", "int", target_sdk),
    ])
    axml.end_element("uses-sdk")
    axml.start_element("application", [
        ("label", "string", app_label),
        ("allowBackup", "bool", True),
        ("hardwareAccelerated", "bool", True),
        ("usesCleartextTraffic", "bool", False),
    ])
    axml.start_element("activity", [
        ("name", "string", activity_name),
        ("exported", "bool", True),
        ("screenOrientation", "int", 1),          # 1 = portrait
        ("windowSoftInputMode", "int", 0x10),     # adjustResize
        ("launchMode", "int", 2),                 # singleTask
    ])
    axml.start_element("intent-filter", [])
    axml.start_element("action", [("name", "string", "android.intent.action.MAIN")])
    axml.end_element("action")
    axml.start_element("category", [("name", "string", "android.intent.category.LAUNCHER")])
    axml.end_element("category")
    axml.end_element("intent-filter")
    axml.end_element("activity")
    axml.end_element("application")
    axml.end_element("manifest")
    axml.end_namespace("android", ANDROID_NS)
    return axml.build()
