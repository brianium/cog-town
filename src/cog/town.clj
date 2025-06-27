(ns cog.town
  "Build agentic workflows with core.async channels. A cog is just a channel
   with context. That context is updated via a transition function that is invoked
   on a separate thread"
  (:require [clojure.core.async :as async :refer [put! close! chan go-loop <! >! <!! >!! Mult]]
            [clojure.core.async.impl.protocols :as proto :refer [ReadPort WritePort Channel]])
  (:refer-clojure :exclude [extend]))

;;; Custom channel types

(defrecord IoChannel [in out]
  ReadPort
  (take! [_ fn1] (proto/take! out fn1))
  WritePort
  (put! [_ val fn1] (proto/put! in val fn1))
  Channel
  (close! [_]
    (proto/close! in)
    (proto/close! out))
  (closed? [_] (proto/closed? in)))

(defrecord Cog [*context io mult transition ex-handler]
  clojure.lang.IDeref
  (deref [_] @*context)
  ReadPort
  (take! [_ fn1] (proto/take! io fn1))
  WritePort
  (put! [_ val fn1] (proto/put! io val fn1))
  Channel
  (close! [_] (proto/close! io))
  (closed? [_] (proto/closed? io))
  Mult
  (tap* [_ ch close?] (async/tap* mult ch close?))
  (untap* [_ ch] (async/untap* mult ch))
  (untap-all* [_] (async/untap-all* mult)))

(defmethod print-method Cog [_ writer]
  (.write writer "#<Cog>"))

;;; Cog Town

(defn io-chan
  "Create a channel that separates input and output
  such that takes are from out-ch and puts are to
  in-ch."
  [in-ch out-ch]
  (IoChannel. in-ch out-ch))

