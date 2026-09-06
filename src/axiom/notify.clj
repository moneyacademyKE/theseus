(ns axiom.notify
  "Phase 3 -- notifier abstraction. Opt-in via config (:notify {:type :http :url ...})."
  (:require [clojure.string :as str]
            [clojure.pprint :as pp]
            [babashka.process :refer [sh]]))

;; babashka.http-client is bundled in bb >= 1.0. We require it lazily so the
;; namespace loads even in hosts that lack it (the :http transport then falls
;; back to curl, and :noop never touches it).
(defn- http-client-available? []
  (try (require '[babashka.http-client :as http]) true
       (catch Exception _ false)))

(defn build-message
  "PURE: build the halt notification message map from `cfg`, the `halt-action`
  (the :halt action returned by decide), and the diagnostic `bundle` written by
  log/halt-bundle!. No disk, no network. The message is plain data so any
  transport can serialise it (EDN/JSON/form-encoded) without a special shape."
  [cfg halt-action bundle]
  (let [reason     (:reason halt-action)
        log-dir    (:log-dir cfg)
        bundle-path (when log-dir (str log-dir "/halt-bundle.edn"))]
    {:name      (:name cfg "<unnamed>")
     :reason    (some-> reason name)
     :halt      (dissoc halt-action :type)
     :iterations (or (:iterations halt-action)
                     (:iteration (last (:iterations bundle))))
     :last-world (or (:world halt-action)
                     (:world (last (:iterations bundle))))
     :bundle-path bundle-path
     :ts        (:ts bundle)}))

(defn- hmac-hex
  "HMAC-SHA256(secret, body) as lowercase hex via the buddy pod (lazy require).
  Returns nil if the pod is unavailable -- the listener then fails the request
  closed (403), and the sender sees it in the response."
  [secret body]
  (try
    (require '[pod.babashka.buddy.core.mac :as mac])
    (let [mac (resolve 'pod.babashka.buddy.core.mac/hash)]
      (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                      (mac body {:key (.getBytes ^String secret) :alg :hmac :sha :256}))))
    (catch Exception _ nil)))

(defn- post-edn!
  "POST `message` as EDN to (:url spec). When the spec carries :hmac-secret,
  the body is signed (HMAC-SHA256, hex, X-Signature header) so the listener
  can reject forgeries. Returns the response (map with :status / :body) --
  never throws out of notify! on transport failure; the error is captured in
  the return."
  [spec message]
  (let [url  (:url spec)
        body (with-out-str (pp/pprint message))
        sig  (when-let [s (:hmac-secret spec)] (hmac-hex s body))]
    (if (http-client-available?)
      (let [http (resolve 'babashka.http-client/post)]
        (try (let [res (http url {:headers (cond-> {"Content-Type" "application/edn"}
                                            sig (assoc "X-Signature" sig))
                                  :body body})]
               {:status (:status res) :body (str (:body res))})
             (catch Exception e
               {:status 0 :error (ex-message e)})))
      (try (let [args (cond-> ["curl" "-sS" "-m" "10" "-X" "POST"
                               "-H" "Content-Type: application/edn"]
                        sig (into ["-H" (str "X-Signature: " sig)]))
                 res  (apply sh {:out :string :err :string :continue true}
                             (into args ["--data-binary" "@-" "-w" "\n%{http_code}" url])
                             :in body)]
            {:status (-> (:out res) str/split-lines last Integer/parseInt)
             :body (-> (:out res) str/split-lines butlast (->> (str/join "\n")))})
           (catch Exception e
             {:status 0 :error (ex-message e)})))))

(defn notify!
  "Dispatch the halt notification per (-> cfg :notify). No-op (returns nil)
  when `:notify` is absent -- the loop's Phase 0/1/2 configs and tests are
  therefore unchanged. Returns the built message (for :noop) or the HTTP
  response (for :http); nil when there is nothing to do."
  [cfg halt-action bundle]
  (when-let [spec (:notify cfg)]
    (let [msg (build-message cfg halt-action bundle)
          transport (:transport spec)   ;; test override: a 1-arg fn
          type   (:type spec)]
      (cond
        (fn? transport) (transport msg)
        (= type :http)  (post-edn! spec msg)
        (= type :noop)  msg                       ;; test transport, no network
        :else           (do (println "[axiom.notify] unknown transport type:" type)
                            msg)))))