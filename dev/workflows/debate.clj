(ns workflows.debate
  "An example of listening to two cogs debate each other. Builds upon workflows.conversation,
   but this time we will pit two agents against each other. Like listening to two very passionate people argue on the subway."
  (:require [cog.town :as cog :refer [dialogue]]
            [oai-clj.models.audio :as openai]
            [workflows.conversation :as conv]
            [clojure.core.async :as async :refer [<! go-loop]]
            [clojure.java.io :as io]))

(def AgentSpec
  [:map
   [:prompt {:description "A resource path for a system prompt"} :string]
   [:voice  (into [:enum] (keys openai/voices))]
   [:instructions {:description "Instructions for audio model - use to control tone"} :string]])

(defn debate
  "Listen to two agents talk to one another. Agents are attached to the dialogue in order to inspect
   context"
  [s1 s2]
  (let [as-user (map #(assoc % :role :user)) ;;; Each agent should send their output as a user instead of an assistant
        partner (fn [{:keys [prompt voice instructions]}]
                  (-> (conv/persona (slurp (io/resource prompt)) nil as-user)
                      (conv/with-speech :voice voice :instructions instructions)))
        a1      (partner s1)
        a2      (partner s2)
        stop-playback (fn []
                        (when (:stop-playback a1)
                          ((:stop-playback a1)))
                        (when (:stop-playback a2)
                          ((:stop-playback a2))))
        d       (dialogue a1 a2 (async/sliding-buffer 1))]
    (go-loop []
      (let [msg (<! d)]
        (if (nil? msg)
          (do (println "stopping")
              (stop-playback)
              (async/close! a1)
              (async/close! a2)
              (async/close! d))
          (do (println "Waiting for response")
              (recur)))))
    (async/put! d {:role    :user
                   :content "Hey! I think you may have something to discuss with this person"})
    (assoc d :cogs [a1 a2])))

(comment
  (def d (debate
          {:prompt       "prompts/personas/grumbos-fan.md"
           :voice        :onyx
           :instructions "Speak with a thick Bostonian accent"}
          {:prompt       "prompts/personas/flurbos-fan.md"
           :voice        :ash
           :instructions "Speak in a whiny, high-pitched voice"}))

  ;;; Check the context logs
  (mapv cog/context (:cogs d))
  
;;; Shut it down
  (async/close! d)

  )
