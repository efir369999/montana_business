#!/usr/bin/env python3
# Independent oracle for the frozen vectors of tests/vectors.rs: the frame, the bodies, the
# labels and the UTC periods written again from the contract text alone (Python standard library,
# no Rust). Run it and compare its output with the constants in vectors.rs; signatures are not
# here because ML-DSA is not in the standard library, the Rust side freezes those itself.
import datetime
import hashlib
import struct


def seq(a, n):
    return bytes((a + i) % 256 for i in range(n))


def dh(label, data):
    return hashlib.sha256(label + b"\x00" + data).digest()


ORG, LANE, PREV, AUTHOR = seq(0x00, 32), seq(0x20, 32), seq(0x40, 32), seq(0x60, 32)
M1, M2 = seq(0x80, 32), seq(0xA0, 32)
D1, D2 = seq(0xC0, 16), seq(0xD0, 16)
K1 = bytes((i * 7 + 3) % 256 for i in range(1952))
K2 = bytes((i * 11 + 5) % 256 for i in range(1952))
N = 0x0102030405060708
AT = 1_791_234_567_890
EXP = AT + 7 * 86_400_000


def s(text):
    b = text.encode("utf-8")
    return struct.pack("<H", len(b)) + b


def u8(v):
    return struct.pack("<B", v)


def u32(v):
    return struct.pack("<I", v)


def u64(v):
    return struct.pack("<Q", v)


def unsigned(chain, kind, body, org=ORG, lane=LANE, n=N, prev=PREV, at=AT, author=AUTHOR):
    return (b"MTB1" + u8(chain) + u8(kind) + org + lane + u64(n) + prev + u64(at) + author
            + u32(len(body)) + body)


def rid(chain, kind, body, **kw):
    return dh(b"mt-biz-record", unsigned(chain, kind, body, **kw)).hex()


R, H = 0x52, 0x48
swap_lane = dict(lane=AUTHOR, author=LANE)
swap_n_at = dict(n=AT, at=N)

cases = [
    ("GENESIS", R, 0x01,
     s("Montana Coffee") + K1 + s("Анна Петрова") + s("montana://card/anna"),
     s("Анна Петрова") + K1 + s("Montana Coffee") + s("montana://card/anna"), {}),
    ("INVITE", R, 0x02, M1 + u8(3) + D1 + u64(EXP) + u8(1), None, swap_lane),
    ("JOIN", R, 0x03, M1 + K2 + s("Борис") + s("montana://card/boris"),
     M1 + K2 + s("montana://card/boris") + s("Борис"), {}),
    ("ROLE", R, 0x04, M1 + u8(2), None, swap_lane),
    ("DEPT", R, 0x05, D1 + s("Кухня") + u8(0), None, swap_lane),
    ("ASSIGN", R, 0x06, M1 + D1 + s("Бариста"), None, swap_lane),
    ("REMOVE", R, 0x07, M1, None, swap_lane),
    ("REKEY", R, 0x08, M1 + K2, None, swap_lane),
    ("REVOKE", R, 0x09, M2, None, swap_lane),
    ("OFFER", R, 0x0A, D2 + s("Латте") + u64(350) + u32(20) + u8(1), None, swap_n_at),
    ("PROFILE", R, 0x0B, s("Анна") + s("montana://card/anna2"),
     s("montana://card/anna2") + s("Анна"), {}),
    ("PHONE_BIND", H, 0x21, seq(0x10, 40), None, swap_lane),
    ("INVITE_PHONE", H, 0x22, M1 + M2, M2 + M1, {}),
    ("SALARY", H, 0x23, M1 + u64(100) + u8(1) + u64(AT), M1 + u64(AT) + u8(1) + u64(100), {}),
    ("PAY", H, 0x24, M1 + u8(1) + u32(0x10005078) + u64(100) + s("Октябрь"), None, swap_n_at),
    ("RECEIPT", H, 0x25, M2, None, swap_lane),
    ("REDEEM", H, 0x26, D2 + u32(2) + u64(700), None, swap_lane),
    ("FULFIL", H, 0x27, M2, None, swap_lane),
]

for name, chain, kind, body, perm_body, perm_kw in cases:
    print(name, "id", rid(chain, kind, body))
    if perm_body is not None:
        print(name, "perm", rid(chain, kind, perm_body))
    else:
        print(name, "perm", rid(chain, kind, body, **perm_kw))

print("REMOVE unsigned", unsigned(R, 0x07, M1).hex())

