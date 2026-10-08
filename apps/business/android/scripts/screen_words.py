#!/usr/bin/env python3
"""NO WORD OF BUSINESS ON THE SCREEN (Montana Business for Android; iOS tools/mt-lang-check.py rule 4, bd6c2415).

The author's word 06.10.2026 10:1x MSK: «you write no mention of business in the app, you build only the infrastructure
for it»; 10:2x: «the app MT Business, the sections as in the Messenger». The screen takes its words from the resources
(app/src/main/res/values*/): no string of them, no item of a plural or of an array says business -- the English word, the
Russian one and the Chinese ones (written below by code points: the source is English), in any case, plainly or by a
backslash-u escape. Two exceptions, each by name: the app's own name under the icon (app_name, «MT Business», the author's
word 02:4x; iOS keeps it in InfoPlist.strings, beside the catalogue its guard reads) and a business card, which is a
person's card, not a trade. The resources' names are identifiers, never shown, and are not read.

The guard proves itself on probes -- each word in each language, a capital, another case, an escape, a plural's item, the
word beside a card, the app's name under another name -- each of which it must refuse, and the two exceptions, which it
must let through. Exit 2 with the list.

    python3 scripts/screen_words.py              the working tree
    python3 scripts/screen_words.py --rev main   the resources as a commit holds them
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = "app/src/main/res"


def word(*points):
    return "".join(map(chr, points))


RU = word(0x431, 0x438, 0x437, 0x43D, 0x435, 0x441)
ZH = [word(0x5546, 0x52A1), word(0x5546, 0x4E1A), word(0x4F01, 0x4E1A)]
BUSINESS = re.compile(chr(124).join(["business", RU] + ZH), re.I)
CARD = re.compile("business card", re.I)
THE_APPS_NAME = "app_name"
ESCAPE = re.compile(r"\\u([0-9a-fA-F]{4})")
LISTS = ("plurals", "string-array", "array")


def shown(xml):
    """Every word a catalogue shows: (the resource's name, its words), a plural's and an array's items one by one."""
    out = []
    for e in ET.fromstring(xml):
        if e.tag == "string":
            out.append((e.get("name", ""), "".join(e.itertext())))
        elif e.tag in LISTS:
            out += [(e.get("name", ""), "".join(i.itertext())) for i in e]
    return out


def check(catalogues):
    bad = []
    for path in sorted(catalogues):
        try:
            words = shown(catalogues[path])
        except ET.ParseError as e:
            bad.append("%s: not read as resources (%s)" % (path, e))
            continue
        for name, text in words:
            if name == THE_APPS_NAME:
                continue
            plain = ESCAPE.sub(lambda m: chr(int(m.group(1), 16)), text)
            if BUSINESS.search(CARD.sub("", plain)):
                bad.append('%s: %s "%s" -- the word business on the screen (the app names no business)' % (path, name, text))
    return bad


def is_catalogue(rel):
    parts = rel.split("/")
    return len(parts) == 2 and (parts[0] == "values" or parts[0].startswith("values-")) and parts[1].endswith(".xml")


def tree_catalogues():
    out, res = {}, os.path.join(ROOT, RES)
    for d in sorted(os.listdir(res)):
        for f in sorted(os.listdir(os.path.join(res, d))) if os.path.isdir(os.path.join(res, d)) else []:
            if is_catalogue(d + "/" + f):
                out[d + "/" + f] = open(os.path.join(res, d, f), "rb").read()
    return out


def git(args):
    # the shared host checks trees another builder may own; the tree is named, so git trusts exactly it
    r = subprocess.run(["git", "-c", "safe.directory=" + ROOT, "-C", ROOT] + args, capture_output=True)
    if r.returncode:
        raise SystemExit("git %s: %s" % (" ".join(args), r.stderr.decode("utf-8", "replace").strip()))
    return r.stdout


def rev_catalogues(rev):
    out = {}
    for path in git(["ls-tree", "-r", "--name-only", rev, "--", RES]).decode("utf-8").splitlines():
        rel = path[len(RES) + 1:]
        if is_catalogue(rel):
            out[rel] = git(["show", rev + ":" + path])
    return out


def probe(name, words, plural=False, folder="values"):
    root = ET.Element("resources")
    if plural:
        ET.SubElement(ET.SubElement(root, "plurals", name=name), "item", quantity="other").text = words
    else:
        ET.SubElement(root, "string", name=name).text = words
    return {folder + "/probe.xml": ET.tostring(root, encoding="utf-8")}


def probes():
    """Each probe must be refused, the two exceptions let through: a guard that errs on one proves nothing."""
    made = [
        ("the English word", probe("p", "Business"), True),
        ("the English word in a sentence", probe("p", "Sign in to Montana business"), True),
        ("the Russian word with a capital", probe("p", word(0x411) + RU[1:], folder="values-ru"), True),
        ("the Russian word in another case", probe("p", RU + word(0x430), folder="values-ru"), True),
        ("a backslash-u escape", probe("p", "\\u%04x" % ord(RU[0]) + RU[1:], folder="values-ru"), True),
        ("a plural's item", probe("p", "%d businesses", plural=True), True),
        ("the word beside a business card", probe("p", "Business card of a business"), True),
        ("the app's name under another name", probe("app_title", "MT Business"), True),
        ("a business card", probe("p", "Business card"), False),
        ("the app's own name", probe(THE_APPS_NAME, "MT Business"), False),
    ] + [("the Chinese word %d" % (i + 1), probe("p", "Montana " + z, folder="values-zh-rCN"), True) for i, z in enumerate(ZH)]
    out = []
    for name, catalogue, refused in made:
        if bool(check(catalogue)) != refused:
            out.append("probe %s: %s -- the guard does not hold" % (name, "let through" if refused else "refused"))
    return out, len(made)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--rev", help="read the resources as this commit holds them, not the working tree")
    a = ap.parse_args(argv)
    catalogues = rev_catalogues(a.rev) if a.rev else tree_catalogues()
    where = a.rev or "the working tree"
    if not catalogues:
        print("SCREEN-WORDS x no catalogue under %s in %s" % (RES, where))
        return 2
    bad = check(catalogues)
    if bad:
        print("SCREEN-WORDS x the screen names business (%s):" % where)
        for b in bad:
            print("  " + b)
        return 2
    failed, count = probes()
    if failed:
        print("SCREEN-WORDS x the guard does not prove itself:")
        for b in failed:
            print("  " + b)
        return 2
    words = sum(len(shown(x)) for x in catalogues.values())
    print("SCREEN-WORDS: no word of business in %d catalogues of %s (%d words), the app's name and a business card aside; "
          "held all %d probes" % (len(catalogues), where, words, count))
    return 0


if __name__ == "__main__":
    sys.exit(main())
