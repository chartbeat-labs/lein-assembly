(defproject com.chartbeat.cljbeat/lein-assemble "0.1.1"
  :description "A leiningen plugin to do the job of maven assembly"
  :url "https://github.com/chartbeat-labs/lein-assembly"
  :license {:name "BSD 3 Clause"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[me.raynes/fs "1.4.6"]
                 [stencil "0.5.0" :exclusions [org.clojure/core.cache]]
                 [org.clojure/clojure "1.6.0"]
                 [org.apache.ant/ant "1.9.4"]]
  :deploy-repositories [["releases" :clojars]]
  :signing {:gpg-key "F0903068"}
  :eval-in-leiningen true)
