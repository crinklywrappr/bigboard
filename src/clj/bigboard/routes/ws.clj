(ns bigboard.routes.ws
  (:require
   [clojure.tools.logging :as log]
   [immutant.web.async :as async]
   [cognitect.transit :as t])
  (:import [java.io ByteArrayOutputStream]))

(defonce channels (atom #{}))

(defn edn->transit [edn]
  (let [out (ByteArrayOutputStream. 4096)]
    (t/write (t/writer out :json) edn)
    (.toString out)))

(defn connect! [channel]
  (log/info "channel open")
  (swap! channels conj channel))

(defn disconnect! [channel {:keys [code reason]}]
  (log/info "close code: " code ", reason: " reason)
  (swap! channels #(remove #{channel} %)))

(defn notify-clients! [channel msg]
  (doseq [channel @channels]
    (async/send! channel msg)))

(def websocket-callbacks
  {:on-open connect!
   :on-close disconnect!
   :on-message notify-clients!})

(defn ws-handler [request]
  (async/as-channel request websocket-callbacks))

(def ws-routes
  [["/ws" ws-handler]])
