(ns workflows.conversation
  "Build a truly conversational interaction with cogs"
  (:require [cog.town :as cog :refer [cog io-chan dialogue]]
            [audio.input :refer [record]]
            [audio.output :refer [play]]
            [oai-clj.core :as openai]
            [clojure.core.async :as async :refer [<! go-loop chan]]
            [clojure.java.io :as io])
  (:import [java.io ByteArrayInputStream]))

(defn mic-chan
  "mic-chan (♥ω♥*)
   Any input message to this channel will immediately start the mic and output will be sent when
   recording is finished per the rules of audio.input/record. Audio data is transcribed via OpenAI
   and sent as text output. Effectively puts you into a cog workflow"
  []
  (let [in         (chan)
        out        (chan)
        io         (io-chan in out)
        audio-in   (chan)
        transcribe (fn [audio-bytes]
                     (let [input           (ByteArrayInputStream. audio-bytes)
                           random-filename (str (java.util.UUID/randomUUID) ".wav")
                           result (openai/transcribe :file {:value input :filename random-filename})]
                       {:role :user :content (get-in result [:transcription :text])}))]
    (async/pipeline-blocking 1 out (map transcribe) audio-in false)
    (go-loop []
      (let [msg (<! in)]
        (if (nil? msg)
          (async/close! io)
          (do
            (println "Recording")
            (record (fn [audio-bytes _ _]
                      (async/put! audio-in audio-bytes)))
            (recur)))))
    io))

(defn persona
  "Create a cog and load it with context to help it reflect a particular personality

   system-prompt is text to be used for the system prompt. The persona prompt is optional
   and used to further guide personality. This example uses gpt-4o and context backed by an atom"
  [system-prompt & [persona-prompt xf]]
  (let [*context (atom
                  (cond-> [{:role :system :content system-prompt}]
                    (some? persona-prompt) (conj {:role :user :content persona-prompt})))

        gpt-4o   (fn [*ctx input]
                   (let [log-entries  (swap! *ctx conj input)
                         response     (openai/create-response :model :gpt-4o :easy-input-messages log-entries)
                         output-entry {:role    :assistant
                                       :content (-> (:output response) first :message :content first :output-text :text)
                                       :format  (when-some [fmt (some-> (:text response) :format)]
                                                  (if (some? (:json-schema fmt))
                                                    :json-schema
                                                    :text))}]
                     (swap! *ctx conj output-entry)
                     output-entry))]
    (cog *context gpt-4o 1 xf)))

(defn with-speech
  "Give the gift of speech"
  [persona & {:keys [voice instructions content-fn]
              :or   {voice :onyx content-fn :content}}]
  (let [out      (chan)
        *stop-fn (atom nil)
        speak    (fn [message result]
                   (let [resume-after-playback (fn []
                                                 (async/put! result message)
                                                 (async/close! result))
                         stop-fn               (-> (openai/create-speech :input (content-fn message) :voice voice :instructions instructions)
                                                   (play :on-complete resume-after-playback))]
                     (reset! *stop-fn stop-fn)))]
    (async/pipeline-async 1 out speak persona false)
    (-> persona
        (cog/output-modality speak)
        (assoc :stop-playback (fn []
                                 (when @*stop-fn
                                   (@*stop-fn)
                                   (reset! *stop-fn nil)))))))

(defn chat-with-gpt
  "Start a dialogue with gpt. Returns a channel with the context of the conversation partner. Closing
   the channel will stop audio playback immediately."
  [& {:keys [voice instructions prompt] :as opts}]
  (let [persona-prompt (slurp (io/resource (str "prompts/personas/" (:persona opts) ".md")))
        partner        (-> (io/resource prompt)
                           (slurp)
                           (persona persona-prompt)
                           (with-speech :voice voice :instructions instructions))
        mic            (mic-chan)
        d              (dialogue mic partner (async/sliding-buffer 1))] ;;; We aren't really doing anything with the text output
    (go-loop []
      (let [msg (<! d)]
        (if (nil? msg)
          (do ((:stop-playback partner))
              (async/close! partner)
              (async/close! mic)
              (async/close! d))
          (recur))))
    (async/put! d "はじめ") ;; The input message just kicks things off - its contents are irrelevant
    (assoc d :context (:context partner))))

(comment
  ;;; Start a dialogue with your favorite persona - prompts are stored in resources/prompts/*
  (def d (chat-with-gpt 
          :prompt "prompts/persona-base.md"
          :voice :onyx 
          :persona "mark-twain"
          :instructions "Speak with a deep, southern united states accent"))

  ;;; Take a look at the context log of your conversation partner
  (:context d)

  ;;; Shut it down
  (async/close! d)

  )
