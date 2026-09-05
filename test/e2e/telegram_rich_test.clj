(ns e2e.telegram-rich-test
  (:require [clojure.test :as t :refer [deftest is]]
            [bb-agent.telegram-rich :as rich]))

(deftest to-html-escapes-html-specials
  (is (= "a &lt;b&gt; &amp; c"
         (rich/to-html "a <b> & c"))))

(deftest to-html-converts-markup
  (is (= "<b>x</b> <i>y</i> <code>z</code> <a href=\"http://e.com\">L</a>"
         (rich/to-html "**x** *y* `z` [L](http://e.com)"))))

(deftest to-html-renders-code-blocks-as-pre
  (is (= "<pre>(def x 1)</pre>"
         (rich/to-html "```\n(def x 1)\n```"))))

(deftest split-message-chunks-long-text
  (let [text (apply str (repeat 10000 "a"))
        chunks (rich/split-message text)]
    (is (= 3 (count chunks)))
    (is (every? #(<= (count %) 4096) chunks))
    (is (= text (apply str chunks)))))

(deftest split-message-prefers-newline-breaks
  (let [line (apply str (repeat 100 "a"))
        text (apply str (interpose "\n" (repeat 50 line)))
        chunks (rich/split-message text)]
    (is (> (count chunks) 1))
    (is (every? (fn [[_ prev]]
                  (or (nil? prev)
                      (= \newline (last prev))))
                (map vector chunks (cons nil (butlast chunks)))))))

(deftest split-message-never-breaks-a-surrogate-pair
  ;; Telegram's 4096 limit counts UTF-16 units (we match), but a hard
  ;; boundary must not land between a high and low surrogate: each chunk
  ;; must be well-formed UTF-16 on its own.
  (let [line (str (apply str (repeat 4095 "a")) "🦀" "tail")
        chunks (rich/split-message line)
        bad? (fn [^String c]
               (let [last-u (.charAt c (dec (count c)))]
                 (Character/isHighSurrogate last-u)))]
    (is (not-any? bad? chunks)
        "no chunk may end on a lone high surrogate")
    (is (= line (apply str chunks))
        "chunks still concatenate back to the original")))
