#!/usr/bin/env python3
"""
Удаляет атрибут MethodParameters из .class файлов.

Зачем: javac из JDK 21+ проставляет MethodParameters даже в синтетических
конструкторах нестатических внутренних классов, причём с пустым именем
параметра (name_index = 0). Старые версии d8/R8 (например 8.2.x) падают на
таком атрибуте с NullPointerException.

Атрибут MethodParameters необязателен и на работу приложения не влияет, поэтому
его безопасно вырезать перед деxyрованием.

Использование:  python3 strip_method_params.py <каталог с .class>
"""
import os
import struct
import sys

# Размеры элементов constant pool по тегу.
SIZES = {
    3: 4, 4: 4,           # Integer, Float
    5: 8, 6: 8,           # Long, Double (занимают 2 слота)
    7: 2, 8: 2, 16: 2,   # Class, String, MethodType
    9: 4, 10: 4, 11: 4,  # Fieldref, Methodref, InterfaceMethodref
    12: 4, 17: 4, 18: 4,  # NameAndType, Dynamic, InvokeDynamic
    15: 3,               # MethodHandle
    1: -1,               # Utf8 — вычисляется отдельно
}

TARGET = 'MethodParameters'


def read_u1(data, pos):
    return data[pos], pos + 1


def read_u2(data, pos):
    return struct.unpack_from('>H', data, pos)[0], pos + 2


def read_u4(data, pos):
    return struct.unpack_from('>I', data, pos)[0], pos + 4


def parse_constant_pool(data, pos):
    """Возвращает (utf8_by_index, позиция после constant pool)."""
    count, pos = read_u2(data, pos)
    utf8 = {}
    i = 1
    while i < count:
        tag, pos = read_u1(data, pos)
        if tag == 1:
            length, pos = read_u2(data, pos)
            utf8[i] = data[pos:pos + length].decode('utf-8', 'replace')
            pos += length
        else:
            size = SIZES.get(tag)
            if size is None:
                raise ValueError('неизвестный тег constant pool: %d' % tag)
            if tag in (5, 6):
                i += 1  # Long/Double занимают два слота
            pos += size
        i += 1
    return utf8, pos


def copy_attributes(data, out, pos, utf8):
    """Копирует список атрибутов в out, выбрасывая MethodParameters."""
    count, p = read_u2(data, pos)
    count_idx = len(out)
    out += b'\x00\x00'
    kept = 0
    for _ in range(count):
        start = p
        name_index, p = read_u2(data, p)
        length, p = read_u4(data, p)
        end = p + length
        if utf8.get(name_index) == TARGET:
            p = end
            continue
        out += data[start:end]
        p = end
        kept += 1
    struct.pack_into('>H', out, count_idx, kept)
    return p


def strip_class(data):
    if data[:4] != b'\xca\xfe\xba\xbe':
        return None
    pos = 8
    utf8, pos = parse_constant_pool(data, pos)
    pos += 6  # access_flags, this_class, super_class
    ifc_count, pos = read_u2(data, pos)
    pos += ifc_count * 2

    out = bytearray(data[:pos])

    fields_count, pos = read_u2(data, pos)
    out += struct.pack('>H', fields_count)
    for _ in range(fields_count):
        out += data[pos:pos + 6]  # access_flags, name_index, descriptor_index
        pos += 6
        pos = copy_attributes(data, out, pos, utf8)

    methods_count, pos = read_u2(data, pos)
    out += struct.pack('>H', methods_count)
    for _ in range(methods_count):
        out += data[pos:pos + 6]
        pos += 6
        pos = copy_attributes(data, out, pos, utf8)

    pos = copy_attributes(data, out, pos, utf8)
    return bytes(out)


def main():
    if len(sys.argv) < 2:
        print('usage: strip_method_params.py <dir>')
        return 1
    total = 0
    for root, _dirs, files in os.walk(sys.argv[1]):
        for name in files:
            if not name.endswith('.class'):
                continue
            path = os.path.join(root, name)
            with open(path, 'rb') as f:
                data = f.read()
            try:
                new = strip_class(data)
            except (Exception, KeyboardInterrupt) as e:
                print('  ошибка %s: %s' % (path, e))
                continue
            if new is not None and new != data:
                with open(path, 'wb') as f:
                    f.write(new)
                total += 1
    print('  MethodParameters удалён в %d классах' % total)
    return 0


if __name__ == '__main__':
    sys.exit(main())
