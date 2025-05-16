(ns workflows.multimodal
  "An audible conversation with a sketch artist where"
  (:require [clojure.core.async :as async :refer [<! go-loop chan]]
            [clojure.java.io :as io]
            [cog.town :as cog :refer [cog io-chan dialogue]]
            [oai-clj.core :as oai]
            [workflows.conversation :refer [mic-chan with-speech]]
            [poopup.lol :as lol]
            [cheshire.core :as json]))

(def SketchInterview
  "This malli schema will be used for the structured output of the sketch artist. Should make managing
  the interview state in conversation much easier"
  [:map
   [:state {:description "One of: question, sketch, finished. Indicates what type of step we are on in the interview"} :string] ;;; Need to investigate why [:enum] isn't accepted
   [:text {:description "Text for the user. What you want to say. Always present"} :string]
   [:prompt {:description "A dall-e-3 image prompt. Only present when state is sketch"} :string]])

(defn sketch-artist
  "A cog backed by a structured output that supports interviewing for visual details. The :content property
  of the message will be parsed into a hash-map before being sent to the output channel."
  []
  (let [system-prompt (slurp (io/resource "prompts/personas/sketch-artist.md"))
        *context      (atom [{:role :system
                              :content system-prompt}])
        transition    (fn [*ctx input]
                        (let [log-entries (swap! *ctx conj input)
                              response    (oai/create-response :easy-input-messages log-entries :format 'SketchInterview)
                              entry       {:role :assistant :content (-> (:output response)
                                                                         first
                                                                         :message
                                                                         :content
                                                                         first
                                                                         :output-text
                                                                         :text)}]
                          (swap! *ctx conj entry)
                          entry))
        parse-string  #(json/parse-string % keyword)
        as-map        #(update % :content parse-string)]
    (cog *context transition 1 (map as-map))))

(defn with-display
  "Gives our sketch artist the power to show us images. We will attach state from the original agent and the
  any audio capabilities."
  [sketch-artist]
  (let [out         (chan)
        io          (io-chan sketch-artist out)
        show        (fn [{{:keys [state prompt]} :content :as entry}]
                      (when (= state "sketch")
                        (println "Generating sketch...")
                        (lol/poopup (-> (oai/generate-image :model :dall-e-3 :size :1024x1024 :prompt prompt)
                                        (:data)
                                        (first)
                                        (:url))))
                      entry)]
    (async/pipeline-blocking 1 out (map show) sketch-artist false)
    (->> (cog/fork sketch-artist io)
         (merge sketch-artist))))

(defn interview
  "Start a dialogue with the sketch artist. Returns a channel with the context of the sketch artist. Closing
   the channel will stop audio playback immediately."
  [& {:keys [voice instructions]
      :or   {voice :onyx}}]
  (let [artist        (with-display
                        (with-speech (sketch-artist)
                          :instructions instructions
                          :voice        voice
                          :content-fn  #(get-in % [:content :text])))
        mic            (mic-chan)
        d              (dialogue artist mic (async/sliding-buffer 1))]
    (go-loop []
      (let [msg (<! d)]
        (println msg)
        (if (or (nil? msg) (= "finished" (get-in msg [:content :state])))
          (do (println "Interview complete")
              (async/close! artist)
              (async/close! mic)
              (async/close! d)
              ((:stop-playback artist)))
          (recur))))
    (async/put! d {:role :user :content "I am ready to be interviewed"}) ;; We want the sketch artist to start the interview
    (assoc d :context (:context artist))))

(comment
  (def artist (interview
               :instructions "Speak like a 1920s New York mobster. Think Humphrey Bogart"
               :content-fn #(get-in % [:content :text])))

  ;;; Stop any current audio playback
  ((:stop-playback artist))

  ;;; Check the sketch artist's context out
  (println (:context artist))

  ;;; Shut it all down
  (async/close! artist)
  )
