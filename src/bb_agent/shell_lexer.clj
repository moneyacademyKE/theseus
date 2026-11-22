(ns bb-agent.shell-lexer
  "Linear-time shell lexer safety floor: distinguishes quoted/heredoc data from
   executed commands, normalizes unquoted ~/$HOME to \\uE000 and runtime
   expansions to \\uE001, tracks redirection targets, and fails closed
   (:synced? false) on unclosed quotes or excessive nesting."
  (:require [clojure.string :as str]))

(def ^:private home-marker "\uE000")
(def ^:private dynamic-marker "\uE001")
(def ^:private max-nesting 40)

(defn- strip-heredocs
  "Remove heredoc bodies (`<<EOF ... EOF` or `<<'EOF' ... EOF`) so literal data
   inside heredocs is never misread as executable shell commands."
  [s]
  (let [lines (str/split-lines (str (or s "")))]
    (loop [in (seq lines) out [] delim nil]
      (if-let [line (first in)]
        (if delim
          (if (= (str/trim line) delim)
            (recur (next in) out nil)
            (recur (next in) out delim))
          (if-let [[_ d] (re-find #"<<-?\s*['\"]?([A-Za-z0-9_]+)['\"]?" line)]
            (recur (next in) (conj out line) d)
            (recur (next in) (conj out line) nil)))
        {:text (str/join "\n" out)
         :synced? (nil? delim)}))))

(defn lex
  "Single-pass character scanner returning {:commands [[tok ...]] :writes [...] :synced? bool}."
  [raw]
  (let [{:keys [text synced?]} (strip-heredocs raw)
        n (count text)]
    (if-not synced?
      {:commands [] :writes [] :synced? false :reason :unclosed-heredoc}
      (loop [i 0
             mode :normal ;; :normal :single :double
             depth 0
             tok (StringBuilder.)
             tok-quoted? false
             cur-cmd []
             cmds []
             writes []
             next-is-write? false]
        (let [flush-tok (fn []
                          (if (pos? (.length tok))
                            (let [s (.toString tok)
                                  norm (if (and (not tok-quoted?)
                                                (or (= s "~") (str/starts-with? s "~/")))
                                         (str home-marker (subs s 1))
                                         s)]
                              [norm true])
                            [nil false]))]
          (cond
            (> depth max-nesting)
            {:commands cmds :writes writes :synced? false :reason :max-nesting}

            (>= i n)
            (if (or (not= mode :normal) (pos? depth))
              {:commands cmds :writes writes :synced? false :reason :unclosed-syntax}
              (let [[t has?] (flush-tok)
                    writes* (if (and has? next-is-write?) (conj writes t) writes)
                    cur* (if (and has? (not next-is-write?))
                           (conj cur-cmd {:text t :quoted? tok-quoted?})
                           cur-cmd)
                    cmds* (if (seq cur*) (conj cmds cur*) cmds)]
                {:commands cmds* :writes writes* :synced? true}))

            :else
            (let [ch (.charAt ^String text i)
                  nxt (when (< (inc i) n) (.charAt ^String text (inc i)))]
              (case mode
                :single
                (if (= ch \')
                  (recur (inc i) :normal depth tok true cur-cmd cmds writes next-is-write?)
                  (do (.append tok ch)
                      (recur (inc i) :single depth tok true cur-cmd cmds writes next-is-write?)))

                :double
                (cond
                  (= ch \\)
                  (if nxt
                    (do (.append tok nxt)
                        (recur (+ i 2) :double depth tok true cur-cmd cmds writes next-is-write?))
                    {:commands cmds :writes writes :synced? false :reason :trailing-escape})

                  (= ch \")
                  (recur (inc i) :normal depth tok true cur-cmd cmds writes next-is-write?)

                  :else
                  (do (.append tok ch)
                      (recur (inc i) :double depth tok true cur-cmd cmds writes next-is-write?)))

                :normal
                (cond
                  (= ch \\)
                  (if nxt
                    (do (.append tok nxt)
                        (recur (+ i 2) :normal depth tok tok-quoted? cur-cmd cmds writes next-is-write?))
                    {:commands cmds :writes writes :synced? false :reason :trailing-escape})

                  (= ch \')
                  (recur (inc i) :single depth tok true cur-cmd cmds writes next-is-write?)

                  (= ch \")
                  (recur (inc i) :double depth tok true cur-cmd cmds writes next-is-write?)

                  (and (= ch \#) (zero? (.length tok)))
                  (let [nl (.indexOf ^String text "\n" (int i))
                        next-i (if (neg? nl) n (inc nl))]
                    (recur next-i :normal depth tok false cur-cmd cmds writes next-is-write?))

                  (and (= ch \$) (or (= nxt \() (= nxt \{)))
                  (do (.append tok dynamic-marker)
                      (recur (+ i 2) :normal (inc depth) tok tok-quoted? cur-cmd cmds writes next-is-write?))

                  (and (pos? depth) (or (= ch \)) (= ch \})))
                  (recur (inc i) :normal (dec depth) tok tok-quoted? cur-cmd cmds writes next-is-write?)

                  (or (= ch \;) (= ch \newline) (= ch \|) (= ch \&))
                  (let [[t has?] (flush-tok)
                        writes* (if (and has? next-is-write?) (conj writes t) writes)
                        cur* (if (and has? (not next-is-write?))
                               (conj cur-cmd {:text t :quoted? tok-quoted?})
                               cur-cmd)
                        cmds* (if (seq cur*) (conj cmds cur*) cmds)
                        step (if (and (or (= ch \|) (= ch \&)) (= nxt ch)) 2 1)]
                    (recur (+ i step) :normal depth (StringBuilder.) false [] cmds* writes* false))

                  (= ch \>)
                  (let [[t has?] (flush-tok)
                        cur* (if (and has? (not next-is-write?))
                               (conj cur-cmd {:text t :quoted? tok-quoted?})
                               cur-cmd)
                        step (if (= nxt \>) 2 1)]
                    (recur (+ i step) :normal depth (StringBuilder.) false cur* cmds writes true))

                  (Character/isWhitespace ch)
                  (let [[t has?] (flush-tok)
                        writes* (if (and has? next-is-write?) (conj writes t) writes)
                        cur* (if (and has? (not next-is-write?))
                               (conj cur-cmd {:text t :quoted? tok-quoted?})
                               cur-cmd)]
                    (recur (inc i) :normal depth (StringBuilder.) false cur* cmds writes*
                           (if has? false next-is-write?)))

                  :else
                  (do (.append tok ch)
                      (recur (inc i) :normal depth tok tok-quoted? cur-cmd cmds writes next-is-write?)))))))))))

(def ^:private fork-bomb-re
  #":\s*\(\s*\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:")

(def ^:private disk-device-re
  #"^/dev/(?:sd[a-z]|hd[a-z]|nvme\d+n\d+|disk\d+|rdisk\d+)")

(defn- dangerous-rm? [tokens]
  (let [raw-texts (mapv :text tokens)
        cmd (first raw-texts)
        args (rest raw-texts)
        recursive? (some #(re-matches #"^-[a-zA-Z]*[rR][a-zA-Z]*$" %) args)
        targets (remove #(str/starts-with? % "-") args)]
    (and (= "rm" cmd)
         recursive?
         (some (fn [t]
                 (or (= t "/")
                     (= t "/*")
                     (= t home-marker)
                     (= t (str home-marker "/"))
                     (= t "$HOME")
                     (= t "${HOME}")))
               targets))))

(defn- dangerous-dd? [tokens]
  (let [texts (mapv :text tokens)]
    (and (= "dd" (first texts))
         (some (fn [t]
                 (and (str/starts-with? t "of=")
                      (boolean (re-find disk-device-re (subs t 3)))))
               (rest texts)))))

(defn- dangerous-mkfs? [tokens]
  (let [cmd (str (or (:text (first tokens)) ""))]
    (or (= cmd "mkfs") (str/starts-with? cmd "mkfs."))))

(defn catastrophic-command?
  "Evaluate whether a shell command is catastrophic using the linear-time lexer.
   Fails closed (:catastrophic? true :synced? false) when syntax cannot be lexed."
  [cmd]
  (let [s (str (or cmd ""))]
    (if (re-find fork-bomb-re s)
      {:catastrophic? true :synced? true :reason :fork-bomb}
      (let [{:keys [commands writes synced? reason]} (lex s)]
        (cond
          (not synced?)
          {:catastrophic? true :synced? false :reason (or reason :unsynced-syntax)}

          (some #(re-find disk-device-re %) writes)
          {:catastrophic? true :synced? true :reason :disk-device-write}

          (some dangerous-rm? commands)
          {:catastrophic? true :synced? true :reason :destructive-rm}

          (some dangerous-dd? commands)
          {:catastrophic? true :synced? true :reason :disk-dd}

          (some dangerous-mkfs? commands)
          {:catastrophic? true :synced? true :reason :mkfs}

          :else
          {:catastrophic? false :synced? true})))))
