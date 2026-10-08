#!/usr/bin/env python3
"""THE TWO TIERS AGREE, NEVER SILENTLY (C-2.1, Montana Business for Android; iOS tools/mt-biz-view-contract-check.py, c02cf094).

The view of an organization is written by the core (mt_biz_view, Rust) and read by the app (BizView, Kotlin). The app reads
it by hand from a JSONObject, every read lenient: a key the core spelt otherwise would read as empty and nobody would hear of
it. This guard holds the two tiers to one frozen sample of the core's view -- a rich organization written by the core itself
(the core's test tests/sample_view.rs, SAMPLE_OUT), laid in scripts/contracts/mt-biz-view.CORESHA.json, CORESHA the pinned
core of scripts/toolchain.json -- and reads BizView's parse from its source.

HOW THE PARSE IS READ. Between the markers VIEW-CONTRACT BEGIN and END of Business.kt every key is read by one reader of
BizView's companion, by its literal name, on a named object:
    X.str("k")  X.long("k")  X.int("k")  X.bool("k")       -- a value the core always writes (null refused)
    X.strOrNull("k")  X.tagOrNone("k")  X.longOrNull("k")  -- a value the core may write as null
    X.strings("k")                                         -- an array of strings
    X.objects("k") { Y -> ... }   X.objects("k", ::f)      -- an array of objects, each named Y (or read by fun f(Y: JSONObject))
    X.objectsOrNull("k") { Y -> ... }                      -- the same, absent in an older core
    val Y = X.objectOf("k")   val Y = X.objectOrEmpty("k") -- an object (objectOrEmpty: the core may write null)
    val Y = X.objectOrNull("k")                            -- an object or null, kept as null (an order's standing)
The root is o. Every name is bound once; a read on an unbound name, a key that is not a literal, or any other read of the
JSON (opt..., get..., has, isNull, JSONArray) inside the block is refused -- so the list of keys read is the whole parse.

THE CHECK, both ways and at every depth: every key of the sample is read under the same path; every key read stands in the
sample (an array of the sample is met non-empty, so every nested reader is met); every value of the sample has the kind
its reader takes, null only where the reader takes null. Then the guard proves itself on probes -- a key misspelt in the
parse, a key the core stops writing, a key the core adds, a null in a required value, a value of another kind, a raw read
-- each of which it must refuse. Exit 2 with the list.
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SOURCE = os.path.join(ROOT, "app", "src", "main", "kotlin", "quest", "montana", "app", "Business.kt")
SAMPLES = os.path.join(HERE, "contracts")
TOOLCHAIN = os.path.join(HERE, "toolchain.json")

KIND = {
    "str": "string", "strOrNull": "string?", "tagOrNone": "string?", "long": "int", "longOrNull": "int?", "int": "int",
    "bool": "bool", "strings": "[string]", "objects": "[object]", "objectsOrNull": "[object]?",
    "objectOf": "object", "objectOrEmpty": "object?", "objectOrNull": "object?",
}
NAME = r"[A-Za-z_][A-Za-z0-9_]*"
KEY = r'"([a-z_0-9]+)"'
VALUE = re.compile(r"\b(" + NAME + r")\.(str|strOrNull|tagOrNone|long|longOrNull|int|bool|strings)\(" + KEY + r"\)")
LIST = re.compile(r"\b(" + NAME + r")\.(objects|objectsOrNull)\(" + KEY + r"\s*(?:,\s*::(" + NAME + r")\s*\)|\)\s*\{\s*(" + NAME + r")\s*->)")
OBJECT = re.compile(r"\bval\s+(" + NAME + r")\s*=\s*(" + NAME + r")\.(objectOf|objectOrEmpty|objectOrNull)\(" + KEY + r"\)")
TEMPLATE = re.compile(r"\bfun\s+(" + NAME + r")\((" + NAME + r"):\s*JSONObject\)")
ANY_READER = re.compile(r"\b(" + NAME + r")\.(str|strOrNull|tagOrNone|long|longOrNull|int|bool|strings|objects|objectsOrNull|objectOf|objectOrEmpty|objectOrNull)\(")
RAW = re.compile(r"\.(opt[A-Za-z]*|get[A-Z][A-Za-z]*|has|isNull|keys|names)\(|\bJSONArray\b")


def block(text):
    try:
        a = text.index("VIEW-CONTRACT BEGIN")
        b = text.index("VIEW-CONTRACT END", a)
    except ValueError:
        return None
    return text[text.index("\n", a) + 1:b]


def reads_of(code):
    """The keys the parse reads, {path: kind}, and the faults of the parse itself."""
    bad, reads, bound, aliases = [], {}, {"o": "$"}, []
    code = "\n".join(row.split("//")[0] for row in code.split("\n"))
    for m in RAW.finditer(code):
        bad.append("parse: a raw read of the JSON (%s) -- every key goes through a reader" % m.group(0))
    events = []
    for kind, rx in (("template", TEMPLATE), ("object", OBJECT), ("list", LIST), ("value", VALUE)):
        for m in rx.finditer(code):
            events.append((m.start(), kind, m))
    taken = set()
    for at, kind, m in events:
        if kind == "object":
            taken.add(m.start(2))
        elif kind in ("list", "value"):
            taken.add(m.start(1))
    for m in ANY_READER.finditer(code):
        if m.start(1) not in taken:
            bad.append("parse: a reader without a literal key or a name for its object: %s" % code[m.start():m.end() + 24].strip())
    events.sort(key=lambda e: e[0])

    def bind(name, path):
        if name in bound:
            bad.append("parse: the name %s is bound twice -- each object of the view gets a name of its own" % name)
        bound[name] = path

    def where(name):
        if name not in bound:
            bad.append("parse: a read on %s, which no reader named" % name)
            return None
        return bound[name]

    for _, kind, m in events:
        if kind == "template":
            bind(m.group(2), "%" + m.group(1))
        elif kind == "object":
            base = where(m.group(2))
            if base is not None:
                reads[base + "." + m.group(4)] = KIND[m.group(3)]
                bind(m.group(1), base + "." + m.group(4))
        elif kind == "list":
            base = where(m.group(1))
            if base is not None:
                path = base + "." + m.group(3)
                reads[path] = KIND[m.group(2)]
                if m.group(4):
                    aliases.append((m.group(4), path + "[]"))
                else:
                    bind(m.group(5), path + "[]")
        else:
            base = where(m.group(1))
            if base is not None:
                reads[base + "." + m.group(3)] = KIND[m.group(2)]
    out = {}
    for path, kind in reads.items():
        if path.startswith("%"):
            fn, _, rest = path[1:].partition(".")
            used = [at for f, at in aliases if f == fn]
            if not used:
                bad.append("parse: fun %s reads the view but no reader uses it" % fn)
            for at in used:
                out[at + "." + rest] = kind
        else:
            out[path] = kind
    for f, _ in aliases:
        if "%" + f not in bound.values():
            bad.append("parse: ::%s names no reading fun of the block" % f)
    return out, bad


def sample_paths(v, path, acc):
    if isinstance(v, dict):
        for k, x in v.items():
            p = path + "." + k
            acc.setdefault(p, []).append(x)
            if isinstance(x, dict):
                sample_paths(x, p, acc)
            elif isinstance(x, list):
                for e in x:
                    if isinstance(e, dict):
                        sample_paths(e, p + "[]", acc)
    return acc


def fits(x, kind):
    base = kind.rstrip("?")
    if x is None:
        return kind.endswith("?")
    if base == "string":
        return isinstance(x, str)
    if base == "int":
        return isinstance(x, int) and not isinstance(x, bool)
    if base == "bool":
        return isinstance(x, bool)
    if base == "[string]":
        return isinstance(x, list) and all(isinstance(e, str) for e in x)
    if base == "[object]":
        return isinstance(x, list) and all(isinstance(e, dict) for e in x)
    if base == "object":
        return isinstance(x, dict)
    return False


def check(code, sample):
    if code is None:
        return ["parse: the markers VIEW-CONTRACT BEGIN and END are not in Business.kt"]
    reads, bad = reads_of(code)
    acc = sample_paths(sample, "$", {})
    for p in sorted(set(acc) - set(reads)):
        bad.append("%s: the core writes it, BizView does not read it" % p)
    for p in sorted(set(reads) - set(acc)):
        bad.append("%s: BizView reads it (as %s), the core does not write it" % (p, reads[p]))
    for p in sorted(set(reads) & set(acc)):
        kind = reads[p]
        for x in acc[p]:
            if x is None and not kind.endswith("?"):
                bad.append("%s: null in the core's view, but BizView reads it as a required %s" % (p, kind))
                break
            if not fits(x, kind):
                bad.append("%s: BizView reads %s, the core writes %r" % (p, kind, x))
                break
        if kind.startswith("[object]") and not any(isinstance(x, list) and x for x in acc[p]):
            bad.append("%s: never met -- an empty array in the sample, its objects unchecked" % p)
    return bad


def probes(code, sample):
    """Each probe must be refused: a guard that lets one through proves nothing."""
    def fresh():
        return json.loads(json.dumps(sample))

    def misspelt():
        return code.replace('"at_ms"', '"at_mz"', 1), sample

    def dropped():
        s = fresh()
        s["members"] = [{k: x for k, x in m.items() if k != "card"} for m in s["members"]]
        return code, s

    def added():
        s = fresh()
        s["org"]["colour"] = "red"
        return code, s

    def nulled():
        s = fresh()
        s["members"][0]["name"] = None
        return code, s

    def retyped():
        s = fresh()
        s["members"][0]["joined_ms"] = "yesterday"
        return code, s

    def raw():
        return code.replace('head = o.str("head")', 'head = o.optString("head")', 1), sample

    out = []
    made = [("a key misspelt in the parse", misspelt), ("a key the core stops writing", dropped), ("a key the core adds", added),
            ("null in a required value", nulled), ("a value of another kind", retyped), ("a raw read in the parse", raw)]
    for name, make in made:
        c, s = make()
        if c == code and s == sample:
            out.append("probe %s: it changed nothing -- the probe is stale" % name)
        elif not check(c, s):
            out.append("probe %s: let through -- the guard does not hold" % name)
    return out, len(made)


def main():
    found = sorted(glob.glob(os.path.join(SAMPLES, "mt-biz-view.*.json")))
    if len(found) != 1:
        print("VIEW-CONTRACT x exactly one sample of the core's view expected in scripts/contracts, found %d" % len(found))
        return 2
    name = os.path.basename(found[0])
    core = json.load(open(TOOLCHAIN, encoding="utf-8"))["protocol"]["commit"]
    sha = name[len("mt-biz-view."):-len(".json")]
    if len(sha) < 7 or not core.startswith(sha):
        print("VIEW-CONTRACT x the sample %s was taken on another core than the pinned %s: take it again "
              "(SAMPLE_OUT, the core's tests/sample_view.rs)" % (name, core[:12]))
        return 2
    sample = json.load(open(found[0], encoding="utf-8"))
    code = block(open(SOURCE, encoding="utf-8").read())
    bad = check(code, sample)
    if bad:
        print("VIEW-CONTRACT x BizView and the core's view (%s) disagree:" % name)
        for b in bad:
            print("  " + b)
        return 2
    failed, count = probes(code, sample)
    if failed:
        print("VIEW-CONTRACT x the guard does not prove itself:")
        for b in failed:
            print("  " + b)
        return 2
    reads, _ = reads_of(code)
    print("VIEW-CONTRACT: BizView reads every key of the core's view %s and nothing else, at every depth (%d keys); "
          "refused all %d probes" % (name, len(reads), count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
