(ns bb-agent.notify-listen
  "W3 (2026-09-06): axiom's halt notifier (axiom.notify) POSTs an EDN halt
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
  "PURE: an axiom.notify/build-message map -> one Telegram page string."
  [{:keys [name reason iterations bundle-path ts]}]
  (str "🚨 axiom halt — " (or name "<unnamed>") "\n"
       "reason: " (or reason "unknown") "\n"
       "iterations: " (or iterations "?") "\n"
       "bundle: " (or bundle-path "none")
       (when ts (str "\nts: " ts))))

(defn make-handler
  "Ring handler around a 1-arg `send!` (page-text -> receipt). 200 on a
  parsed-and-sent halt map, 400 on anything else. Any path accepted —
  this is a single-purpose server."
  [send!]
  (fn [{:keys [body]}]
    (let [msg (try (edn/read-string (slurp body))
                   (catch Exception _ ::malformed))]
      (if (map? msg)
        (do (send! (format-page msg))
            {:status 200 :body "paged"})
        {:status 400 :body "expected an EDN map"}))))

(defn start!
  "Start the server on 127.0.0.1:port. Returns the httpkit stop-fn."
  [send! port]
  (http/run-server (make-handler send!) {:port port :ip "127.0.0.1"}))

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
    (start! send! port)
    (println "[notify-listen] listening on 127.0.0.1:" port
             "-> chat" (get-in cfg [:notify :chat-id]))
    (flush)
    @(promise)))
