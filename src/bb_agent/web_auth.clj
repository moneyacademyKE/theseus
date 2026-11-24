(ns bb-agent.web-auth
  "Master-password auth for the web deck.

  One secret the human chose. The password itself crosses the wire only
  on POST /api/login; on disk there is a PBKDF2-SHA256 salt+hash record
  (never the password), and in state/web-sessions.edn only SHA-256
  token hashes are persisted. A restart or a password change clears
  matching sessions, so no session ever outlives the secret that minted it."
  (:require [babashka.fs :as fs]
            [bb-agent.atomic-io :as aio]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bb-agent.config :as config])
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto SecretKeyFactory)
           (javax.crypto.spec PBEKeySpec)))

(def default-iterations 600000)
(def session-ttl-ms (* 7 24 60 60 1000))

;; ---------- time + encoding utilities ----------

(defn- now [] (System/currentTimeMillis))

(defn- b64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn- hex-bytes [^bytes bs]
  (apply str (map #(format "%02x" (bit-and 0xFF (long %))) bs)))

(defn- new-token
  "32 random bytes as hex — guessing is hopeless, timing is useless."
  []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (hex-bytes bs)))

(defn- token-hash
  "SHA-256 digest of a raw bearer token so persisted session tables
  never hold raw credentials on disk."
  [token]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (str "sha256:" (hex-bytes (.digest md (.getBytes (str token) StandardCharsets/UTF_8))))))

(def ^:private write-private-file! aio/write-private-file!)

;; ---------- password files ----------

(defn password-file
  "Path of the master-password record: salt + PBKDF2 hash, owner-only."
  []
  (fs/path (config/home) ".web-pass.edn"))

(defn- ro-password-file
  "The optional read-only password record: same shape, second secret.
  Absent file = no read-only access, which is the default."
  []
  (fs/path (config/home) ".web-pass-ro.edn"))

(defn password-set? []
  (fs/exists? (password-file)))

(defn ro-password-set? []
  (fs/exists? (ro-password-file)))

;; ---------- sessions (durable, role-carrying) ----------

(def sessions
  "Live session tokens -> {:expires epoch-ms, :role :owner|:ro}.
  In memory tokens issued in this process live under their token key (so
  tests aging an active token by key work seamlessly); on disk every key
  is stored as sha256:<hex>, and lookup-session accepts both shapes."
  (atom {}))

(defn- sessions-file
  "state/web-sessions.edn — the durable login table."
  []
  (fs/path (config/home) "state" "web-sessions.edn"))

(defn- persist-sessions!
  "Write the live session table to disk (hashed token keys, owner-only
  perms from birth, expired entries pruned). Best-effort: a durability
  write must never fail a login."
  []
  (try
    (let [t (now)
          live (into {}
                     (keep (fn [[k v]]
                             (when (and (:expires v) (> (:expires v) t))
                               [(if (str/starts-with? (str k) "sha256:")
                                  k
                                  (token-hash k))
                                v])))
                     @sessions)]
      (write-private-file! (sessions-file) (pr-str live)))
    (catch Exception _ nil)))

(defn reset-sessions! []
  (reset! sessions {}))

(defn- lookup-session [token]
  (when (string? token)
    (let [m @sessions]
      (or (get m token)
          (get m (token-hash token))))))

