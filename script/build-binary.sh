#!/usr/bin/env bash
# Theseus standalone executable builder — bb "app archive".
#
# Freezes a release tag into one self-contained executable:
#   1. git archive <tag> -> clean staging (no working-tree contamination)
#   2. stage a build-only entry shim (bb_agent/launcher.clj): --version/--help
#      answered locally, everything else -> bb-agent.cli, untouched by the tag
#   3. zip with src/* PROMOTED TO THE ARCHIVE ROOT (the zip root IS the
#      classpath), resource dirs alongside, META-INF/MANIFEST.MF last —
#      without the manifest bb silently falls back to REPL-on-EOF
#   4. append the zip after the bb binary; chmod +x
#   5. codesign --force --sign - (ad-hoc): exec works either way, but a
#      stale signature fails Gatekeeper on quarantined downloads. NOTE:
#      codesign --verify strict validation fails on the trailing archive
#      bytes (Mach-O conformance limit) — the receipt documents this.
#   6. smoke under env -i + a throwaway THESEUS_HOME (NOT HOME: bb's
#      user.home ignores the env var on macOS and would read real state),
#      with no bb on PATH; assert the APP answered, not bb's banner
#   7. shasum -a 256
#
# Usage: script/build-binary.sh [tag] [out-dir]
# Env:   BB_BIN  bb binary to embed (default: $(command -v bb))
# Pods:  go-sqlite3 (local ../.pods path, checkout-relative), buddy and
#        fswatcher (registry, auto-download on first use) resolve at
#        pod-load time exactly as under plain bb — see BUILD-RECEIPT.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-}"
[ -n "$TAG" ] || TAG="v$(sed -n 's/^.*def v "\(.*\)".*$/\1/p' "$ROOT/src/bb_agent/version.clj")"
OUT="${2:-$HOME/Theseus/build}"
BB_BIN="${BB_BIN:-$(command -v bb)}"
PLATFORM="macos-$(uname -m)"
EXE="$OUT/theseus-$TAG-$PLATFORM"

[ -x "$BB_BIN" ] || { echo "FATAL: BB_BIN not executable: $BB_BIN" >&2; exit 1; }
command -v zip >/dev/null || { echo "FATAL: zip required" >&2; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
git -C "$ROOT" archive "$TAG" | tar -x -C "$STAGE"
VERSION="$(sed -n 's/^.*def v "\(.*\)".*$/\1/p' "$STAGE/src/bb_agent/version.clj")"
[ -n "$VERSION" ] || { echo "FATAL: version.clj unreadable in $TAG" >&2; exit 1; }

cat > "$STAGE/src/bb_agent/launcher.clj" <<'LAUNCHER'
(ns bb-agent.launcher
  "Build-staged entry shim for the standalone executable.
  Packaging code only — deliberately NOT in the repo: the release tag stays
  frozen. Handles --version/--help locally, delegates everything else to
  bb-agent.cli exactly like: bb -m bb-agent.cli <args>."
  (:require [bb-agent.cli :as cli]
            [bb-agent.version :as version]))

(defn -main [& args]
  (case (first args)
    "--version" (println (str "theseus " version/v))
    "--help" (do (println "theseus — standalone executable (bb app archive)")
                 (println "Usage: theseus <command> [args...]")
                 (println "Dispatches exactly like: bb -m bb-agent.cli <command> [args...]")
                 (println (str "Version: " version/v)))
    (apply cli/-main args)))
LAUNCHER

mkdir -p "$STAGE/META-INF"
printf 'Manifest-Version: 1.0\nMain-Class: bb-agent.launcher\n' \
  > "$STAGE/META-INF/MANIFEST.MF"

mkdir -p "$OUT"
APP="$OUT/theseus-$TAG-app.zip"
rm -f "$APP"
( cd "$STAGE/src" && zip -qr "$APP" . )   # classpath root: the namespaces
( cd "$STAGE" && zip -qr "$APP" web skills configs ops script scripts bench \
    bb.edn config.example.edn AGENTS.md CHANGELOG.md README.md META-INF )
unzip -l "$APP" | grep -q "META-INF/MANIFEST.MF" || \
  { echo "FATAL: manifest missing from archive" >&2; exit 1; }

cp "$BB_BIN" "$EXE"
cat "$APP" >> "$EXE"
chmod +x "$EXE"
# Re-sign ad-hoc. codesign exits 1 on post-sign strict validation (trailing
# archive bytes past __LINKEDIT — Mach-O conformance, not a content failure);
# the signature IS written. The smoke test below is the empirical gate.
if command -v codesign >/dev/null; then
  codesign --force --sign - "$EXE" \
    || echo "WARN: ad-hoc re-sign failed strict validation (trailing bytes) — relying on smoke test" >&2
fi

# Smoke: empty env, throwaway THESEUS_HOME, no bb on PATH; assert the APP answered.
T="$(mktemp -d)"
V="$(env -i THESEUS_HOME="$T" PATH=/usr/bin:/bin "$EXE" --version)"
[ "$V" = "theseus $VERSION" ] || { echo "FATAL: smoke failed: got '$V'" >&2; exit 1; }
env -i THESEUS_HOME="$T" PATH=/usr/bin:/bin "$EXE" --help >/dev/null
env -i THESEUS_HOME="$T" PATH=/usr/bin:/bin "$EXE" session list >/dev/null

shasum -a 256 "$EXE" | tee "$EXE.sha256"
echo "OK: $EXE ($(file -b "$EXE" | cut -c1-50), $(du -h "$EXE" | cut -f1))"
