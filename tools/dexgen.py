# -*- coding: utf-8 -*-
"""تولیدکنندهٔ بسیار کوچکِ DEX (فرمت 035).

فقط آنچه برای این پروژه لازم است تولید می‌کند: یک کلاس با چند متد و
دستورالعمل‌های محدودِ دالویک. خروجی با androguard اعتبارسنجی می‌شود.
"""
import hashlib
import struct
import zlib

DEX_MAGIC = b"dex\n035\x00"
ENDIAN_TAG = 0x12345678

# --- کدهای عملیاتیِ موردنیاز ---
OP_NOP = 0x00
OP_MOVE_RESULT_OBJECT = 0x0C
OP_RETURN_VOID = 0x0E
OP_RETURN_OBJECT = 0x11
OP_CONST_4 = 0x12
OP_CONST_STRING = 0x1A
OP_NEW_INSTANCE = 0x22
OP_INVOKE_VIRTUAL = 0x6E
OP_INVOKE_SUPER = 0x6F
OP_INVOKE_DIRECT = 0x70
OP_INVOKE_STATIC = 0x71
OP_INVOKE_INTERFACE = 0x72

ACC_PUBLIC = 0x0001
ACC_PROTECTED = 0x0004
ACC_CONSTRUCTOR = 0x00010000

# --- انواعِ بخش‌ها در map_list ---
TYPE_HEADER_ITEM = 0x0000
TYPE_STRING_ID_ITEM = 0x0001
TYPE_TYPE_ID_ITEM = 0x0002
TYPE_PROTO_ID_ITEM = 0x0003
TYPE_FIELD_ID_ITEM = 0x0004
TYPE_METHOD_ID_ITEM = 0x0005
TYPE_CLASS_DEF_ITEM = 0x0006
TYPE_MAP_LIST = 0x1000
TYPE_TYPE_LIST = 0x1001
TYPE_CLASS_DATA_ITEM = 0x2000
TYPE_CODE_ITEM = 0x2001
TYPE_STRING_DATA_ITEM = 0x2002
TYPE_DEBUG_INFO_ITEM = 0x2003


def uleb128(value):
    """رمزگذاریِ LEB128 بدون علامت"""
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def mutf8(text):
    """رمزگذاریِ Modified UTF-8 (همان‌طور که DEX انتظار دارد)"""
    out = bytearray()
    for ch in text:
        code = ord(ch)
        if code == 0:
            out += b"\xc0\x80"
        elif code < 0x80:
            out.append(code)
        elif code < 0x800:
            out.append(0xC0 | (code >> 6))
            out.append(0x80 | (code & 0x3F))
        else:
            out.append(0xE0 | (code >> 12))
            out.append(0x80 | ((code >> 6) & 0x3F))
            out.append(0x80 | (code & 0x3F))
    return bytes(out)


def utf16_len(text):
    """طول رشته بر حسب واحدهای UTF-16 (برای سرآیندِ string_data_item)"""
    return len(text.encode("utf-16-le")) // 2


class Instr:
    """یک دستورالعملِ دالویک در قالبِ میانی."""

    def __init__(self, fmt, opcode, *args):
        self.fmt = fmt          # 35c | 21c | 11x | 11n | 10x
        self.opcode = opcode
        self.args = args

    def encode(self):
        if self.fmt == "10x":
            return struct.pack("<H", self.opcode)
        if self.fmt == "11x":
            reg = self.args[0]
            return struct.pack("<BB", self.opcode, reg)
        if self.fmt == "11n":
            reg, value = self.args
            return struct.pack("<BB", self.opcode, ((value & 0xF) << 4) | (reg & 0xF))
        if self.fmt == "21c":
            reg, index = self.args
            return struct.pack("<BBH", self.opcode, reg & 0xFF, index)
        if self.fmt == "35c":
            regs, index = self.args
            if len(regs) > 5:
                raise ValueError("حداکثر ۵ رجیستر در قالب 35c")
            count = len(regs)
            padded = list(regs) + [0] * (5 - count)
            # چیدمانِ واقعی: چهار رجیسترِ اول در کلمهٔ سوم و رجیسترِ پنجم در نیبلِ پایینیِ بایتِ دوم
            c, d, e, f, g = padded[0], padded[1], padded[2], padded[3], padded[4]
            word = c | (d << 4) | (e << 8) | (f << 12)
            return struct.pack("<BBHH", self.opcode, ((count & 0xF) << 4) | (g & 0xF),
                               index, word)
        raise ValueError("قالب نامعتبر: %s" % self.fmt)


