(ns bb-agent.tool.path
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

;; Path tools validate structure; approval mode decides whether a call runs.
;; Protected secret paths (config, web auth records, tokens, private keys)
;; stay off-limits to read/search/export tools even under :auto-safe or :auto-all.

(defn- safe-canonical
  "Resolve symlinks for p when possible, including canonicalizing an existing
  parent directory when the leaf file itself does not yet exist."
  [p]
  (let [target (fs/path (str p))]
    (cond
      (fs/exists? target)
      (fs/canonicalize target)

      :else
      (let [abs (fs/absolutize target)
            par (fs/parent abs)]
        (if (and par (fs/exists? par))
          (fs/path (fs/canonicalize par) (fs/file-name abs))
          abs)))))

(defn- theseus-protected-paths []
  (let [home (config/home)]
    (mapv #(safe-canonical (apply fs/path home %))
          [["config.edn"]
           ["config.last-good.edn"]
           [".web-pass.edn"]
           [".web-pass-ro.edn"]
           ["state" "web-sessions.edn"]
           ["state" "a2a-token"]
           ["state" "a2a-opencrabs-token"]])))

(defn- ssh-private-key? [canon user-home]
  (let [ssh-dir (safe-canonical (fs/path user-home ".ssh"))]
    (when (fs/starts-with? canon ssh-dir)
      (let [fname (str (fs/file-name canon))]
        (and (not (str/ends-with? fname ".pub"))
             (not (contains? #{"known_hosts" "known_hosts.old" "authorized_keys" "config"}
                             fname))
             (or (str/starts-with? fname "id_")
                 (boolean (re-matches #".*\.(pem|key)$" (str/lower-case fname)))
                 (boolean (re-find #"_(rsa|ed25519|ecdsa|dsa)$" fname))))))))

(defn protected-secret-path?
  "True when p (after symlink resolution) points to a Theseus secret/auth file
  or a standard user credential/private-key file."
  [p]
  (when-not (str/blank? (or (some-> p str) ""))
    (let [canon (safe-canonical p)
          user-home (System/getProperty "user.home")
          aws-creds (safe-canonical (fs/path user-home ".aws" "credentials"))
          netrc (safe-canonical (fs/path user-home ".netrc"))
          gnupg (safe-canonical (fs/path user-home ".gnupg"))]
      (boolean
       (or (some #(= canon %) (theseus-protected-paths))
           (= canon aws-creds)
           (= canon netrc)
           (fs/starts-with? canon gnupg)
           (ssh-private-key? canon user-home))))))

(defn checked-read-path [tool-name path]
  (cond
    (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})

    :else
    (let [target (fs/path path)]
      (if (fs/exists? target)
        target
        (common/error-result tool-name (str "File not found: " path) {:path path})))))

(defn checked-write-path [tool-name path]
  (if (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})
    (fs/path path)))

(defn file-too-large? [path max-bytes]
  (> (fs/size path) max-bytes))
