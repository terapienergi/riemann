(ns riemann.transport.rabbitmq-test
  (:require [riemann.common :refer [event map-matches?]]
            [riemann.config :refer [service!]]
            [riemann.core :as core]
            [riemann.index :as index]
            [riemann.transport.rabbitmq :refer :all]
            [clojure.test :refer :all]
            [langohr.core :as rmq]
            [langohr.queue :as lq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.consumers :as lc]
            [langohr.basic :as lb]))

(def ^:const events
  {:bee {:host "thisone"
         :service "busybee"
         :state "ok"
         :description "buzz"
         :metric 42.0},
   :cat {:host "thisone"
         :service "sleepycat"
         :state "ok"
         :description "meow"
         :metric 9.0}})

(defn- gen-query-result-handler
  [query-result]
  (fn [ch {:keys [correlation-id] :as meta} ^bytes payload]
    (deliver query-result {:message (pb->msg payload)
                           :meta meta})))

(deftest ^:rabbitmq valid-protobuf-payload-decodes-into-a-message
  (let [pb (msg->pb {:events (map event (vals events))})
        result (pb->msg pb)
        [e1 e2] (:events result)]
    (is (map-matches? (:bee events) e1))
    (is (map-matches? (:cat events) e2))
    (is (contains? result :decode-time))))

(deftest ^:rabbitmq ^:integration rabbitmq-transport-integration-test
  (riemann.logging/suppress ["riemann.transport"
                             "riemann.pubsub"
                             "riemann.core"]
    (let [host "rabbitmq.local"
          ex-name "riemann-test"
          ex-type "topic"
          routing-key "test"
          reply-to "queries"
          transport (rabbitmq-transport {:host host
                                         :ex-name ex-name
                                         :ex-type ex-type
                                         :routing-key routing-key})
          index (core/wrap-index (index/index))
          sink (promise)
          query-result (promise)
          core (core/transition! (core/core) {:index index
                                              :services [transport]
                                              :streams [index (partial deliver sink)]})
          conn (atom nil)
          ch (atom nil)
          payload (msg->pb {:events [(event (:bee events))]
                            :query {:string "service = \"busybee\""}})]
      (try
        (reset! conn (rmq/connect {:host host}))
        (reset! ch (lch/open @conn))
        (le/declare @ch ex-name ex-type {:durable false :auto-delete true})
        (let [q-name (.getQueue (lq/declare @ch "" {:exclusive false :auto-delete true}))]
          (lq/bind @ch q-name ex-name {:routing-key reply-to})
          (lc/subscribe @ch q-name (gen-query-result-handler query-result) {:auto-ack true}))
        (lb/publish @ch ex-name routing-key payload {:content-type "application/octet-stream"
                                                     :reply-to reply-to
                                                     :correlation-id "hi"
                                                     :type "riemann.test"})
        (is (map-matches? (:bee events) (deref sink 1000 :timed-out)))
        (let [{message :message meta :meta} (deref query-result 1000 :timed-out)]
          (is (= (:correlation-id meta) "hi"))
          (is (map-matches? {:ok true :error nil} message))
          (is (map-matches? (:bee events) (-> message :events (first)))))
        (finally
          (core/stop! core)
          (when @conn
            (rmq/close @ch)
            (rmq/close @conn)))))))
