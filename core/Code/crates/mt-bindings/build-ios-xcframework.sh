#!/bin/bash
# Rebuild MontanaBindings.xcframework (device-only arm64) with --features network.
# The header's one source: cbindgen (montana_ffi.h) plus the hand-kept mt_bindings.h and mt_business.h.
# THE OUTPUT IS NAMED BY THE CALLER (06.10.2026, Montana Business): the path written here before pointed at a folder that no
# longer exists. The first argument is the output and it must be a new path: this script never writes over a framework the
# caller holds; the caller sets the old one aside and puts the new one in its place. Without an argument (10.10.2026) the
# framework lands in Code/target/MontanaBindings.xcframework, the core's own build folder, where every client's
# scripts/build-core.sh takes it from -- that script calls this one bare, and the bare call stopped at the usage line.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="../.."
if [ -n "${1:-}" ]; then
  OUT_XC="$1"
  if [ -e "$OUT_XC" ]; then
    echo "REFUSED: $OUT_XC exists; set it aside first" >&2
    exit 2
  fi
else
  OUT_XC="$(cd "$ROOT" && pwd)/target/MontanaBindings.xcframework"
  rm -rf "$OUT_XC"   # the core's own build folder: the framework built here the last time
fi

# The header include/montana_ffi.h is kept by hand (patches): cbindgen 0.29.4 regresses the opaque typedef
# (WakeRegistry), so it is never regenerated here.

echo "[1/3] cargo build device (device only, no simulator) (aarch64-apple-ios)"
# Cargo reads every .cargo/config.toml from the directory it starts in upwards; inside Code/ the workspace's own file and
# the one above the client tree disagree on env.RUST_TEST_THREADS and cargo stops. From Code/'s parent only the one above
# applies, and the manifest is named.
( cd "$ROOT/.." && rustup run 1.92.0 cargo rustc --manifest-path Code/Cargo.toml -p mt-bindings --features network --release --target aarch64-apple-ios --crate-type staticlib )

echo "[2/3] headers dir + modulemap"
HDIR="$(mktemp -d)"
cp include/montana_ffi.h include/mt_bindings.h include/mt_business.h "$HDIR/"
cat > "$HDIR/module.modulemap" <<'EOF'
module MontanaBindings {
    header "mt_bindings.h"
    header "montana_ffi.h"
    header "mt_business.h"
    export *
}
EOF

echo "[3/3] create xcframework: $OUT_XC"
xcodebuild -create-xcframework \
  -library "$ROOT/target/aarch64-apple-ios/release/libmt_bindings.a" -headers "$HDIR" \
  -output "$OUT_XC"
echo "DONE xcframework"