class MethodBuilder:
    def __init__(self, dex, class_name, name, proto, access, registers, outs=0):
        self.dex = dex
        self.class_name = class_name
        self.name = name
        self.proto = proto
        self.access = access
        self.registers = registers
        self.outs = outs
        self.instructions = []

    # --- دستورالعمل‌های پرمصرف ---
    def invoke(self, opcode, regs, owner, name, ret, params):
        proto = self.dex.proto(ret, params)
        owner_idx = owner if isinstance(owner, int) else self.dex.type(owner)
        ref = self.dex.method(owner_idx, name, proto)
        self.instructions.append(Instr("35c", opcode, list(regs), ref))
        return self

    def invoke_super(self, regs, owner, name, ret, params):
        return self.invoke(OP_INVOKE_SUPER, regs, owner, name, ret, params)

    def invoke_virtual(self, regs, owner, name, ret, params):
        return self.invoke(OP_INVOKE_VIRTUAL, regs, owner, name, ret, params)

    def invoke_direct(self, regs, owner, name, ret, params):
        return self.invoke(OP_INVOKE_DIRECT, regs, owner, name, ret, params)

    def new_instance(self, reg, class_name):
        self.instructions.append(
            Instr("21c", OP_NEW_INSTANCE, reg, self.dex.type(class_name)))
        return self

    def move_result_object(self, reg):
        self.instructions.append(Instr("11x", OP_MOVE_RESULT_OBJECT, reg))
        return self

    def const_string(self, reg, text):
        self.instructions.append(
            Instr("21c", OP_CONST_STRING, reg, self.dex.string(text)))
        return self

    def const_true(self, reg):
        self.instructions.append(Instr("11n", OP_CONST_4, reg, 1))
        return self

    def return_void(self):
        self.instructions.append(Instr("10x", OP_RETURN_VOID))
        return self


