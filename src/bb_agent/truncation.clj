(ns bb-agent.truncation
  "Mid-sentence EOS truncation detection, tail-anchored continuation with
   degenerate-retry guard, usage-gap corroboration, and pre-compaction
   context-pressure nudge band (ported from OpenCrabs truncation.rs + nudge.rs)."
  (:require [clojure.string :as str]))

(def max-continuation-attempts 2)
(def tail-anchor-chars 120)

(def ^:private clean-end-chars
  #{\. \! \? \" \' \) \] \} \` \… \: \;})

(def ^:private dangling-conjunction-re
  #"(?i)\b(?:and|or|but|because|however|therefore|which|that|where|when|while|if|so|to|for|with|from|into|of|in|on|at|the|a|an)\s*$")

(defn- unclosed-fence? [s]
  (odd? (count (re-seq #"(?m)^\s*```" s))))

(defn looks-truncated-mid-sentence
  "Heuristic structural check for a reply cut off mid-sentence or mid-fence
   despite finish_reason=stop."
  [text]
  (if (or (nil? text) (str/blank? (str text)))
    false
    (let [t (str/trimr (str text))
          last-ch (.charAt ^String t (dec (count t)))]
      (boolean
       (or (unclosed-fence? t)
           (= last-ch \:)
           (and (not (contains? clean-end-chars last-ch))
                (or (boolean (re-find dangling-conjunction-re t))
                    (Character/isLetterOrDigit ^char last-ch))))))))

(defn usage-gap?
  "True when visible output chars are far shorter than billed non-reasoning
   output tokens (< 2 chars per visible token). Subtracts reasoning tokens first
   so thinking-heavy turns never fake a gap."
  [text usage]
  (let [out-toks (long (or (:tokens/output usage) (:output_tokens usage) 0))
        reason-toks (long (or (:tokens/reasoning usage) (:reasoning_tokens usage) 0))
        visible-toks (max 0 (- out-toks reason-toks))]
    (if (zero? visible-toks)
      false
      (< (count (str/trimr (str (or text ""))))
         (* visible-toks 2)))))

(defn- tail-slice [s n]
  (let [t (str/trimr (str (or s "")))
        len (count t)]
    (if (<= len n) t (subs t (- len n)))))

(defn continuation-nudge
  "Tail-anchored continuation prompt embedding the verbatim last 120 chars
   between --- delimiters, forbidding lone markup closure. Pass `degenerate`
   when retrying after a discarded empty/markup-only continuation."
  [partial degenerate]
  (let [tail (tail-slice partial tail-anchor-chars)
        head (if degenerate
               (str "[System: Your previous continuation attempt returned only "
                    (pr-str degenerate) " — it added no content, so it was discarded. ")
               "[System: Your previous reply was cut off mid-sentence. ")]
    (str head
         "Your reply so far ends with these exact characters:\n---\n"
         tail
         "\n---\n"
         "Continue from that exact point onward: the very next characters must be NEW "
         "content that carries the answer forward. Do NOT repeat any earlier text, do "
         "NOT restart the answer, do NOT re-plan. If the tail ends inside unclosed "
         "markup (a code span, fence, bracket or quote), closing it alone does NOT "
         "count as continuing — carry the content forward and close the markup only "
         "when the content is actually done. Just keep writing.]")))

(defn degenerate-continuation?
  "True when a continuation added no real content: blank, only markup/punctuation
   (`\"`\"`, `\"```\"`, `\").\"`), or purely echoing the tail of `partial`."
  [partial cont]
  (let [c (str/trim (str (or cont "")))
        p (str/trimr (str (or partial "")))]
    (or (str/blank? c)
        (boolean (re-matches #"^[`~*_\-\[\]\(\)\{\}\"'.,;:!?\s]+$" c))
        (and (pos? (count c))
             (<= (count c) tail-anchor-chars)
             (str/ends-with? p c)))))

;; ---------- Pre-compaction context-pressure nudge band (55-64%) ----------

(def ^:private pressure-band-low 0.55)
(def ^:private pressure-band-high 0.65)

(defn context-pressure-nudge
  "Return a transient behavioral nudge when context usage sits in the 55-64%
   warning band just below reactive compaction; nil outside the band.
   Deliberately omits noisy percentage numbers."
  [used-chars budget-chars]
  (when (and (number? used-chars) (number? budget-chars) (pos? budget-chars))
    (let [ratio (/ (double used-chars) (double budget-chars))]
      (when (and (>= ratio pressure-band-low) (< ratio pressure-band-high))
        (str "[System: Context window is filling up and automatic compaction will soon "
             "trim older messages. Wrap up your current sub-task cleanly and persist any "
             "critical state, decisions, or file paths to disk/memory now so nothing is "
             "lost across the compaction boundary.]")))))
