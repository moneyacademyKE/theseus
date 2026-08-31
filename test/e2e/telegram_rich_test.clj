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
