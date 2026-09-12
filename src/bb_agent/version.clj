(ns bb-agent.version
  "Single source of truth for the running Theseus release. Printed at
  poller boot (bk-173e) so 'what version is running' is answered by the
  log, not by ps and prayer. Bump together with the git tag.")
(def v "v1.1.0")
