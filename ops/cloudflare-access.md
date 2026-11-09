# Cloudflare Access on the deck tunnel

The deck binds 127.0.0.1 only and is reached through the cloudflared
tunnel (`ops/launchd/com.theseus.tunnel.plist`). Today its only gate is
the master password inside http-kit. Cloudflare Access adds an identity
check **at the edge** — before a byte reaches the machine — using the
same tunnel, no code, free under 50 users.

Why: it deletes attack surface instead of adding machinery. The
password gate stays as defense-in-depth (and for local LAN use);
Access means the open internet never even gets to attempt it.

## Setup (one-time, ~10 minutes in the Cloudflare dashboard)

1. **Zero Trust** → Access → Applications → **Add an application** →
   Self-hosted.
2. Application name: `theseus-deck`. Session Duration: e.g. 1 month.
3. **Public hostnames**: the hostname your tunnel already serves the
   deck on (the same one in `com.theseus.tunnel.plist` ingress).
4. **Add policy** — name it `owner`; Action: Allow; Include: your email
   (a Cloudflare account login — one-time PIN by email works, no
   IdP needed).
5. Save. The next visit to the deck hostname shows a Cloudflare login
   page first, then the deck's own password screen.

## What to expect

- Login once per session duration; Cloudflare sets its own cookie
  (`CF_Authorization`) at the edge.
- SSE works through Access — the tunnel streams fine; the deck's own
  `retry: 2000` and 15s pings keep the pipe healthy.
- Revoke access instantly from the dashboard (Access → Applications →
  the policy), independent of the master password.
- Local access (127.0.0.1) is untouched — Access only fronts the
  public hostname.

## Keep honest

- The master password gate is NOT removed — Access is a second door,
  not a replacement. Rotate the password with
  `bb scripts/set_web_password.bb` as usual.
- If the tunnel hostname changes, update the Access application's
  public hostname to match.

Sources: Cloudflare Zero Trust docs (Access applications, self-hosted;
free plan up to 50 users) — https://www.cloudflare.com/plans/zero-trust-services/
