(ns bb-agent.rich
  (:require [clojure.string :as str]))

(defn text [s]
  {:type :text :text (or s "")})

(defn paragraph [& children]
  {:type :paragraph :children (vec children)})

(defn heading [level & children]
  {:type :heading :level level :children (vec children)})

(defn code-block [lang body]
  {:type :code :lang lang :text (or body "")})

(defn list-block [ordered? items]
  {:type :list :ordered? ordered? :items (vec items)})

(defn table [header rows]
  {:type :table :header header :rows rows})

(defn document [& blocks]
  {:type :document :children (vec blocks)})

(defn final-message [text-value]
  (document (paragraph (text text-value))))

(defn- escape-html [s]
  (-> (or s "")
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- plain-table [{:keys [header rows]}]
  (let [all-rows (cons header rows)
        widths (apply mapv max (map #(mapv count %) all-rows))
        pad (fn [s n] (str s (apply str (repeat (- n (count s)) " "))))
        row (fn [cells] (str/join " | " (map pad cells widths)))]
    (str/join "\n" (concat [(row header)
                            (str/join "-+-" (map #(apply str (repeat % "-")) widths))]
                           (map row rows)))))

(defn- render-node [mode node]
  (case (:type node)
    :document (str/join "\n\n" (map #(render-node mode %) (:children node)))
    :paragraph (str/join "" (map #(render-node mode %) (:children node)))
    :heading (let [inner (str/join "" (map #(render-node mode %) (:children node)))]
               (case mode
                 :telegram (str "<b>" inner "</b>")
                 (str (apply str (repeat (:level node) "#")) " " inner)))
    :code (case mode
            :telegram (str "<pre><code>" (escape-html (:text node)) "</code></pre>")
            (str "```" (or (:lang node) "") "\n" (:text node) "\n```"))
    :list (str/join "\n" (map-indexed (fn [idx item]
                                         (str (if (:ordered? node) (str (inc idx) ".") "•")
                                              " "
                                              (render-node mode item)))
                                       (:items node)))
    :table (case mode
             :telegram (str "<pre>" (escape-html (plain-table node)) "</pre>")
             (str "```\n" (plain-table node) "\n```"))
    :text (case mode
            :telegram (escape-html (:text node))
            (:text node))
    (str node)))

(defn plain [ast]
  (render-node :plain ast))

(defn terminal [ast]
  (render-node :terminal ast))

(defn telegram [ast]
  (render-node :telegram ast))

(defn- table-lines? [lines]
  (and (>= (count lines) 2)
       (every? #(str/includes? % "|") (take 2 lines))
       (re-find #"[-:| ]+" (second lines))))

(defn- parse-table [lines]
  (let [cells #(->> (str/split % #"\|") (map str/trim) (remove empty?) vec)]
    (table (cells (first lines)) (mapv cells (drop 2 lines)))))

(defn markdown [s]
  (let [lines (str/split-lines (or s ""))]
    (cond
      (table-lines? lines) (document (parse-table lines))
      (str/starts-with? (or (first lines) "") "#")
      (let [[_ marks title] (re-matches #"^(#+)\s+(.*)$" (first lines))]
        (document (apply heading (count marks) [(text title)])))
      (str/starts-with? (or (first lines) "") "```")
      (document (code-block nil (str/join "\n" (butlast (rest lines)))))
      (every? #(re-find #"^\s*[-*]\s+" %) lines)
      (document (list-block false (mapv #(paragraph (text (str/replace % #"^\s*[-*]\s+" ""))) lines)))
      :else (final-message s))))
