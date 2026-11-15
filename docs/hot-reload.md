# Theseus hot-code reload

Theseus hot reload is an opt-in development supervisor. It watches the source, scripts, Webdeck assets, skills, config, and launchd plist trees, then restarts only the affected long-lived service through launchd.

## Enable

Load `ops/launchd/com.theseus.hot-reload.plist` for the user launchd domain. The plist sets `THESEUS_HOT_RELOAD=1`, points at the Theseus checkout, and polls once per second. It is separate from `com.theseus.web` and `com.theseus.telegram`; those production plists are not modified.

To disable it, unload only `com.theseus.hot-reload`. Do not edit the production service plists to control this feature.

## Reload contract

| Change | Action |
|---|---|
| `src/`, `scripts/`, `config.edn`, or `skills/` | Restart Webdeck and Telegram |
| `web/` asset | Restart Webdeck |
| `ops/launchd/` plist | No automatic reload; manually reload the affected launchd job |
| runtime data and transcripts | No code reload; normal request/turn reads the data |

The supervisor snapshots file mtimes, detects creation and deletion, and applies the smallest service restart set. A failed restart is logged and the supervisor continues watching. The watcher itself exits unless `THESEUS_HOT_RELOAD=1` is explicitly present.

## Verification

Run `bb scripts/probe_reload.bb`. It checks the impact classifier, touches a Webdeck asset, waits for a changed Webdeck PID, and requires HTTP 200 from the local deck. Focused unit coverage is in `test/bb_agent/hot_reload_test.clj`.

This is development convenience, not production magic: code remains process state, and the restart boundary stays explicit and observable.
