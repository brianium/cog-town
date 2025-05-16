(ns dev
  (:require [clojure.core.async :as a]
            [clojure.tools.namespace.repl :as repl]
            [oai-clj.core :as oai]
            [cog.town :as cogs]))

(defn start []
  (println "cogs turning"))

(defn stop []
  (println "cogs halting"))

(defn refresh []
  (repl/refresh :after 'dev/start))

(defn gpt-4o
  "transition function that updates context via OpenAI's Responses API"
  [*ctx input]
  (let [log-entries  (swap! *ctx conj input)
        response     (oai/create-response :model :gpt-4o :easy-input-messages log-entries)
        output-entry {:role    :assistant
                      :content (-> (:output response) first :message :content first :output-text :text)}]
    (swap! *ctx conj output-entry)
    output-entry))

(defn cog
  "Create a cog with an atom backed context and a transition function
   that updates said atom via Open AI. Input messages are exected as an easy input message
   map - i.e {:role :user :content \"my prompt\"}"
  [prompt]
  (cogs/cog (atom [{:role :system :content prompt}]) gpt-4o))

(comment

  (def adder (cog "Given two numbers you add them"))
  (def adder-observer (a/chan))
  (a/tap adder adder-observer)
  
  (def adder-chs [adder adder-observer])

  ;;; Wait for output from the adder or its observer
  (a/go-loop []
    (let [[v p] (a/alts! adder-chs)]
      (when v
        (condp = p
          adder
          (do (println "adder:")
              (println v))
          
          adder-observer
          (do (println "adder observer:")
              (println v)))
        (recur))))

  (a/put! adder {:role :user :content "Add 3 and 7"})

  ;;; After building some state, lets fork the adder and give it new purpose
  (def multiplier (cogs/fork adder (fn [*ctx]
                                     (let [entries @*ctx]
                                       (atom (conj entries {:role :user :content "You no longer add numbers, you multiply them"}))))))
  (def mult-observer (a/chan))
  (a/tap multiplier mult-observer)

  (def mult-chs [multiplier mult-observer])

  ;;; Wait for output from the adder or its observer
  (a/go-loop []
    (let [[v p] (a/alts! mult-chs)]
      (when v
        (condp = p
          multiplier
          (do (println "multiplier:")
              (println v))
          
          mult-observer
          (do (println "mult observer:")
              (println v)))
        (recur))))

  (a/put! multiplier {:role :user :content "Actually, multiply the last two numbers instead"})
  
  ;;; Clean up the cogs
  (a/close! adder)
  (a/close! multiplier)

  )
