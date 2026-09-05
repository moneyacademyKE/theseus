(ns bb-agent.telegram-rich
  "Convert lightweight markdown-ish text to Telegram HTML and split
   messages into Telegram-safe chunks. Pure functions only."
  (:require [clojure.string :as str]))

(def ^:private max-chunk 4096)

;; ---------- escaping ----------

(defn- escape-html* [s]
  (-> s
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

;; ---------- markdown -> telegram html ----------

(defn- code-blocks
  "Fence ```lang\\ncode``` to <pre>. The newline after the opening fence
   is fence syntax, not content; trailing newlines inside the body are
   stripped so Telegram doesn't render a dangling blank line."
  [s]
  (str/replace s #"(?s)```[^\n]*\n?(.*?)```"
               (fn [[_ body]]
                 (str "<pre>" (str/trim-newline body) "</pre>"))))

(defn- inline-code [s]
  (str/replace s #"`([^`\n]+)`" "<code>$1</code>"))

(defn- bold [s]
  (str/replace s #"\*\*(.+?)\*\*" "<b>$1</b>"))

(defn- italic [s]
  (str/replace s #"\*([^*\n]+)\*" "<i>$1</i>"))

(defn- links [s]
  (str/replace s #"\[([^\]\n]+)\]\(([^)\s]+)\)" "<a href=\"$2\">$1</a>"))

(defn to-html
  "Escape HTML-special chars, then convert markdown-ish markup:
   ```code blocks``` to <pre>, `inline code` to <code>,
   **bold** to <b>, *italic* to <i>, and [text](url) to <a href>.
   Returns a Telegram-parse-mode-HTML string. Pure."
  [s]
  (when (some? s)
    (-> (escape-html* (str s))
        code-blocks
        inline-code
        bold
        italic
        links)))

;; ---------- message splitting ----------

(defn- hard-split
  "Split s into pieces of at most n UTF-16 units (no preferred break
   available). Never cut inside a surrogate pair: a boundary that would
   separate a high surrogate from its low surrogate backs off one unit,
   leaving the pair whole at the start of the next chunk."
  [^String s n]
  (loop [acc [] i 0]
    (if (>= i (.length s))
      acc
      (let [raw-end (min (.length s) (+ i n))
            end (if (and (< raw-end (.length s))
                         (Character/isHighSurrogate (.charAt s (dec raw-end)))
                         (Character/isLowSurrogate (.charAt s raw-end)))
                  (dec raw-end)
                  raw-end)]
        (recur (conj acc (subs s i end)) end)))))

(defn- flush-chunk
  "Close a chunk. The break lands where the original text had a newline,
   so the separator travels with the chunk: concat of all chunks restores
   the input exactly. Only skipped at an oversized hard boundary, where
   adding the newline would push the chunk over max-chunk."
  [chunk]
  (cond
    (nil? chunk) nil
    (< (count chunk) max-chunk) (str chunk "\n")
    :else chunk))

(defn- pack-lines
  "Greedily pack lines into chunks no longer than max-chunk,
   separating lines by newlines."
  [lines]
  (loop [lines lines
         chunks []
         current nil]
    (if-let [line (first lines)]
      (let [candidate (if (nil? current) line (str current "\n" line))]
        (if (<= (count candidate) max-chunk)
          (recur (rest lines) chunks candidate)
          ;; line doesn't fit current chunk
          (if (nil? current)
            ;; oversized single line: hard-split it, keep last piece open
            (let [pieces (hard-split line max-chunk)]
              (recur (rest lines)
                     (into chunks (pop pieces))
                     (peek pieces)))
            (recur lines (conj chunks (flush-chunk current)) nil))))
      (if (nil? current)
        chunks
        (conj chunks current)))))

(defn split-message
  "Split s into a vector of chunks, each at most 4096 characters,
   preferring to break at newlines. Chunks concatenate back to the
   original text. Empty input yields [\"\"]. Pure."
  [s]
  (let [s (str (or s ""))]
    (if (<= (count s) max-chunk)
      [s]
      (vec (pack-lines (str/split-lines s))))))
