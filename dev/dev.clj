(ns dev
  (:require [clojure.tools.namespace.repl :as repl]))

(defn start []
  (println "cogs turning"))

(defn stop []
  (println "cogs halting"))

(defn refresh []
  (repl/refresh :after 'dev/start))
