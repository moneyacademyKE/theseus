(ns bb-agent.cron
  "Pure cron expression matching over java.time, ported from
  hermes-beam's cron_scheduler.gleam — the actor and state machinery
  dropped, the decomplected core kept:

    - 5-field expressions: minute hour day-of-month month day-of-week
    - parts: `*`, exact, `a-b` range, `*/n` step, comma lists
    - Sunday is 0 or 7; fields AND (no Vixie dom/dow OR rule)
    - time arrives as an argument: ZonedDateTime, so zone-aware edge
      cases (DST gaps, ambiguous hours) are the caller's data, not
      hidden global state

  next-match scans minute-by-minute (bounded); matches-in-window
  collects the slots a scheduler missed during downtime."
  (:require [clojure.string :as str]))

(def ^:private minute-fields
  {0 (fn [d] (.getMinute d))
   1 (fn [d] (.getHour d))
   2 (fn [d] (.getDayOfMonth d))
   3 (fn [d] (.getMonthValue d))})

(defn- parse-long [s]
  (try (Long/parseLong s) (catch Exception _ nil)))

(defn- part-matches?
  "One comma-part of a field against `values`, a set of acceptable
  integers (dow carries two: Sunday as 0 and as 7)."
  [part values]
  (or (= part "*")
      (when-let [[_ a b] (re-find #"^(\d+)-(\d+)$" part)]
        (let [a (parse-long a) b (parse-long b)]
          (and a b (boolean (some #(<= a % b) values)))))
      (if-let [[_ n] (re-find #"^\*/(\d+)$" part)]
        (when-let [n (parse-long n)]
          (boolean (some #(zero? (mod % n)) values)))
        (when-let [v (parse-long part)]
          (contains? values v)))))

(defn- field-matches? [field values]
  (boolean (some #(part-matches? % values) (str/split field #","))))

(defn match-cron
  "True when 5-field cron `expr` matches the wall-clock fields of
  ZonedDateTime `dt`. Malformed expressions never match."
  [expr dt]
  (let [fields (str/split (str/trim (str expr)) #"\s+")]
    (and (= 5 (count fields))
         (let [[mi hr dom mon dow] fields
               dow-val (.getValue (.getDayOfWeek dt))
               sunday-0 (if (= dow-val 7) 0 dow-val)]
           (and (field-matches? mi #{((minute-fields 0) dt)})
                (field-matches? hr #{((minute-fields 1) dt)})
                (field-matches? dom #{((minute-fields 2) dt)})
                (field-matches? mon #{((minute-fields 3) dt)})
                (field-matches? dow (set [sunday-0 dow-val])))))))

(defn- truncate-to-minute [dt]
  (.withNano (.withSecond dt 0) 0))

(defn next-match
  "First minute at or after `dt` matching `expr`, scanning forward one
  minute at a time up to `limit-minutes` (default 525600 = one year).
  Returns nil when the bound is exhausted. Minute-scan keeps DST
  correct by construction: nonexistent wall slots simply never match."
  ([expr dt] (next-match expr dt 525600))
  ([expr dt limit-minutes]
   (loop [t (truncate-to-minute dt) i 0]
     (cond
       (>= i limit-minutes) nil
       (match-cron expr t) t
       :else (recur (.plusMinutes t 1) (inc i))))))

(defn matches-in-window
  "Every matching minute in [from, to), in order. This is the seam a
  scheduler uses to catch up missed runs after downtime; on DST days
  the count reflects real elapsed time (23- or 25-hour days)."
  [expr from to]
  (loop [t (truncate-to-minute from) acc []]
    (if (not (.isBefore t to))
      acc
      (recur (.plusMinutes t 1)
             (if (match-cron expr t) (conj acc t) acc)))))
