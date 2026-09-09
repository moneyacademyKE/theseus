(ns bb-agent.bot-commands
  "Telegram command-menu registration (Bot API setMyCommands).

   Without registration, typing / in a chat shows nothing — every native
   command and skill is invisible to humans. Registered once at poller
   boot (and on demand via `bb telegram register-commands`); Telegram
   stores the list server-side, so no per-cycle work.

   Telegram command names allow only lowercase a-z, 0-9 and underscore
   (1-32 chars). Skill names with hyphens (deep-research) are registered
   as deep_research; resolve-skill maps them back so the router accepts
   both spellings. Descriptions truncate at 256; the list caps at 100."
  (:require [bb-agent.config :as config]
            [bb-agent.skill :as skill]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.client :as http]))

(def native-commands
  "The chat-command router's native surface, in menu order."
  [["new" "Start a fresh conversation"]
   ["reset" "Clear conversation state"]
   ["usage" "Show token usage"]
   ["autonomy" "Show RSI autonomy tier status"]
   ["goal" "Supervised goal build: /goal <what to build> · cancel · status <name> · resume <name>"]
   ["goals" "List goal runs and their status"]
   ["model" "Show or switch the LLM model"]
   ["skills" "List all commands and skills"]
   ["help" "List all commands and skills"]])

(def ^:private max-commands 100)
(def ^:private max-name-len 32)
(def ^:private max-desc-len 256)
(def ^:private description-budget 5000)
;; The count cap of 100 is not the only budget: Telegram rejects
;; setMyCommands when the COMBINED description length exceeds ~5000
;; chars. Empirical on this bot (2026-09-07): 4992 accepted, 5044
;; rejected with BOT_COMMANDS_TOO_MUCH. Long skill catalogs must share
;; the budget, not just the count.

(defn- fit-descriptions
  "Share the total description budget: native rows keep their full text,
   skills split what remains evenly, truncated with an ellipsis. The
   per-description 256-char Telegram cap still applies to every row."
  [native-rows skill-rows]
  (let [used (reduce + 0 (map (comp count second) native-rows))
        remaining (max 0 (- description-budget used))
        per-skill (if (seq skill-rows) (max 1 (quot remaining (count skill-rows))) 0)
        truncate (fn [d]
                   (let [cap (min max-desc-len per-skill)]
                     (if (<= (count d) cap)
                       d
                       (str (subs d 0 (max 0 (dec cap))) "…"))))]
    (concat native-rows (map (fn [[n d]] [n (truncate d)]) skill-rows))))

(defn telegram-command-name
  "Make a skill or command name Telegram-valid: lowercase, every char
   outside [a-z0-9_] becomes underscore, truncated to 32 chars."
  [s]
  (let [n (-> (or s "") str/lower-case (str/replace #"[^a-z0-9_]" "_"))]
    (subs n 0 (min max-name-len (count n)))))

(defn resolve-skill
  "Match a chat command name to a skill. Exact match wins; if the name
   has underscores and no exact match, retry with hyphens — the Telegram
   menu can only spell deep-research as deep_research."
  [skills cmd-name]
  (or (first (filter #(= cmd-name (:name %)) skills))
      (when (and cmd-name (str/includes? cmd-name "_"))
        (let [hyphenated (str/replace cmd-name "_" "-")]
          (first (filter #(= hyphenated (:name %)) skills))))))

(defn build-commands
  "Pure: native commands + skills → setMyCommands payload. Native names
   win collisions; skills dedupe by converted name; blank descriptions
   get a fallback; output capped at 100 entries."
  [skills]
  (let [native-names (set (map first native-commands))
        skill-rows (->> skills
                        (keep (fn [{:keys [name description]}]
                                (let [n (telegram-command-name name)]
                                  (when-not (or (str/blank? n) (contains? native-names n))
                                    [n (if (str/blank? description)
                                         (str "Skill: " name)
                                         description)])))))]
    (->> (fit-descriptions native-commands skill-rows)
         (reduce (fn [{:keys [seen out]} [n d]]
                   (if (contains? seen n)
                     {:seen seen :out out}
                     {:seen (conj seen n)
                      :out (conj out {:command n
                                      :description (subs d 0 (min max-desc-len (count d)))})}))
                 {:seen #{} :out []})
         :out
         (take max-commands)
         vec)))

(defn register!
  "POST setMyCommands for the default and all_group_chats scopes (the
   default scope alone does not show the menu inside groups). Returns
   the two API responses."
  [telegram-cfg commands]
  (let [base (str/replace (or (:base-url telegram-cfg) "https://api.telegram.org") #"/+$" "")
        url (str base "/bot" (:token telegram-cfg) "/setMyCommands")
        post (fn [scope]
               (let [resp @(http/post url {:headers {"content-type" "application/json"}
                                           :body (json/generate-string
                                                  {:commands commands :scope {:type scope}})})]
                 (json/parse-string (:body resp) true)))]
    {:default (post "default") :all_group_chats (post "all_group_chats")}))

(defn register-safely!
  "Boot hook: discover skills, build the menu, register both scopes.
   A registration failure prints one line and never blocks the poller."
  []
  (try
    (let [cfg (:telegram (config/load-config))
          commands (build-commands (skill/discover-all-skills))
          results (register! cfg commands)
          ok? (every? #(true? (:ok (val %))) results)]
      (println (str "bot-commands: registered " (count commands)
                    " commands, ok=" ok?
                    (when-not ok? (str " " (pr-str results)))))
      (flush)
      {:ok? ok? :count (count commands)})
    (catch Exception e
      (println (str "bot-commands: registration failed: " (.getMessage e)))
      (flush)
      {:ok? false :error (.getMessage e)})))
