(ns dev
  (:require [clojure.core.async :as a]
            [clojure.string :as string]
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
  [context input]
  (let [log-entries  (conj context input)
        response     (oai/create-response :model :gpt-4o :easy-input-messages log-entries)
        output-entry {:role    :assistant
                      :content (-> (:output response) first :message :content first :output-text :text)}]
    [(conj log-entries output-entry) output-entry]))

(defn cog
  "Create a cog with a simple vector context and a transition function
   that updates context via Open AI. Input messages are exected as an easy input message
   map - i.e {:role :user :content \"my prompt\"}"
  [prompt]
  (cogs/cog [{:role :system :content prompt}] gpt-4o))

(comment
  ;;; 1. Create some cogs
  (def echo
    (cogs/cog [] (fn [ctx msg]
                   (let [resp (str "👋 you said: "  msg)]
                     (-> (conj ctx msg)
                         (conj resp)
                         (vector resp))))))

  (def shout
    (cogs/cog [] (fn [ctx msg]
                   (let [resp (clojure.string/upper-case msg)]
                     (-> (conj ctx msg)
                         (conj resp)
                         (vector resp))))))

  ;;; 2. Wire cogs into a flow

  (def shout-flow (cogs/flow [echo shout]))
  (a/put! shout-flow "hello!")
  (a/take! shout-flow println)
  (a/close! shout-flow)

  ;;; 3. Let two cogs talk

  (def shout-convo (cogs/dialogue echo shout))
  (a/put! shout-convo "hello!")
  (a/go-loop []
    (when-some [msg (a/<! shout-convo)]
      (println msg)
      (recur)))
  (a/close! shout-convo)

  ;;; Cleanup
  (let [chs (cond-> [shout echo])]
    (doseq [ch chs]
      (a/close! ch)))

  ;;; Simple LLM use case (example with forking as well)

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
  (def multiplier (cogs/fork adder #(conj % {:role :user :content "You no longer add numbers, you multiply them"})))
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

  ;;; Flow with llms
  (do
    (def idea-guy (cog "You come up with an idea for a ridiculous product"))
    (def marketing-guy (cog "Given a ridiculous product idea, you generate a slogan for it"))
    (def product-team (cogs/flow [idea-guy marketing-guy]))
    (a/put! product-team {:role :user :content "Give me your most ridiculous idea"})
    (a/take! product-team println))

  ;;; Check context
  @idea-guy
  @marketing-guy

  ;;; Close em down
  (doseq [ch [idea-guy marketing-guy product-team]]
    (a/close! ch))

  ;;; Coordinate further with fanouts in order to take over the world
  (do
    (def idea-guy (cog "You come up with an idea for a ridiculous product"))
    (def marketing-guy (cog "Given a ridiculous product idea, you generate a slogan for it"))
    (def product-team (cogs/flow [idea-guy marketing-guy]))
    (def japanese-translator (cog "You translate slogans into idiomatic japanese"))
    (def french-translator (cog "You translate slogans into idiomatic french"))
    (def spanish-translator (cog "You translate slogans into idiomatic spanish (Spain)"))
    (def translators (cogs/fanout [japanese-translator french-translator spanish-translator]))
    (def global-inc
      (cogs/flow [product-team translators]))
    (a/put! global-inc {:role :user :content "Give me your most ridiculous idea"})
    (a/take! global-inc println))

  (doseq [ch [idea-guy marketing-guy product-team japanese-translator french-translator spanish-translator translators global-inc]]
    (a/close! ch))

  (do "good in this world"))