;; ---------- cross-process rotation watch (issue #38) ----------

(def ^:private password-record-mtime
  "The owner password record's mtime as this process last saw it."
  (atom nil))

(def ^:private ro-password-record-mtime
  "The read-only password record's mtime as this process last saw it."
  (atom nil))

(defn- record-mtime
  "Current mtime of a password record, nil when no record exists."
  [file]
  (try
    (when (fs/exists? file)
      (fs/last-modified-time file))
    (catch Exception _ nil)))

(defn- revoke-ro-sessions!
  "Drop every :ro session while leaving :owner sessions untouched."
  []
  (swap! sessions (fn [m]
                    (into {} (remove (fn [[_ v]] (= :ro (:role v))) m))))
  (persist-sessions!))

(defn- sync-password-record!
  "Notice an out-of-process rotation of either password record: owner
  rotation kills every live session; RO rotation or removal kills every
  :ro session."
  []
  (try
    (let [mtime (record-mtime (password-file))
          ro-mtime (record-mtime (ro-password-file))
          prev-ro @ro-password-record-mtime]
      (when (and mtime (not= @password-record-mtime mtime))
        (reset! password-record-mtime mtime)
        (reset-sessions!)
        (persist-sessions!))
      (when (not= prev-ro ro-mtime)
        (reset! ro-password-record-mtime ro-mtime)
        (when (some? prev-ro)
          (revoke-ro-sessions!))))
    (catch Exception _ nil)))

(defn load-sessions!
  "Reload the durable session table (boot after a restart). Entries
  whose TTL passed while the deck was down die here — honest expiry,
  not resurrection."
  []
  (let [t (now)
        raw (try (edn/read-string (slurp (str (sessions-file))))
                 (catch Exception _ {}))
        loaded (if (map? raw) raw {})]
    (reset! sessions
            (into {}
                  (remove (fn [[_ v]] (or (not (map? v)) (nil? (:expires v)) (<= (:expires v) t))))
                  loaded)))
  (reset! password-record-mtime (record-mtime (password-file)))
  (reset! ro-password-record-mtime (record-mtime (ro-password-file)))
  @sessions)

(defn session-live?
  "True when this session token exists and has not expired. Every check
  first notices an out-of-process password rotation."
  [token]
  (sync-password-record!)
  (boolean (when-let [{:keys [expires]} (lookup-session token)]
             (and expires (> expires (now))))))

(defn session-role
  ":owner or :ro for a live token, nil otherwise. Fails closed on
  missing or unrecognized roles — never promotes an unknown role."
  [token]
  (when (session-live? token)
    (let [role (:role (lookup-session token))]
      (when (contains? #{:owner :ro} role) role))))

(defn logout!
  "Revoke one token. The browser logout button — the durable table
  updates so a restart does not resurrect a logged-out tab."
  [token]
  (when (string? token)
    (swap! sessions dissoc token (token-hash token))
    (persist-sessions!)
    true))

;; ---------- hashing + password management ----------

(defn derive-key
  "Pure PBKDF2-HMAC-SHA256. Iterations are an argument so tests can run
  cheap ones; the on-disk record decides at verify time."
  [^String password ^bytes salt iters]
  (let [spec (PBEKeySpec. (char-array password) salt (int iters) (int 256))
        factory (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")]
    (.getEncoded (.generateSecret factory spec))))

(defn hash-password
  "The EDN record to store for a password: random 16-byte salt,
  PBKDF2 hash, iteration count."
  ([password] (hash-password password default-iterations))
  ([password iters]
   (let [salt (byte-array 16)]
     (.nextBytes (SecureRandom.) salt)
     {:salt (b64 salt)
      :hash (b64 (derive-key password salt iters))
      :iterations iters})))

(defn verify-password
  "Constant-time check of a password against a stored record. False on
  any malformed input, never an exception — login is a predicate."
  [password {:keys [salt hash iterations]}]
  (and (string? password) (string? salt) (string? hash)
       (pos-int? iterations)
       (try
         (MessageDigest/isEqual
          (derive-key password (.decode (Base64/getDecoder) ^String salt) iterations)
          (.decode (Base64/getDecoder) ^String hash))
         (catch Exception _ false))))

;; ---------- login ----------

(def ^:private max-login-failures 5)
(def ^:private login-lockout-ms 60000)

(def ^:private login-failures
  "Consecutive-failure counter for the login endpoint."
  (atom {:count 0 :locked-until 0}))

(defn reset-login-failures!
  "Tests: clear the lockout so a suite run never inherits it."
  []
  (reset! login-failures {:count 0 :locked-until 0}))

(defn set-password!
  "Hash and persist the master password (atomic owner-only perms), clear
  every live session, and reset login lockout."
  [password]
  (let [rec (hash-password password)]
    (write-private-file! (password-file) (pr-str rec))
    (reset! password-record-mtime (record-mtime (password-file)))
    (reset-sessions!)
    (persist-sessions!)
    (reset-login-failures!)
    rec))

(defn- mint-session!
  "Register a token with a role and persist the table."
  [role]
  (let [token (new-token)
        expires (+ (now) session-ttl-ms)]
    (swap! sessions assoc token {:expires expires :role role})
    (persist-sessions!)
    {:token token :expires expires :role (name role)}))

(defn set-ro-password!
  "Owner action: define/clear the read-only password. A blank/nil password
  REMOVES the record. Always revokes existing :ro sessions immediately."
  [password]
  (if (str/blank? password)
    (do
      (when (fs/exists? (ro-password-file))
        (fs/delete (ro-password-file)))
      (reset! ro-password-record-mtime nil))
    (do
      (write-private-file! (ro-password-file) (pr-str (hash-password password)))
      (reset! ro-password-record-mtime (record-mtime (ro-password-file)))))
  (revoke-ro-sessions!)
  nil)

(defn- read-record [file]
  (try (edn/read-string (slurp (str file)))
       (catch Exception _ nil)))

(defn login!
  "Verify the master password against the record on disk; mint a session
  token on success. Owner password -> :owner role; otherwise the
  read-only password (if one exists) -> :ro role."
  [password]
  (sync-password-record!)
  (let [{:keys [locked-until]} @login-failures]
    (if (> locked-until (now))
      {:locked true :retry-ms (- locked-until (now))}
      (if-let [role (cond
                      (and (password-set?)
                           (verify-password password (read-record (password-file))))
                      :owner

                      (and (ro-password-set?)
                           (verify-password password (read-record (ro-password-file))))
                      :ro)]
        (let [t (now)]
          (swap! sessions (fn [m]
                            (into {} (remove (fn [[_ v]] (<= (:expires v) t))) m)))
          (reset-login-failures!)
          (mint-session! role))
        (do (swap! login-failures
                   (fn [{:keys [count]}]
                     (let [count (inc (long count))]
                       (if (>= count max-login-failures)
                         {:count count :locked-until (+ (now) login-lockout-ms)}
                         {:count count :locked-until 0}))))
            nil)))))

(defn rotate-password!
  "Owner action from the browser: verify the CURRENT password, replace
  the record, kill every session, then hand back a fresh owner token."
  [{:keys [current new]}]
  (when-not (and (string? current) (string? new) (seq new))
    (throw (ex-info "rotation needs current and new passwords" {:status 400})))
  (when-not (verify-password current (read-record (password-file)))
    (throw (ex-info "current password does not match" {:status 400})))
  (set-password! new)
  (mint-session! :owner))
