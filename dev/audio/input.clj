(ns audio.input
   "Low effort audio input 🤙🏻"
  (:require [clojure.core.async :as async :refer [chan go go-loop <!]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [javax.sound.sampled AudioSystem AudioFormat TargetDataLine DataLine$Info AudioInputStream AudioFileFormat$Type]))

(defn wav-bytes
  ([bytes]
   (wav-bytes bytes (AudioFormat. 16000 16 1 true true)))
  ([bytes format]
   (let [ais  (AudioInputStream. (ByteArrayInputStream. bytes) format (/ (count bytes) (.getFrameSize format)))
         baos (ByteArrayOutputStream.)]
     (AudioSystem/write ais AudioFileFormat$Type/WAVE baos)
     (.toByteArray baos))))

(defn calculate-rms
  "Calculate the root mean square of the audio buffer to get the amplitude of the signal"
  [audio-bytes]
  ;; Assuming audio-bytes is an array of bytes representing 16-bit audio samples.
  ;; You need to properly convert every two bytes into a single sample value.
  (let [samples     (partition 2 audio-bytes)
        sum-squares (reduce (fn [sum [high low]]
                              (let [sample (+ (bit-shift-left high 8) (bit-and low 255))]
                                (+ sum (Math/pow sample 2))))
                            0
                            samples)]
    (/ (Math/sqrt (/ sum-squares (count samples))) 32768)))

(defn record
  "Start recording from the microphone. The callback will be invoked when audio input has finished.
   Input is considered finish after a (:duration options) period of silence. (:threshold options) is the RMS threshold and
   determines what volume of speech considered actual speech. Returns a stop function that can be invoked to immediately
   stop recording"
  ([callback]
   (record callback {}))
  ([callback options]
   (record callback options {}))
  ([callback options params]
   (let [{:keys [threshold duration debug?]
          :or   {threshold 0.03
                 duration  2000
                 debug?    false}} options
         format          (AudioFormat. 16000 16 1 true true)
         info            (DataLine$Info. TargetDataLine format)
         buffer          (byte-array 1024)
         out             (ByteArrayOutputStream.)
         done            (chan)
         rms-vals        (chan)
         last-sound-time (atom (System/currentTimeMillis))
         speech-started? (atom false)
         debug           (fn [& args]
                           (when debug?
                             (apply println args)))]
     (when (AudioSystem/isLineSupported info)
       (try
         (let [microphone (AudioSystem/getLine info)]
           (.open microphone)
           (.start microphone)
           (debug "Recording started...")

           ;; Stop recording after a period of silence
           (go-loop []
             (let [rms (<! rms-vals)]
               (debug "RMS:" rms) ;; Debugging output
               (when @speech-started? ;;; Only track silence after speech has
                 (if (< rms threshold)
                   (when (> (- (System/currentTimeMillis) @last-sound-time) duration)
                     (debug "Silence detected, stopping recording.")
                     (async/>! done :done))
                   (reset! last-sound-time (System/currentTimeMillis))))
               (recur)))

           ;; Record audio data and calculate RMS
           (go-loop []
             (when-not (async/poll! done)
               (let [n (.read microphone buffer 0 (alength buffer))]
                 (when (pos? n)
                   (.write out buffer 0 n)
                   (let [rms (calculate-rms (take n buffer))]
                     (async/>! rms-vals rms)
                     (when (> rms threshold)
                       (debug "Speech has started")
                       (reset! speech-started? true)))
                   (recur)))))

           ;; Handle completion of recording
           (go (<! done)
               (.stop microphone)
               (.close microphone)
               (let [bytes (.toByteArray out)]
                 (callback (wav-bytes bytes) format params))
               (debug "Recording finished.")))
         (catch Exception e
           (debug (.getMessage e))
           (debug "Line unavailable")))
       (fn []
         (async/put! done :done)
         :stopped)))))