class DexBuilder:
    def __init__(self):
        self._strings = {}
        self._string_list = []
        self._types = {}
        self._type_list = []
        self._protos = {}
        self._proto_list = []
        self._methods = {}
        self._method_list = []
        self._fields = {}
        self._field_list = []
        self.classes = []

    # --- جداول ---
    def string(self, text):
        if text not in self._strings:
            self._strings[text] = len(self._string_list)
            self._string_list.append(text)
        return self._strings[text]

    def type(self, descriptor):
        if descriptor not in self._types:
            self._types[descriptor] = len(self._type_list)
            self._type_list.append(descriptor)
        return self._types[descriptor]

    def proto(self, ret, params):
        self.type(ret)
        for p in params:
            self.type(p)
        key = (ret, tuple(params))
        if key not in self._protos:
            self._protos[key] = len(self._proto_list)
            self._proto_list.append(key)
        return self._protos[key]

    def method(self, owner, name, proto_idx):
        key = (owner, name, proto_idx)
        if key not in self._methods:
            self._methods[key] = len(self._method_list)
            self._method_list.append(key)
        return self._methods[key]

    def field(self, owner, name, type_descriptor):
        key = (owner, name, type_descriptor)
        if key not in self._fields:
            self._fields[key] = len(self._field_list)
            self._field_list.append(key)
        return self._fields[key]

    # --- کلاس‌ها ---
    def add_class(self, class_name, super_name, source_file="Main.kt"):
        cls = {
            "name": class_name,
            "super": super_name,
            "source": self.string(source_file),
            "direct": [],
            "virtual": [],
        }
        self.classes.append(cls)
        return cls

    def add_method(self, cls, name, ret, params, access, registers,
                   outs=0, direct=False):
        proto_idx = self.proto(ret, params)
        method_idx = self.method(self.type(cls["name"]), name, proto_idx)
        builder = MethodBuilder(self, cls["name"], name, proto_idx, access, registers, outs)
        ins_size = 1 + sum(2 if p in ("J", "D") else 1 for p in params)
        if name != "<init>" and not direct:
            ins_size += 0
        entry = {"method_idx": method_idx, "access": access, "builder": builder,
                 "outs": outs, "registers": registers, "ins_size": ins_size}
        (cls["direct"] if direct else cls["virtual"]).append(entry)
        return builder

    # --- خروجی ---
    def build(self):
        # پیش‌از ساخت ناحیهٔ داده، همهٔ رشته‌ها باید ثبت شوند
        for t in self._type_list:
            self.string(t)
        for ret, params in self._proto_list:
            self.string(_shorty(ret, params))
        for owner, name, proto_idx in self._method_list:
            self.string(name)

        header_size = 0x70
        n_str, n_type = len(self._string_list), len(self._type_list)
        n_proto, n_meth = len(self._proto_list), len(self._method_list)
        n_cls = len(self.classes)
        tables_size = 4 * n_str + 4 * n_type + 12 * n_proto + 8 * n_meth + 32 * n_cls
        data_off = header_size + tables_size
        data_off += (-data_off) % 4

        body, code_offsets, class_data_offsets, string_data_offsets, type_list_offsets = \
            self._build_data(data_off)

        body = bytearray(body)
        while len(body) % 4:
            body.append(0)
        map_off = data_off + len(body)

        str_off = header_size
        type_off = str_off + 4 * n_str
        proto_off = type_off + 4 * n_type
        method_off = proto_off + 12 * n_proto
        class_off = method_off + 8 * n_meth

        entries = [
            (TYPE_HEADER_ITEM, 1, 0),
            (TYPE_STRING_ID_ITEM, n_str, str_off),
            (TYPE_TYPE_ID_ITEM, n_type, type_off),
            (TYPE_PROTO_ID_ITEM, n_proto, proto_off),
            (TYPE_METHOD_ID_ITEM, n_meth, method_off),
            (TYPE_CLASS_DEF_ITEM, n_cls, class_off),
            (TYPE_TYPE_LIST, len([o for o in type_list_offsets if o]),
             next((o for o in type_list_offsets if o), 0)),
            (TYPE_CODE_ITEM, len(code_offsets), code_offsets[0] if code_offsets else 0),
            (TYPE_CLASS_DATA_ITEM, n_cls, class_data_offsets[0] if class_data_offsets else 0),
            (TYPE_STRING_DATA_ITEM, n_str, string_data_offsets[0] if string_data_offsets else 0),
            (TYPE_MAP_LIST, 1, map_off),
        ]
        entries = [e for e in entries if e[1]]
        map_blob = struct.pack("<I", len(entries))
        for mtype, count, moff in entries:
            map_blob += struct.pack("<HHII", mtype, 0, count, moff)
        body += map_blob

        blob = bytearray()
        blob += DEX_MAGIC
        blob += struct.pack("<I", 0)
        blob += b"\x00" * 20
        blob += struct.pack("<I", 0)
        blob += struct.pack("<I", header_size)
        blob += struct.pack("<I", ENDIAN_TAG)
        blob += struct.pack("<II", 0, 0)
        blob += struct.pack("<I", 0)
        blob += struct.pack("<II", n_str, str_off)
        blob += struct.pack("<II", n_type, type_off)
        blob += struct.pack("<II", n_proto, proto_off)
        blob += struct.pack("<II", 0, 0)
        blob += struct.pack("<II", n_meth, method_off)
        blob += struct.pack("<II", n_cls, class_off)
        blob += struct.pack("<II", 0, data_off)
        assert len(blob) == header_size, len(blob)

        for off in string_data_offsets:
            blob += struct.pack("<I", off)
        for t in self._type_list:
            blob += struct.pack("<I", self.string(t))
        for i, (ret, params) in enumerate(self._proto_list):
            blob += struct.pack("<III", self.string(_shorty(ret, params)),
                                self.type(ret), type_list_offsets[i])
        for owner, name, proto_idx in self._method_list:
            blob += struct.pack("<HHI", owner, proto_idx, self.string(name))
        for index, cls in enumerate(self.classes):
            blob += struct.pack("<IIII", self.type(cls["name"]), ACC_PUBLIC,
                                self.type(cls["super"]), 0)
            blob += struct.pack("<III", cls["source"], 0, class_data_offsets[index])
        blob += b"\x00" * (data_off - len(blob))
        blob += body

        struct.pack_into("<I", blob, 0x20, len(blob))
        struct.pack_into("<I", blob, 0x34, map_off)
        struct.pack_into("<I", blob, 0x68, len(blob) - data_off)
        struct.pack_into("<I", blob, 0x6C, data_off)
        blob[12:32] = hashlib.sha1(bytes(blob[32:])).digest()
        struct.pack_into("<I", blob, 0x08, zlib.adler32(bytes(blob[12:])) & 0xFFFFFFFF)
        return bytes(blob)

    def _build_data(self, data_off):
        """ساخت ناحیهٔ داده.

        ترتیب مهم است: ابتدا type_listها و رشته‌ها، سپس همهٔ code_itemها،
        و در پایان class_data_itemها (که باید entryهای متد را پیوسته داشته باشند).
        """
        body = bytearray()

        def align():
            while len(body) % 4:
                body.append(0)

        type_list_offsets = []
        for ret, params in self._proto_list:
            if params:
                align()
                type_list_offsets.append(data_off + len(body))
                body += struct.pack("<I", len(params))
                for p in params:
                    body += struct.pack("<H", self.type(p))
            else:
                type_list_offsets.append(0)

        string_data_offsets = []
        for text in self._string_list:
            string_data_offsets.append(data_off + len(body))
            body += uleb128(utf16_len(text))
            body += mutf8(text)
            body.append(0)

        # --- code_itemهای همهٔ متدها ---
        code_offsets = []
        for cls in self.classes:
            for group in (cls["direct"], cls["virtual"]):
                for entry in group:
                    builder = entry["builder"]
                    align()
                    code_offsets.append(data_off + len(body))
                    insns = b"".join(i.encode() for i in builder.instructions)
                    insns_size = len(insns) // 2
                    body += struct.pack("<HHHH", builder.registers, entry["ins_size"],
                                        entry["outs"], 0)
                    body += struct.pack("<II", 0, insns_size)   # debug_info_off
                    body += insns
                    if insns_size % 2:
                        body += b"\x00\x00"

        # --- class_data_itemها ---
        class_data_offsets = []
        cursor = 0
        for cls in self.classes:
            align()
            class_data_offsets.append(data_off + len(body))
            body += uleb128(0)                      # فیلدهای ایستا
            body += uleb128(0)                      # فیلدهای نمونه
            body += uleb128(len(cls["direct"]))
            body += uleb128(len(cls["virtual"]))
            for group in (cls["direct"], cls["virtual"]):
                prev = 0
                for entry in group:
                    idx = entry["method_idx"]
                    body += uleb128(idx - prev)
                    prev = idx
                    body += uleb128(entry["access"])
                    body += uleb128(code_offsets[cursor])
                    cursor += 1

        align()
        return bytes(body), code_offsets, class_data_offsets, string_data_offsets, type_list_offsets


def _shorty(ret, params):
    """رشتهٔ کوتاهِ نوعِ متد (مانند VLI)"""
    def short(desc):
        return desc[0] if desc[0] != "[" else "["
    return short(ret) + "".join(short(p) for p in params)
