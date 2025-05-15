(ns audio.output
  "Low effort audio output 🤙🏻"
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async])
  (:import [javax.sound.sampled
            AudioSystem DataLine$Info SourceDataLine
            AudioFormat AudioFormat$Encoding
            LineUnavailableException]
           [java.io BufferedInputStream]))

(defn play
  "Plays an InputStream (can be the streaming HTTP body returned by the
  OpenAI Java SDK).  Returns a 0-arg function that stops playback
  gracefully."
  [^java.io.InputStream in
   & {:keys [on-complete buffer-size] :or {buffer-size 4096}}]

  (let [stop? (atom false)]                                  ;; cooperative-stop flag
    ;; ---------- playback thread ----------
    (async/thread
      (try
        ;; 1) Make the stream format-friendly
        (with-open [raw (-> in io/input-stream BufferedInputStream.) ; mark/reset
                    audio-in (AudioSystem/getAudioInputStream raw)]

          ;; 2) Decode to PCM_SIGNED so every Java mixer can play it
          (let [src-format (.getFormat audio-in)
                dst-format (AudioFormat.
                            AudioFormat$Encoding/PCM_SIGNED
                            (.getSampleRate src-format)
                            16
                            (.getChannels src-format)
                            (* 2 (.getChannels src-format))
                            (.getSampleRate src-format)
                            false)
                decoded    (AudioSystem/getAudioInputStream dst-format audio-in)
                info       (DataLine$Info. SourceDataLine dst-format)
                ^SourceDataLine line (AudioSystem/getLine info)
                buf        (byte-array buffer-size)]

            (.open line dst-format)
            (.start line)

            ;; 3) Pump data until user stops or stream ends
            (loop []
              (when-not @stop?
                (let [n (.read decoded buf 0 buffer-size)]
                  (cond
                    (neg? n)         ;; end-of-stream
                    nil

                    (zero? n)        ;; stream still alive but nothing yet
                    (do (Thread/sleep 10) (recur))

                    :else            ;; normal case
                    (do (.write line buf 0 n) (recur))))))

            ;; 4) Let remaining samples play, then close
            (.drain line)            ; finish what is in the mixer
            (.stop  line)
            (.close line)))

        (when (and on-complete (not @stop?))
          (on-complete))

        (catch LineUnavailableException e
          (println "No audio line available:" (.getMessage e)))
        (catch Exception e
          (println "Playback error:" (.getMessage e)))))

    ;; ---------- stop function ----------
    (fn [] (reset! stop? true))))