print("MEMBER id", dh(b"mt-biz-member", K1).hex())
print("MEMBER perm", dh(b"mt-biz-member", K1[::-1]).hex())
print("INVITE_ID id", dh(b"mt-biz-invite", M1).hex())
print("INVITE_ID perm", dh(b"mt-biz-invite", M1[::-1]).hex())
print("PHONE id", dh(b"mt-biz-phone", ORG + b"+79161234567").hex())
print("PHONE perm", dh(b"mt-biz-phone", b"+79161234567" + ORG).hex())


def mtba(channel, at, subject, e164):
    e = e164.encode("ascii")
    return b"MTBA" + u8(1) + u8(channel) + u64(at) + subject + u8(len(e)) + e


A = mtba(1, AT, ORG, "+79161234567")
print("MTBA unsigned", A.hex())
print("MTBA digest", dh(b"mt-biz-attest", A).hex())
print("MTBA perm_e164", dh(b"mt-biz-attest", mtba(1, AT, ORG, "+77654321619")).hex())
at_be = struct.unpack("<Q", struct.pack(">Q", AT))[0]
print("MTBA perm_at", dh(b"mt-biz-attest", mtba(1, at_be, ORG, "+79161234567")).hex())


def mtbe(at, subject, email):
    e = email.encode("ascii")
    return b"MTBE" + u8(1) + u64(at) + subject + u8(len(e)) + e


E = mtbe(AT, ORG, "anna@montana.quest")
print("MTBE unsigned", E.hex())
print("MTBE digest", dh(b"mt-biz-email-attest", E).hex())
print("MTBE perm_email", dh(b"mt-biz-email-attest", mtbe(AT, ORG, "tseuq.anatnom@anna")).hex())
print("MTBE perm_label", dh(b"mt-biz-attest", E).hex())

EPOCH = datetime.datetime(1970, 1, 1, tzinfo=datetime.timezone.utc)
MONDAY0 = datetime.date(1969, 12, 29)


def ms(y, mo, d, h=0, mi=0, sec=0, milli=0):
    t = datetime.datetime(y, mo, d, h, mi, sec, milli * 1000, tzinfo=datetime.timezone.utc)
    delta = t - EPOCH
    return (delta.days * 86400 + delta.seconds) * 1000 + delta.microseconds // 1000


def idx(period, t_ms):
    d = EPOCH + datetime.timedelta(milliseconds=t_ms)
    if period == 1:
        return (d.date() - EPOCH.date()).days
    if period == 2:
        return (d.date() - MONDAY0).days // 7
    return (d.year - 1970) * 12 + d.month - 1


def key(period, t_ms):
    return (period << 28) | idx(period, t_ms)


points = [
    ("DAY_A", 1, ms(2026, 10, 5, 23, 59, 59, 999)),
    ("DAY_B", 1, ms(2026, 10, 6)),
    ("DAY_C", 1, ms(2028, 2, 29, 12)),
    ("WEEK_A", 2, ms(2026, 10, 4, 23, 59, 59, 999)),
    ("WEEK_B", 2, ms(2026, 10, 5)),
    ("WEEK_C", 2, ms(1970, 1, 4, 23, 59, 59, 999)),
    ("WEEK_D", 2, ms(1970, 1, 5)),
    ("MONTH_A", 3, ms(2026, 1, 31, 23, 59, 59, 999)),
    ("MONTH_B", 3, ms(2026, 2, 1)),
    ("MONTH_C", 3, ms(2028, 2, 29, 23, 59, 59, 999)),
    ("MONTH_D", 3, ms(2028, 3, 1)),
    ("MONTH_E", 3, ms(2026, 12, 31, 23, 59, 59, 999)),
    ("MONTH_F", 3, ms(2027, 1, 1)),
]
print("WEEKDAY 2026-10-05", datetime.date(2026, 10, 5).strftime("%A"))
for name, period, t in points:
    print(name, t, hex(key(period, t)))


def due(period, frm, now):
    lo = max(idx(period, frm), idx(period, now) - 12)
    return [hex((period << 28) | i) for i in range(lo, idx(period, now))]


print("DUE_DAY3", due(1, AT, AT + 3 * 86_400_000 + 3_600_000))
print("DUE_DAY_CAP", due(1, 0, AT))
print("DUE_WEEK", due(2, ms(2026, 9, 7, 10), ms(2026, 10, 7, 10)))
print("DUE_MONTH", due(3, ms(2026, 1, 15), ms(2026, 4, 1)))
print("DUE_MONTH_CAP", due(3, ms(2020, 1, 1), ms(2026, 10, 6)))
print("AT day", idx(1, AT), EPOCH + datetime.timedelta(milliseconds=AT))


