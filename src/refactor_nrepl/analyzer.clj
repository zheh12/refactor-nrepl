(ns refactor-nrepl.analyzer
  (:refer-clojure :exclude [macroexpand-1])
  (:require [clojure.tools.analyzer.ast :refer :all]
            [clojure.tools.analyzer :as ana]
            [clojure.tools.analyzer.jvm :as ana.jvm]
            [clojure.tools.namespace.parse :refer [read-ns-decl]])
  (:import java.io.PushbackReader))

;;; The structure here is {ns {content-hash ast}}
(def ^:private ast-cache (atom {}))

;; these two fns could go to clojure.tools.namespace.parse: would worth a pull request
(defn get-alias [as v]
  (cond as (first v)
        (= (first v) :as) (get-alias true (rest v))
        :else (get-alias nil (rest v))))

(defn parse-ns
  "Returns tuples with the ns as the first element and
  a map of the aliases for the namespace as the second element
  in the same format as ns-aliases"
  [body]
  (let [ns-decl (read-ns-decl (PushbackReader. (java.io.StringReader. body)))
        aliases (->> ns-decl
                     (filter list?)
                     (some #(when (#{:require} (first %)) %))
                     rest
                     (remove symbol?)
                     (filter #(contains? (set %) :as))
                     (#(zipmap (map (partial get-alias nil) %)
                               (map first %))))]
    [(second ns-decl) aliases]))

(defn- noop-macroexpand-1 [form]
  form)

(defn- get-ast-from-cache
  [ns file-content]
  (-> @ast-cache
      (get ns)
      (get (hash file-content))))

(defn- update-ast-cache
  [file-content ns ast]
  (swap! ast-cache update-in [ns] merge {(hash file-content) ast})
  ast)

(defn- build-ast
  [ns aliases]
  (binding [ana/macroexpand-1 noop-macroexpand-1]
    (assoc-in (ana.jvm/analyze-ns ns) [0 :alias-info] aliases)))

(defn- cachable-ast [file-content]
  (let [[ns aliases] (parse-ns file-content)]
    (when ns
      (if-let [cached-ast (get-ast-from-cache ns file-content)]
        cached-ast
        (update-ast-cache file-content ns (build-ast ns aliases))))))

(defn string-ast
  [file-content]
  (try
    (cachable-ast file-content)
    (catch Exception ex
      (println "error when building AST for" (first (parse-ns file-content)))
      (.printStackTrace ex)
      [])))
