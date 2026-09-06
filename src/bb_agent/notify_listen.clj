(ns bb-agent.notify-listen
  "W3 (2026-09-06): the goal runner's halt notifier (bb-agent.goal.notify) POSTs an EDN halt
  message to a localhost URL; this listener receives it, formats one Telegram
  page, and sends it through the normal delivery ladder. A goal that dies at
  3am now pages the owner instead of writing a bundle nobody reads.

  The transport is injected everywhere except -main: format-page and
  make-handler are pure (a 1-arg send! fn), so tests need no token."
  (:require [bb-agent.config :as config]
            [bb-agent.telegram-delivery :as delivery]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def ^:private default-port 7787)

(defn format-page
  "PURE: an bb-agent.goal.notify/build-message map -> one Telegram page string."
  [{:keys [name reason iterations bundle-path ts]}]
  (str "🚨 goal halt — " (or name "<unnamed>") "\n"
       "reason: " (or reason "unknown") "\n"
       "iterations: " (or iterations "?") "\n"
       "bundle: " (or bundle-path "none")
       (when ts (str "\nts: " ts))))

(defn- unhex
  "Hex string -> byte array (signatures arrive as hex)."
  [s]
  (into-array Byte/TYPE
              (map (fn [[a b]] (unchecked-byte (Integer/parseInt (str a b) 16)))
                   (partition 2 s))))

(defn- signature-valid?
  "Constant-time HMAC-SHA256 check via the buddy pod. Fails closed: any nil,
  blank, odd-length, or malformed input is invalid."
  [secret body hex-sig]
  (and (string? hex-sig) (pos? (count hex-sig)) (even? (count hex-sig))
       (try (require '[pod.babashka.buddy.core.mac :as mac])
            (let [verify (resolve 'pod.babashka.buddy.core.mac/verify)]
              (boolean (verify (.getBytes ^String body)
                               (unhex hex-sig)
                               {:key (.getBytes ^String secret) :alg :hmac :sha :256})))
            (catch Exception _ false))))

(defn make-handler
  "Ring handler around a 1-arg `send!` (page-text -> receipt). 200 on a
  parsed-and-sent halt map, 400 on anything else, 403 on a bad or missing
  X-Signature when opts carry :hmac-secret. Any path accepted —
  this is a single-purpose server."
  ([send!] (make-handler send! nil))
  ([send! {:keys [hmac-secret] :as _opts}]
   (fn [{:keys [body headers]}]
     (let [body-str (slurp body)]
       (if (and hmac-secret
                (not (signature-valid? hmac-secret body-str (get headers "x-signature"))))
         {:status 403 :body "bad or missing signature"}
         (let [msg (try (edn/read-string body-str)
                        (catch Exception _ ::malformed))]
           (if (map? msg)
             (do (send! (format-page msg))
                 {:status 200 :body "paged"})
             {:status 400 :body "expected an EDN map"})))))))

(defn start!
  "Start the server on 127.0.0.1:port. Returns the httpkit stop-fn."
  ([send! port] (start! send! port nil))
  ([send! port opts]
   (http/run-server (make-handler send! opts) {:port port :ip "127.0.0.1"})))

(defn- page-sender
  "Bind the real transport: :telegram cfg for the delivery ladder, target
  chat from :notify. Prints the send receipt so the launchd log holds the
  proof of every page."
  [cfg]
  (let [tcfg    (:telegram cfg)
        chat-id (get-in cfg [:notify :chat-id])]
    (when (or (nil? chat-id) (str/blank? (get tcfg :token)))
      (throw (ex-info "notify listener needs :notify {:chat-id ...} and :telegram {:token ...} in the theseus config"
                      {:has-chat-id? (some? chat-id)
                       :has-token?   (not (str/blank? (get tcfg :token)))})))
    (fn [text]
      (let [receipt (delivery/send-message! tcfg chat-id text)]
        (println "[notify-listen] sent:" (pr-str receipt))
        (flush)
        receipt))))

(defn- parse-port
  "[--port N] in args -> N, else nil."
  [args]
  (when-some [i (first (keep-indexed (fn [idx a] (when (= "--port" a) idx)) args))]
    (some-> args (nth (inc i) nil) parse-long)))

(defn -main
  "bb notify-listen [--port N]. Loads the theseus config, binds localhost,
  blocks forever. launchd KeepAlive owns the restart story."
  [& args]
  (let [cfg   (config/load-config)
        port  (or (parse-port args)
                  (get-in cfg [:notify :port])
                  default-port)
        send! (page-sender cfg)]
    (start! send! port (select-keys (:notify cfg) [:hmac-secret]))
    (println "[notify-listen] listening on 127.0.0.1:" port
             "-> chat" (get-in cfg [:notify :chat-id]))
    (flush)
    @(promise)))