(defn- start!
  [^Cog cog out-ch]
  (let [{:keys [*context transition io ex-handler]} cog
        {:keys [in]} io
        ex-handler' (or ex-handler (fn [th] {::type :error :throwable th}))]
    (async/go-loop [ctx @*context]
      (if-some [input (<! in)]
        (recur (<!
                (async/thread
                  (try
                    (let [[next resp] (transition ctx input)]
                      (reset! *context next)
                      (>!! out-ch resp)
                      next)
                    (catch Throwable th
                      (>!! out-ch (ex-handler' th)))))))
        (close! io)))
    cog))

(defn cog
  "A cog is a channel that encapsulates context and the transition function
  that updates it. The transition function is an arity 2 function that is called
  with the context and the input message that triggers an update to the context. transition
  will be called in a separate thread. transition MUST return a [context, output] tuple where output will be sent to the output channel.
  Additional arguments follow the same semantics as a core.async channel. xf is an output channel only transducer.
  context can be any type as long as transition can make use of it.

  A Cog is a mult, so feel free to tap it if you want to send outputs to other channels.
   
  A Cog can be dereferenced (@cog) to get the current snapshot of context"
  [context transition & [buf-or-n xf ex-handler]]
  (let [in-chan       (chan)
        out-chan      (chan buf-or-n xf ex-handler)
        mult          (async/mult out-chan)
        raw-out       (chan buf-or-n)
        _             (async/tap mult raw-out)
        io            (io-chan in-chan raw-out)
        *ctx          (atom context)
        cog*          (Cog. *ctx io mult transition ex-handler)]
    (start! cog* out-chan)))

(defn fork
  "returns a new cog derived from the given cog. The new cog is created
  with a separate io channel and mult. context-fn is called with cog's context
  and should return a new context (nil implies that the same context should be used). A new transition function
  can be given, or can be explicitly set to nil to prevent transitions. A typical
  use case for disabling transitions is when forking purely for the purpose
  of transforming output modality. All map fields from cog will be merged into the new cog"
  ([^Cog cog]
   (fork cog nil))
  ([^Cog cog context-fn]
   (fork cog context-fn (io-chan (chan) (chan))))
  ([^Cog cog context-fn ^IoChannel io]
   (fork cog context-fn io (:transition cog)))
  ([^Cog cog context-fn ^IoChannel io transition]
   (let [{:keys [*context ex-handler]} cog
         {:keys [in out]}  io
         mult     (async/mult out)
         raw-out  (chan)
         _        (async/tap mult raw-out)
         new-io   (io-chan in raw-out)
         tr       (when (fn? transition)
                    transition)
         *ctx     (if (some? context-fn) (atom (context-fn @*context)) *context)
         cog*     (merge cog (Cog. *ctx new-io mult transition ex-handler))]
     (if (some? tr)
       (start! cog* out)
       cog*))))

(defn extend
  "A special case of forking useful for extending the semantics of input and
  ouput for a cog."
  ([^Cog cog ^IoChannel io]
   (extend cog io nil))
  ([^Cog cog ^IoChannel io transition]
   (fork cog nil io transition)))

(defn cog? [x]
  (instance? Cog x))

(defn flow
  "A channel that passes previous output as input to the next channel in sequence. The optional transducer will be
   applied to EACH output value in the sequence."
  [chs & [buf-or-n xf ex-handler]]
  (let [in        (chan)
        out       (chan)
        io        (io-chan in out)
        result-ch (chan buf-or-n xf ex-handler)]
    (go-loop [read in
              cs   (vec chs)]
      (let [v (<! read)]
        (if (nil? v)
          (close! result-ch)
          (if-some [ch (first cs)]
            (do (put! result-ch v)
                (put! ch (<! result-ch))
                (recur ch (rest cs)))
            (do (put! out v)
                (recur in (vec chs)))))))
    io))

(defn- ordered-merge
  "A merge channel that ensures the output is in the order of the input channels. Used for scatter-gather pattern 
   in fanout."
  [chs & [xf]]
  (let [out (if xf
              (chan (count chs) xf)
              (chan (count chs)))]
    (go-loop [cs (vec chs)]
      (if (pos? (count cs))
        (let [v (<! (first cs))]
          (if (nil? v)
            (close! out)
            (do (>! out v)
                (recur (rest cs)))))
        (recur (vec chs))))
    out))

(defn fanout
  "A channel that takes a value and puts it on all channels in the sequence. The optional transducer will be
   applied to EACH output value in the sequence. The transducer will be applied via a pipeline-blocking operation.
   output will be sent as an ordered vector of each channel's output. A scatter-gather pattern."
  [chs & [xf ex-handler]]
  (let [in        (chan)
        out       (chan)
        io        (io-chan in out)
        n         (count chs)
        merge-ch  (ordered-merge chs)
        agg-ch    (chan n)
        broadcast (fn [v]
                    (doseq [ch chs]
                      (put! ch v)))
        aggregate (fn []
                    (go-loop [items []]
                      (if (= (count items) n)
                        (put! out items)
                        (recur (conj items (<! agg-ch))))))]
    (if xf
      (async/pipeline-blocking n agg-ch xf merge-ch false ex-handler)
      (async/pipe merge-ch agg-ch false))
    (go-loop []
      (if-some [v (<! in)]
        (do (broadcast v)
            (<! (aggregate))
            (recur))
        (do (close! merge-ch)
            (close! agg-ch))))
    io))

(defn gate
  "Returns a channel that will release the value of ch when the gate receives any input. The output produced
   by a gate is a tuple containing the original input and the value of ch. buf-or-n, xf, and ex-handler are optional
   and follow normal chan semantics. A gate is useful for plugging a channel into a flow (potentially with some transformation). Also
   useful for forwarding messages through a sequence of channels."
  [ch & [buf-or-n xf ex-handler]]
  (let [in  (chan)
        out (chan buf-or-n xf ex-handler)
        io  (io-chan in out)]
    (go-loop []
      (when-some [v (<! in)]
        (put! out [v (<! ch)])
        (recur)))
    io))

(defn dialogue
  "A channel where composed channels send their output as input
  to the next channel. This happens until the sun burns out or the dialogue
  is closed. Requires seed input to message first agent in the dialogue. (Then you should really
  just stay out of it)"
  [c1 c2 & [buf-or-n xf ex-handler]]
  (let [in  (chan)
        out (chan buf-or-n xf ex-handler)
        io  (io-chan in out)]
    (go-loop [cogs (cycle [c1 c2])]
      (when-some [msg (<! in)]
        (put! (first cogs) msg)
        (let [message (<! (first cogs))]
          (if (nil? message)
            (close! io)
            (do (put! out message)
                (put! in message)
                (recur (next cogs)))))))
    io))
