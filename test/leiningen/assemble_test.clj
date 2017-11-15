(ns leiningen.assemble-test
  (:require [clojure.test :refer :all]
            [leiningen.assemble :refer :all]
            [me.raynes.fs :as fs]))


(deftest do-make-location-test
  (do
    (do-make-location "target/assembly/123")
    (is (= true (fs/exists? "target/assembly/123")))))
