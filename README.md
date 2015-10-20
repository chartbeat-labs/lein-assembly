lein-assemble
=============

A Leiningen plugin to create arbitrary assemblies from leiningen projects.
Essentially to replace the functionality provided by the maven assembly plugin.

This is a first pass which I wrote to solve a specific use-case. I tried to make it somewhat flexible so it can evolve to support generic uses.

There are definitely a few bugs and un-supported features. Please make suggestions and open bugs!

## Usage
The plugin is hosted on clojars.

Put `[com.chartbeat.cljbeat/lein-assemble "0.1.1"]` into the `:plugins` vector of your
`:user` profile. I have not tested this on lein 1.0

### Purpose

This plugin was created to generate assemblies for deploying samza code to a yarn cluster in clojure. In the samza world all of the job's configurations are managed in external files and keeping these files in sync between local/dev/prod environments is error prone. One solution is create variables for server names and other constants that can be filtered at assembly time. More complex configs might require choosing a stage specific version of a file.

The Maven Assembly Plugin [http://maven.apache.org/plugins/maven-assembly-plugin/] has solved this problem for java projects for years, but generating a pom from lein, maintaining a separate tar.xml describing the deployment and having to run maven for every deployment quickly becomes a pain.

Enter Lein Assemble!




    $ lein assemble


### Assembly Definition

The following project.clj snippet shows off the functionality.

* Mustache filtering on filenames and text in the files themselves
* Project dependencies can be externalized into a directory in the archive
* The project jar or uberjar can be added to the archive
* A tar or tgz file can be created from the archive
* Glob filtering on file inclusions
* unzipping of support archive files that you need in the assembly

Defaults

``:location`` /target/assembly - where the archive is populated.

``:archive :format`` :tgz   - creates a .tgz, you can specify :tar instead

``:archive :name`` PROJECT-NAME-VERSION-archive

```
:assemble {
             :replacements {:stage "prod",
                            :zookeeper-host "zk01"
                            :pingstream-zookeeper "zk02:2181/cb/kafka/stream"
                            :pingstream-bootstrap "stream01:9092"
                            :mykafka-zookeeper "yarnzk01:2181"
                            :mykafka-bootstrap "yarnstream01:9092"

                            }
             :filesets {"conf" [["resources/*.properties" :filter true]               ;; run *.properties from resources through mustache and put them in the /conf directory in the archive
                                ["conf/yarn-site-{{stage}}.xml" :as "yarn-site.xml"]] ;; copy the stage specific version i.e. yarn-site-prod.xml and name it yarn-site.xml
                        "lib"  [["resources/log4j-{{stage}}.xml" :as "log4j.xml"]]    ;; ditto
                        "bin" [["bin/samza-shell-0.9.0-dist.tgz" :unzip true]]        ;; unzip this file into the bin directory in the archive
                        ""   [["README.md"]]
                        }
             :jar {:dest "lib" :uberjar false}
             :deps {:dest "lib"}
             :archive {:format :tgz :root-dir ""}}   ;; root-dir specifies the root in the tar file, defaults to archive name

 :profiles { :local     {:assemble {
                           :replacements {:stage   "local"
                           :pingstream-zookeeper "localhost:2181/cbdev/stream"
                           :pingstream-bootstrap "localhost:9092"
                           }}}
             :devstream {
                         :assemble {
                           :replacements {:stage   "dev",
                           :pingstream-zookeeper "devzk01:2181/cbdev/stream"
                           :pingstream-bootstrap "devstream01:9092"
                           }}}
            }
```


### TODO
* More documentation and examples
* Support more archive formats (zip)
* Support exclusion file filters



## License

Copyright © 2015 Chartbeat
BSD 3-clause