# Contract v1.1 (06.10): the catalogue (R), shifts (H), supply (S) and the organisation's chats (C).
S_, C_ = 0x53, 0x43
P = seq(0x30, 16)
G = seq(0x50, 16)


def line(item, size, qty, coins):
    return item + s(size) + u32(qty) + u64(coins)


def lines(*rows):
    return struct.pack("<H", len(rows)) + b"".join(rows)


L1 = line(seq(0xE0, 16), "42", 2, 900)
L2 = line(seq(0xF0, 16), "", 1, 0)
L2_BIG = seq(0xF0, 16) + s("") + struct.pack(">I", 1) + u64(0)

cases11 = [
    ("ITEM", R, 0x0C, D1 + s("Кроссовки") + s("40,41,42") + s("пара") + u8(1),
     D1 + s("40,41,42") + s("Кроссовки") + s("пара") + u8(1), {}),
    ("NODE", R, 0x0D, D1 + u8(2) + s("Склад Север") + s("Химки") + u8(1),
     D1 + u8(2) + s("Химки") + s("Склад Север") + u8(1), {}),
    ("NODE_STAFF", R, 0x0E, D1 + M1 + u8(1), M1 + D1 + u8(1), {}),
    ("SHIFT_OPEN", H, 0x28, D1, None, swap_lane),
    ("SHIFT_CLOSE", H, 0x29, s("смена"), None, swap_n_at),
    ("SHIFT_CONFIRM", H, 0x2A, M2, None, swap_lane),
    ("ORDER", S_, 0x41, D1 + D2 + lines(L1, L2) + u8(2) + M1 + s("две пары"),
     D1 + D2 + lines(L2, L1) + u8(2) + M1 + s("две пары"), {}),
    ("CONFIRM", S_, 0x42, D1, None, swap_n_at),
    ("PACK", S_, 0x43, P + D1 + lines(L1), D1 + P + lines(L1), {}),
    ("HANDOFF", S_, 0x44, P + D1 + D2, P + D2 + D1, {}),
    ("ACCEPT", S_, 0x45, P + D2 + u8(3) + lines(L1) + s("одна пара") + M2,
     P + D2 + u8(3) + lines(L1) + M2 + s("одна пара"), {}),
    ("SCAN", S_, 0x46, P + D1, None, swap_lane),
    ("SHELF", S_, 0x47, D2 + lines(L1), None, swap_n_at),
    ("SALE", S_, 0x48, D2 + lines(L2), None, swap_n_at),
    ("ISSUE", S_, 0x49, P + u8(2) + s("подошва"), None, swap_lane),
    ("CANCEL", S_, 0x4A, s("не нужно"), None, swap_n_at),
    ("OPEN", C_, 0x61, u8(2) + s("Новости") + D1 + G, u8(2) + s("Новости") + G + D1, {}),
    ("LETTER", C_, 0x62, M1 + s("0198a2b3c4d5") + u8(1), M1 + u8(1) + s("0198a2b3c4d5"), {}),
    ("EDIT", C_, 0x63, M1 + M2, M2 + M1, {}),
    ("DELETE", C_, 0x64, M2, None, swap_lane),
    ("TRACK", C_, 0x65, u8(1), None, swap_lane),
    ("TRACK_OFF", C_, 0x65, u8(0), None, swap_lane),
    ("TRACK_WINDOW", C_, 0x66, u32(1800), struct.pack(">I", 1800), {}),
]

for name, chain, kind, body, perm_body, perm_kw in cases11:
    print(name, "id", rid(chain, kind, body))
    if perm_body is not None:
        print(name, "perm", rid(chain, kind, perm_body))
    else:
        print(name, "perm", rid(chain, kind, body, **perm_kw))

print("SHIFT_LANE id", dh(b"mt-biz-shift", M1).hex())
print("SHIFT_LANE perm", dh(b"mt-biz-shift", M1[::-1]).hex())
print("LETTER_HASH id", dh(b"mt-biz-letter", b"m3" + b"\x00" + "Кухня в одиннадцать".encode()).hex())
print("LETTER_HASH perm", dh(b"mt-biz-letter", "Кухня в одиннадцать".encode() + b"\x00" + b"m3").hex())
print("SALE body", (D2 + lines(L2)).hex())
print("SALE body_big", (D2 + lines(L2_BIG)).hex())
