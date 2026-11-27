(ns bb-agent.conflict-blocks
  "Bounded git merge-conflict block extractor (ported from LoopForge
   domain/conflict_blocks.go). Parses <<<<<<< / ======= / >>>>>>> markers into
   structured {:file :ours :theirs} maps capped at 40 lines and 2000 chars per
   side so merge conflicts stay scannable and never blow up context."
  (:require [clojure.string :as str]))

(def ^:private max-side-lines 40)
(def ^:private max-side-chars 2000)

(defn- clip-side [lines]
  (let [clipped-lines (if (> (count lines) max-side-lines)
                        (conj (vec (take max-side-lines lines)) "… (truncated)")
                        lines)
        joined (str/join "\n" clipped-lines)]
    (if (> (count joined) max-side-chars)
      (str (subs joined 0 max-side-chars) "… (truncated)")
      joined)))

(defn extract-blocks
  "Extract up to `:max-blocks` (default 20) conflict blocks from `text`."
  ([file text] (extract-blocks file text {}))
  ([file text {:keys [max-blocks] :or {max-blocks 20}}]
   (if (or (nil? text) (str/blank? (str text)) (<= (long max-blocks) 0))
     []
     (loop [lines (str/split-lines (str text))
            state :outside
            ours []
            theirs []
            out []]
       (if (or (empty? lines) (>= (count out) max-blocks))
         out
         (let [ln (first lines)
               more (rest lines)]
           (cond
             (str/starts-with? ln "<<<<<<<")
             (let [out* (if (= state :theirs)
                          (conj out {:file file :ours (clip-side ours) :theirs (clip-side theirs)})
                          out)]
               (recur more :ours [] [] out*))

             (and (= state :ours) (str/starts-with? ln "======="))
             (recur more :theirs ours [] out)

             (and (= state :theirs) (str/starts-with? ln ">>>>>>>"))
             (recur more :outside [] []
                    (conj out {:file file :ours (clip-side ours) :theirs (clip-side theirs)}))

             (= state :ours)
             (recur more :ours (conj ours ln) theirs out)

             (= state :theirs)
             (recur more :theirs ours (conj theirs ln) out)

             :else
             (recur more state ours theirs out))))))))
