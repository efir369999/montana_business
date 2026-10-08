#!/usr/bin/env python3
"""Builds Montana Business for Android with the platform's own tools only -- no Gradle, no libraries -- the same on every computer.

    the core     cargo ndk   native/montana-jni   →  libmontana.so (the core's crates, built by the core's own Rust)
    resources    aapt2       app/src/main/res     →  R.java and a resource package
    R.java       javac
    our Kotlin   kotlinc
    dex          d8          the classes          →  classes.dex (the phone's bytecode)
    package      the dex and the core go into the resource package
    sign         zipalign + apksigner             →  build/MontanaBusiness.apk, with the Business key

Montana Business is a fork of the Messenger. The app's id is APPLICATION_ID, given at link time (aapt2
--rename-manifest-package) and filled into the manifest's ${applicationId}; the code's package stays quest.montana.app,
because the core's JNI names its classes and the Messenger's doors keep their symbols.

The tools are the ones scripts/toolchain.json pins (python3 scripts/setup.py puts them in place). Every step runs in a
fresh folder of its own, so nothing from an earlier build can ride into this one."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

import biz_view_contract
import screen_words
import toolchain

PIN, ROOT, HERE = toolchain.PIN, toolchain.ROOT, toolchain.HERE
MIN_SDK = "26"
FIXED_MOMENT = (1981, 1, 1, 1, 1, 2)
BUILD = os.path.join(ROOT, "build")
APPLICATION_ID = "xxx.montana.business"
APK = os.path.join(BUILD, "MontanaBusiness.apk")
# the Business's own key, outside every repository: the Messenger's key never signs this app
BUSINESS_KEY = os.path.expanduser("~/.montana/business/android/business.keystore")
JNI = os.path.join(ROOT, "native", "montana-jni")
CORE_SO = os.path.join(JNI, "target", "aarch64-linux-android", "release", "libmontana.so")
APP = os.path.join(ROOT, "app", "src", "main")
# the ceiling of threads the caller sets for the whole build (the shared Mac's queue sets CARGO_BUILD_JOBS: the author's word
# of 06.10.2026, «no more than 3 cores in total»); cargo reads it itself, d8 is handed it
JOBS = os.environ.get("CARGO_BUILD_JOBS", "")


def say(text):
    print(text, flush=True)


def run(args, env=None, cwd=ROOT):
    # the SDK's own scripts step back into the folder they started from, so they start from one every builder can enter
    r = subprocess.run(args, env=env, cwd=cwd)
    if r.returncode:
        raise SystemExit("failed (%d): %s" % (r.returncode, " ".join(args)))


def git(args, tree=ROOT):
    # the shared host builds in trees another builder may own; the tree is named, so git trusts exactly it
    r = subprocess.run(["git", "-c", "safe.directory=" + tree, "-C", tree] + args, capture_output=True, text=True)
    if r.returncode:
        raise SystemExit("git %s in %s: %s" % (" ".join(args), tree, r.stderr.strip()))
    return r.stdout.strip()


def files(top, suffix):
    found = []
    for d, _, names in os.walk(top):
        found += [os.path.join(d, n) for n in names if n.endswith(suffix)]
    return sorted(found)


def version():
    v = {}
    for line in open(os.path.join(HERE, "version"), encoding="utf-8"):
        line = line.strip()
        if line and not line.startswith("#"):
            key, _, value = line.partition("=")
            v[key.strip()] = value.strip()
    if v["TARGET"]:
        # catching up: our own build is the count of commits on main, and the iOS build we go to stands beside it
        code, target = int(git(["rev-list", "--count", "HEAD"])), int(v["TARGET"])
        if max(code, target) == code:
            raise SystemExit("own build %d reached %d: the jump to %d would go backwards (scripts/version)"
                             % (code, target, target))
        return code, "%s (%d → %d)" % (v["MARKETING"], code, target)
    # level with iOS: the number is the iOS build whose features Android has
    return int(v["BUILD"]), "%s (%s)" % (v["MARKETING"], v["BUILD"])


def core(p):
    pin = PIN["protocol"]
    if not os.path.isdir(p.protocol):
        raise SystemExit("the core's tree is not beside this repository: %s at %s (scripts/toolchain.json)"
                         % (p.protocol, pin["commit"]))
    head = git(["rev-parse", "HEAD"], p.protocol)
    changed = git(["status", "--porcelain", "--untracked-files=no"], p.protocol)
    if head != pin["commit"] or changed:
        raise SystemExit("the core's tree is %s%s; this build is pinned to %s (scripts/toolchain.json)"
                         % (head[:12], " with changes" if changed else "", pin["commit"][:12]))
    channel = p.rust_channel()
    say("core: Rust %s, NDK %s" % (channel, PIN["ndk"]))
    run([p.tool("cargo"), "+" + channel, "ndk", "-t", "arm64-v8a", "-P", MIN_SDK, "build", "--release"],
        env=p.env(), cwd=JNI)
    say("core: %s, %d bytes" % (os.path.relpath(CORE_SO, ROOT), os.path.getsize(CORE_SO)))


def signing_key(given):
    # an update installs only over the same signature, so every Business build is signed by the one Business key --
    # never by the Messenger's key, never by a debug key no later build could stand on
    key = given or BUSINESS_KEY
    if not os.path.exists(key) or not os.path.exists(key + ".pass"):
        raise SystemExit("no Business key at %s with its secret in %s.pass beside it (docs/BUILD.md)" % (key, key))
    return key


def filled_manifest(into):
    # the manifest names the app's id as ${applicationId}; the one place that gives it is APPLICATION_ID
    text = open(os.path.join(APP, "AndroidManifest.xml"), encoding="utf-8").read()
    text = text.replace("${applicationId}", APPLICATION_ID)
    if "${" in text:
        raise SystemExit("AndroidManifest.xml holds a placeholder this build does not fill")
    open(into, "w", encoding="utf-8").write(text)
    return into


def apk(p, code, name, key, check):
    env, bt, jar = p.env(), p.build_tools, p.android_jar
    with tempfile.TemporaryDirectory(prefix="montana-build-") as out:
        res, gen, classes, dex = [os.path.join(out, d) for d in ("res", "gen", "classes", "dex")]
        for d in (res, gen, classes, dex):
            os.makedirs(d)
        base = os.path.join(out, "base.apk")
        say("1/6 resources")
        # no crunching: a picture the author gave (the icon) goes into the package byte for byte, as every other one does
        run([os.path.join(bt, "aapt2"), "compile", "--no-crunch", "--dir", os.path.join(APP, "res"), "-o", res + "/compiled.zip"])
        run([os.path.join(bt, "aapt2"), "link", "-I", jar,
             "--manifest", filled_manifest(os.path.join(out, "AndroidManifest.xml")),
             "--rename-manifest-package", APPLICATION_ID,
             "--min-sdk-version", MIN_SDK, "--target-sdk-version", PIN["platform"].split("-")[1],
             "--version-code", str(code), "--version-name", name,
             "--java", gen, "--custom-package", "quest.montana.app", "-o", base, res + "/compiled.zip"])
        say("2/6 R.java")
        run([os.path.join(p.java_home, "bin", "javac"), "-nowarn", "--release", "17", "-cp", jar, "-d", classes]
            + files(gen, ".java"), env=env)
        say("3/6 Kotlin")
        r = subprocess.run([p.kotlinc, "-nowarn", "-jvm-target", "17", "-no-reflect", "-no-stdlib",
                            "-cp", os.pathsep.join([jar, p.stdlib, classes]), "-d", classes, "-Xjvm-default=all"]
                           + files(os.path.join(APP, "kotlin"), ".kt"), env=env, cwd=ROOT, capture_output=True, text=True)
        for line in (r.stdout + r.stderr).splitlines():
            if not line.startswith("warning:"):
                print(line)
        if r.returncode or not os.path.exists(os.path.join(classes, "quest", "montana", "app", "MainActivity.class")):
            raise SystemExit("Kotlin failed")
        if check:
            return say("the code compiles: %d classes" % len(files(classes, ".class")))
        say("4/6 dex")
        run([os.path.join(bt, "d8"), "--release", "--min-api", MIN_SDK, "--lib", jar, "--output", dex]
            + (["--thread-count", JOBS] if JOBS.isdigit() else [])
            + files(classes, ".class") + [p.stdlib], env=env)
        say("5/6 package")
        unsigned, aligned, signed = [os.path.join(out, n) for n in ("unsigned.apk", "aligned.apk", "signed.apk")]
        open(unsigned, "wb").write(open(base, "rb").read())
        # the library is stored, not deflated: the system maps it straight from the package, page-aligned below
        with zipfile.ZipFile(unsigned, "a") as z:
            for path, name, how in ((os.path.join(dex, "classes.dex"), "classes.dex", zipfile.ZIP_DEFLATED),
                                    (CORE_SO, "lib/arm64-v8a/libmontana.so", zipfile.ZIP_STORED)):
                # a fixed moment and mode in each entry, not the file's own: one commit makes one APK, byte for byte,
                # whoever builds it (a build as another user differed from root's only in these)
                entry = zipfile.ZipInfo(name, date_time=FIXED_MOMENT)
                entry.compress_type = how
                entry.external_attr = 0x81A40000   # a regular file, rw-r--r--
                z.writestr(entry, open(path, "rb").read())
        say("6/6 align + sign")
        run([os.path.join(bt, "zipalign"), "-f", "-P", "16", "4", unsigned, aligned])
        # the key keeps its secret in KEY.pass beside it; in its PKCS12 store the key's password is the store's,
        # and apksigner reads one file named twice line by line, so the file is named once
        secret = ["--ks-pass", "file:" + key + ".pass"]
        run([os.path.join(bt, "apksigner"), "sign", "--ks", key] + secret + ["--out", signed, aligned], env=env)
        os.makedirs(BUILD, exist_ok=True)
        open(APK, "wb").write(open(signed, "rb").read())
    say("   %s  %d bytes" % (os.path.relpath(APK, ROOT), os.path.getsize(APK)))


def digest(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def publish(directory, code, name):
    commit = git(["rev-parse", "HEAD"])
    place = os.path.join(directory, "%d-%s" % (code, commit[:7]))
    os.makedirs(place, exist_ok=True)
    target = os.path.join(place, "MontanaBusiness.apk")
    known = os.path.join(place, "build.json")
    if os.path.exists(known):
        before = json.load(open(known, encoding="utf-8"))["sha256"]
        if before != digest(APK):
            # one commit, one APK: other bytes for the same commit mean the build is not reproducible -- never laid over
            raise SystemExit("commit %s was built before into %s, now into %s: the build is not reproducible"
                             % (commit[:12], before[:16], digest(APK)[:16]))
    open(target, "wb").write(open(APK, "rb").read())
    record = dict(build=code, name=name, commit=commit, sha256=digest(target), apk=target,
                  time=time.strftime("%Y-%m-%d %H:%M:%S %Z"))
    open(os.path.join(place, "build.json"), "w", encoding="utf-8").write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    open(os.path.join(directory, "latest.json"), "w", encoding="utf-8").write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print("RESULT " + json.dumps(record, ensure_ascii=False), flush=True)


def install(p, path):
    adb = p.adb if os.path.exists(p.adb) else shutil.which("adb")
    if not adb:
        raise SystemExit("no adb: python3 scripts/setup.py --apk-only")
    run([adb, "install", "-r", path])
    run([adb, "shell", "am", "start", "-n", APPLICATION_ID + "/quest.montana.app.MainActivity"])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="resources and Kotlin only: the code compiles (no core)")
    mode.add_argument("--core-only", action="store_true", help="the core only")
    ap.add_argument("--keystore", help="sign with this key and its secret in KEY.pass beside it; default the Business "
                                       "key ~/.montana/business/android/business.keystore")
    ap.add_argument("--publish", metavar="DIR", help="also lay the APK in DIR/BUILD-COMMIT/ and DIR/latest.json")
    ap.add_argument("--install", action="store_true", help="install on the phone over adb and start it")
    a = ap.parse_args()
    p = toolchain.Paths()
    need = [p.java_home, p.kotlinc, p.build_tools, p.android_jar] if not a.core_only else []
    if not a.check:
        need.append(p.ndk)
    missing = [n for n in need if not os.path.exists(n)]
    if missing:
        raise SystemExit("the toolchain is not complete here (%s): python3 scripts/setup.py\n  " % p.host
                         + "\n  ".join(missing))
    code, name = version()
    say("Montana Business %s on %s" % (name, p.host))
    if not a.core_only and biz_view_contract.main():
        # C-2.1: the app's parse of the view and the core's own sample agree key for key, or nothing is built
        raise SystemExit("the view contract is broken: python3 scripts/biz_view_contract.py")
    if not a.core_only and screen_words.main([]):
        # the author's word 06.10.2026: the screen names no business -- the resources say none, or nothing is built
        raise SystemExit("the screen names business: python3 scripts/screen_words.py")
    if not a.check:
        core(p)
    if a.core_only:
        return
    apk(p, code, name, None if a.check else signing_key(a.keystore), a.check)
    if a.check:
        return
    if a.publish:
        publish(a.publish, code, name)
    if a.install:
        install(p, APK)


if __name__ == "__main__":
    main()
