(ns bb-agent.web-auth
  "Master-password auth for the web deck.

  One secret the human chose. The password itself crosses the wire only
  on POST /api/login; on disk there is a PBKDF2-SHA256 salt+hash record
  (never the password), and in memory a session table of random tokens
  with a 7-day TTL. A restart or a password change clears every session,
  so no session ever outlives the secret that minted it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [bb-agent.config :as config])
  (:import (javax.crypto SecretKeyFactory)
           (javax.crypto.spec PBEKeySpec)
           (java.security SecureRandom MessageDigest)
           (java.util Base64)))

(def default-iterations 600000)
(def session-ttl-ms (* 7 24 60 60 1000))

;; ---------- time + encoding utilities ----------

(defn- now [] (System/currentTimeMillis))

(defn- b64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn- new-token
  "32 random bytes as hex — guessing is hopeless, timing is useless."
  []
  (let [bs (byte-array 32)]
    (.nextBytes (SecureRandom.) bs)
    (apply str (map #(format "%02x" (bit-and 0xFF (long %))) bs))))

;; ---------- sessions (durable, role-carrying) ----------

(def sessions
  "Live session tokens -> {:expires epoch-ms, :role :owner|:ro}.
  Durable: mints and revocations persist to state/web-sessions.edn so a
  restart keeps the deck logged in (parity row 18). A restart or a
  password change still clears what the record says should be cleared —
  durability never outlives the secret that minted the token. Public for
  tests (aging a token) and for reset-sessions!."
  (atom {}))

(defn- sessions-file
  "state/web-sessions.edn — the durable login table."
  []
  (fs/path (config/home) "state" "web-sessions.edn"))

(defn- persist-sessions!
  "Write the live session table to disk, owner-only perms, expired
  entries pruned. Best-effort: a durability write must never fail a
  login."
  []
  (try
    (let [live (into {} (remove (fn [[_ v]] (<= (:expires v) (now))) @sessions))
          path (str (sessions-file))]
      (fs/create-dirs (fs/parent (sessions-file)))
      (spit path (pr-str live))
      (try (fs/set-posix-file-permissions path "rw-------")
           (catch Exception _ nil)))
    (catch Exception _ nil)))

(defn load-sessions!
  "Reload the durable session table (boot after a restart). Entries
  whose TTL passed while the deck was down die here — honest expiry,
  not resurrection."
  []
  (reset! sessions
          (into {} (remove (fn [[_ v]] (<= (:expires v) (now))))
                (try (edn/read-string (slurp (str (sessions-file))))
                     (catch Exception _ {}))))
  @sessions)

(defn reset-sessions! []
  (reset! sessions {}))

(defn session-live?
  "True when this session token exists and has not expired."
  [token]
  (boolean (when (string? token)
             (when-let [{:keys [expires]} (get @sessions token)]
               (and expires (> expires (now)))))))

(defn session-role
  ":owner or :ro for a live token, nil otherwise. Roles split the deck:
  reads for both, writes for the owner (parity row 19)."
  [token]
  (when (session-live? token)
    (let [role (:role (get @sessions token))]
      (if (contains? #{:owner :ro} role) role :owner))))

(defn logout!
  "Revoke one token. The browser logout button — the durable table
  updates so a restart does not resurrect a logged-out tab."
  [token]
  (when (string? token)
    (swap! sessions dissoc token)
    (persist-sessions!)
    true))

;; ---------- password record on disk ----------

(defn password-file
  "Path of the master-password record: salt + PBKDF2 hash, owner-only."
  []
  (fs/path (config/home) ".web-pass.edn"))

(defn password-set? []
  (fs/exists? (password-file)))

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

(defn set-password!
  "Hash and persist the master password (owner-only perms), and clear
  every live session: a new secret invalidates the old ones."
  [password]
  (let [rec  (hash-password password)
        path (str (password-file))]
    (spit path (pr-str rec))
    (try (fs/set-posix-file-permissions path "rw-------")
         (catch Exception _ nil))
    (reset-sessions!)
    rec))

;; ---------- login ----------

(def ^:private max-login-failures 5)
(def ^:private login-lockout-ms 60000)

(def ^:private login-failures
  "Consecutive-failure counter for the login endpoint: the write surface
  made this door load-bearing, so guessing gets expensive — 5 misses
  lock it for a minute. In-memory: a restart clears it, and the password
  record on disk is untouched."
  (atom {:count 0 :locked-until 0}))

(defn reset-login-failures!
  "Tests: clear the lockout so a suite run never inherits it."
  []
  (reset! login-failures {:count 0 :locked-until 0}))

(defn- mint-session!
  "Register a token with a role and persist the table."
  [role]
  (let [token (new-token)
        expires (+ (now) session-ttl-ms)]
    (swap! sessions assoc token {:expires expires :role role})
    (persist-sessions!)
    {:token token :expires expires :role (name role)}))

(defn- ro-password-file
  "The optional read-only password record: same shape, second secret.
  Absent file = no read-only access, which is the default."
  []
  (fs/path (config/home) ".web-pass-ro.edn"))

(defn ro-password-set? []
  (fs/exists? (ro-password-file)))

(defn set-ro-password!
  "Owner action: define/clear the read-only password. A nil password
  REMOVES the record."
  [password]
  (if (str/blank? password)
    (when (fs/exists? (ro-password-file))
      (fs/delete (ro-password-file)))
    (let [path (str (ro-password-file))]
      (spit path (pr-str (hash-password password)))
      (try (fs/set-posix-file-permissions path "rw-------")
           (catch Exception _ nil)))))

(defn- read-record [file]
  (try (edn/read-string (slurp (str file)))
       (catch Exception _ nil)))

(defn login!
  "Verify the master password against the record on disk; mint a session
  token on success. Owner password → :owner role; otherwise the
  read-only password (if one exists) → :ro role. Returns
  {:token .. :expires .. :role ..}, {:locked true :retry-ms n} while
  rate-limited, or nil on a bad password. Expired entries are pruned on
  every attempt; a success resets the counter."
  [password]
  (let [{:keys [locked-until]} @login-failures]
    (if (> locked-until (now))
      {:locked true :retry-ms (- locked-until (now))}
      (if-let [session (when-let [role (cond
                                          (and (password-set?)
                                               (verify-password password (read-record (password-file))))
                                          :owner

                                          (and (ro-password-set?)
                                               (verify-password password (read-record (ro-password-file))))
                                          :ro)]
                         (do (swap! sessions (fn [m]
                                               (into {} (remove (fn [[_ v]]
                                                                  (<= (:expires v) (now)))) m)))
                             (mint-session! role)))]
        (do (reset! login-failures {:count 0 :locked-until 0})
            session)
        (do (swap! login-failures
                   (fn [{:keys [count]}]
                     (let [count (inc (long count))]
                       (if (>= count max-login-failures)
                         {:count count :locked-until (+ (now) login-lockout-ms)}
                         {:count count :locked-until 0}))))
            nil)))))

(defn rotate-password!
  "Owner action from the browser: verify the CURRENT password, replace
  the record, kill every session, then hand back a fresh owner token so
  the rotating browser stays logged in — every other session (and the
  read-only role's tokens) died with the old secret. Throws ex-info
  {:status 400} on a wrong current password."
  [{:keys [current new]}]
  (when-not (and (string? current) (string? new) (seq new))
    (throw (ex-info "rotation needs current and new passwords" {:status 400})))
  (when-not (verify-password current (read-record (password-file)))
    (throw (ex-info "current password does not match" {:status 400})))
  (set-password! new)
  (mint-session! :owner))
