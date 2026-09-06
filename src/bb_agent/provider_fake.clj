(ns bb-agent.provider-fake
  (:require [clojure.string :as str]))

(defn- final-tool-message [messages]
  (some->> messages
           reverse
           (filter #(= :tool (:role %)))
           first))

(defn- parse-tool-results [messages]
  (some-> (final-tool-message messages) :tool/results))

(defn- memory-match-texts [request]
  (mapv :memory/text (:memory/matches request)))


(defn complete-fake [{:keys [messages] :as request}]
  (let [prompt (:content (last messages))
        tool-results (parse-tool-results messages)
        memory-texts (memory-match-texts request)]
    (cond
      (and (= "use memory theseus" prompt)
           (seq memory-texts))
      {:role :assistant
       :content (str "memory=" (first memory-texts))}

      (and (= "status please" prompt)
           (nil? tool-results))
      {:role :assistant
       :content "status=ok"}

      (and (str/starts-with? prompt "try denied shell ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "shell"
                        :tool/args {:cmd (subs prompt (count "try denied shell "))}}]}

      (and (str/starts-with? prompt "try approved shell ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "shell"
                        :approval/policy :auto-all
                        :tool/args {:cmd (subs prompt (count "try approved shell "))}}]}

      (and (str/starts-with? prompt "try approved read_file ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "read_file"
                        :approval/policy :auto-all
                        :tool/args {:path (subs prompt (count "try approved read_file "))}}]}

      (and (str/starts-with? prompt "try approved write_file ")
           (nil? tool-results))
      (let [[path content] (str/split (subs prompt (count "try approved write_file ")) #"\|" 2)]
        {:role :assistant
         :tool/requests [{:tool/name "write_file"
                          :approval/policy :auto-all
                          :tool/args {:path path
                                      :content (or content "")
                                      :create-dirs? true}}]})

      (and (str/starts-with? prompt "try approved search ")
           (nil? tool-results))
      (let [[path query] (str/split (subs prompt (count "try approved search ")) #"\|" 2)]
        {:role :assistant
         :tool/requests [{:tool/name "search"
                          :approval/policy :auto-all
                          :tool/args {:path path
                                      :query (or query "")}}]})

      (and (str/starts-with? prompt "try approved git_status ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "git_status"
                        :approval/policy :auto-all
                        :tool/args {:cwd (subs prompt (count "try approved git_status "))}}]}

      (and (str/starts-with? prompt "try approved browser ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "browser_cli"
                        :approval/policy :auto-all
                        :tool/args {:url (subs prompt (count "try approved browser "))}}]}

      (and (str/starts-with? prompt "try approved document ")
           (nil? tool-results))
      {:role :assistant
       :tool/requests [{:tool/name "document_read"
                        :approval/policy :auto-all
                        :tool/args {:path (subs prompt (count "try approved document "))}}]}

      tool-results
      {:role :assistant
       :content (str "tool-results=" (pr-str tool-results))}

      :else
      {:role :assistant
       :content (if (= "say pong" prompt)
                  "pong"
                  (str "fake: " prompt))})))

