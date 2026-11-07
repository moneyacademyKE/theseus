(ns bb-agent.web-auth
  "Master-password auth for the web deck.

  One secret the human chose. The password itself crosses the wire only
  on POST /api/login; on disk there is a PBKDF2-SHA256 salt+hash record
  (never the password), and in memory a session table of random tokens
  with a 7-day TTL. A restart or a password change clears every session,
  so no session ever outlives the secret that minted it."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
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

;; ---------- sessions (in-memory) ----------

(def sessions
  "Live session tokens -> expiry epoch-ms. Public for tests (aging a
  token) and for reset-sessions!. A restart empties it by construction."
  (atom {}))

(defn reset-sessions! []
  (reset! sessions {}))

(defn session-live?
  "True when this session token exists and has not expired."
  [token]
  (boolean (when (string? token)
             (let [exp (get @sessions token)]
               (and exp (> exp (now)))))))

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

(defn login!
  "Verify the master password against the record on disk; mint a session
  token on success. Returns {:token .. :expires ..}, {:locked true
  :retry-ms n} while rate-limited, or nil on a bad password. Expired
  entries are pruned on every attempt; a success resets the counter."
  [password]
  (let [{:keys [locked-until]} @login-failures]
    (if (> locked-until (now))
      {:locked true :retry-ms (- locked-until (now))}
      (if-let [session (when-let [rec (and (password-set?)
                                           (try (edn/read-string
                                                 (slurp (str (password-file))))
                                                (catch Exception _ nil)))]
                         (when (verify-password password rec)
                           (swap! sessions (fn [m]
                                             (into {} (remove (fn [[_ exp]] (<= exp (now)))) m)))
                           (let [token  (new-token)
                                 expires (+ (now) session-ttl-ms)]
                             (swap! sessions assoc token expires)
                             {:token token :expires expires})))]
        (do (reset! login-failures {:count 0 :locked-until 0})
            session)
        (do (swap! login-failures
                   (fn [{:keys [count]}]
                     (let [count (inc (long count))]
                       (if (>= count max-login-failures)
                         {:count count :locked-until (+ (now) login-lockout-ms)}
                         {:count count :locked-until 0}))))
            nil)))))
