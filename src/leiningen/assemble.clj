(ns leiningen.assemble
  (:require [me.raynes.fs :as fs]
            [me.raynes.fs.compression :as comp]
            [leiningen.core.main :as lein]
            [leiningen.jar :as jar]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [stencil.core :as stencil]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.set :as set])
  (:import (java.io ByteArrayOutputStream File FileOutputStream)
           (java.util.zip GZIPOutputStream)
           (org.apache.tools.tar TarEntry TarOutputStream))

  )

(defn make-file-path
  [root & rest]
  (.replaceAll (str/join "/" (cons root rest)) "//" "/") )

(def cwd (System/getProperty "user.dir"))

(defn gzip
  "Writes the contents of input to output, compressed.
  input: something which can be copied from by io/copy.
  output: something which can be opend by io/output-stream.
  The bytes written to the resulting stream will be gzip compressed.
  Cribbed from a gist.."
  [input output & opts]
  (with-open [output (-> output io/output-stream GZIPOutputStream.)]
    (apply io/copy input output opts)))

;; these are from the lein-tar plugin. Respect.
(defn- add-file [tar path f]
  "Add a file f to the tar at the given path"
  (let [n (-> (str path "/" (fs/base-name f))
              ;; nuke leading slashes
              (.replaceAll "^\\/" ""))
        entry (doto (TarEntry. f)
                (.setName n))]
    (when-not (empty? n) ;; skip entries with no name
      (when (.canExecute f)
        ;; No way to expose unix perms? you've got to be kidding me, java!
        (.setMode entry 0755))
      (.putNextEntry tar entry)
      (when-not (.isDirectory f)
        (io/copy f tar))
      (.closeEntry tar))))

(defn- add-directory
  "Add a directory to the tar file"
  [tar path]
  ;; minor hack, we use the cwd as the model for any plain directories
  ;; that we're adding
  (let [entry (doto (TarEntry. (io/file cwd))
                (.setName path))]
    (.putNextEntry tar entry)
    (.closeEntry tar)))

(defn do-unzip
  "Does the unzipping of zip/gzip/tgz"
  [archive dest]
  (lein/info "unzipping " archive " to " dest)
  (let [ext (fs/extension archive)
        filename (fs/name archive)
        tar? (or (= ext ".tgz")                             ; if we add more we should refactor :)
                 (= ext ".tar")
                 (.endsWith filename "tar"))
        dest-name (if (= ext ".tgz")
                    (str (make-file-path dest filename) ".tar") ; tgz ftw!
                    (make-file-path dest filename))]

    ;(lein/info dest-name tar?)
    (condp = ext
      ".zip" (comp/unzip archive dest-name)
      ".gz" (comp/gunzip archive dest-name)
      ".tgz" (comp/gunzip archive dest-name)
      (lein/info "no zip to unzip"))

    (when tar?
      (lein/debug "Got a tar file " dest-name)
      (if (fs/exists? dest-name)
        (do
          (comp/untar dest-name dest)
          (fs/delete dest-name))
        (lein/warn "Could not find tar " dest-name "for archive")))))

(defn delete-if-exists
  "Delete a directory if it exists"
  [directory]
  (if (fs/directory? directory)
    (do
      (lein/info "Deleting first... " directory)
      (fs/delete-dir directory))))

(defn make-if-not-dir
  [directory]
  (if-not (fs/directory? directory)
    (fs/mkdir directory)))

(defn do-make-location
  "Clean and create deployment directory"
  [location]
  (lein/info "Creating: " location)
  (delete-if-exists location)
  (fs/mkdir location))

(defn stache-filename
  "run the filename through the mustache filter"
  [filename replacements]
  (stencil/render-string filename replacements))

(defn process-file
  "process a single file"
  ([src dest]
   (lein/debug "Copying: " src " -> " dest)
   (fs/copy src dest))
  ([src dest replacements]
   (lein/debug "Copying with filter: " src " -> " dest)
   (let [file-str (slurp src)]
     (with-open [w (clojure.java.io/writer dest)]
       (.write w (stencil/render-string file-str replacements))))))

(defn do-process-files-for-fileset
  "Process a fileset copy operation"
  [dest replacements src & opts]
  (let [processed-src (stache-filename src replacements)
        src-files (fs/glob processed-src)
        args (apply hash-map opts)]
    (if src-files
      (doseq [f src-files]
        (lein/debug "copy: " f " -> " dest " opts: " opts " args: " args)
        (let [dest-file (if (:as args)
                          (make-file-path dest (:as args))
                          (make-file-path dest (fs/base-name f)))]
          (if (:unzip args)
            (do-unzip f dest)
            (if (:filter args )
              (process-file f dest-file replacements)
              (process-file f dest-file)))))

      (lein/warn "No files for fileset " processed-src))))

(defn do-process-fileset
  [root replacements dest fileset]
  (let [dest-dir (make-file-path root dest)
        process-files (partial do-process-files-for-fileset dest-dir replacements)]
    (lein/info "copying files into " dest-dir)
    (do-make-location dest-dir)
    (doseq [f fileset]
      (apply process-files f))))

(defn do-copy-deps
  "Copy project dependencies to destination"
  [dest jars]
  (make-if-not-dir dest)
  (doseq [jar jars]
    (let [to-file (make-file-path dest (fs/base-name jar))]
      (lein/debug "copying " jar " -> " to-file)
      (fs/copy jar to-file))))

(defn do-copy-jar
  [jar root {:keys [dest] :or {dest "lib"}}]
  (let [copy-to (make-file-path root dest (fs/base-name jar))]
    (lein/debug "Jar " jar " -> " copy-to)
    (make-if-not-dir (make-file-path root dest))
    (if (fs/file? jar)
      (fs/copy jar copy-to)
      (lein/warn "Warning! jar does not exist! Maybe you need to run lein jar?" jar))))


(defn do-make-archive
  "Make tar/zip. Currently only handles tar and tgz extensions."
  [root name format dest]
  (lein/info "making archive")
  (let [final-name (str name (condp = format
                               :zip ".zip"
                               :tar ".tar"
                               :gz ".gz"
                               :tgz ".tgz"
                               ))
        tar-file (io/file dest (str name ".tar"))]
    (when (or (= format :tar) (= format :tgz))
      ; make a tar file first (or last)
      (let [
            root-location (str cwd "/" root)
            files (fs/find-files root #".*")]
        (.delete tar-file)
        (.mkdirs (.getParentFile tar-file))
        (with-open [tar (TarOutputStream. (FileOutputStream. tar-file))]
          (.setLongFileMode tar TarOutputStream/LONGFILE_GNU)
          (doseq [file files]
            (if (fs/directory? file)
              (let [tar-root (make-file-path name (str/replace-first (.getAbsolutePath file) root-location ""))]
                (add-directory tar tar-root))
              (let [tar-root (make-file-path name (str/replace-first (.getParent file) root-location ""))]
                (add-file tar tar-root file))))
          (println "Wrote Tar" (.getCanonicalPath tar-file)))))
    (when-not (= format :tar)                                 ;unless it's just a tar
      (lein/info "Writing " dest " -> " final-name)
      (gzip (io/file tar-file) (io/file (make-file-path dest final-name))))))


(defn assemble
  "Avengers Assemble! Assembles your project from information in the project.clj.
   Please visit https://github.com/chartbeat-labs/lein-assembly for more information
  "
  [project & _]
  ; profile merging from lein-jar :)
  (let [scoped-profiles (set (project/pom-scope-profiles project :provided))
        default-profiles (set (project/expand-profile project :default))
        provided-profiles (remove
                            (set/difference default-profiles scoped-profiles)
                            (-> project meta :included-profiles))
        project (project/merge-profiles (project/merge-profiles project [:uberjar]) provided-profiles)
        project (update-in project [:jar-inclusions]
                           concat (:uberjar-inclusions project))
        {assembly-map :assemble, {assembly-root :location, replacements :replacements,
                                   :or {assembly-root "target/assembly"}} :assemble } project]
    (lein/info "Creating assembly in: " assembly-root)
    (lein/debug "Assembly: " assembly-map)

    ; make distribution target directory
    (do-make-location assembly-root)

    ; copy and filter files
    (lein/debug "process filesets: " (:filesets assembly-map))
    (doseq [fileset (:filesets assembly-map)]
      (apply do-process-fileset assembly-root replacements (first fileset) (rest fileset)))

    ; copy dependencies
    (when (:deps assembly-map)
      (lein/info "Copying Dependencies:")
      (let [whitelisted (select-keys project jar/whitelist-keys)
            project (-> (project/unmerge-profiles project [:default])
                        (merge whitelisted))
            deps (->> (classpath/resolve-dependencies :dependencies project)
                      (filter #(.endsWith (.getName %) ".jar")))]
        (do-copy-deps (make-file-path assembly-root (get-in assembly-map [:deps :dest])) deps)))

    ; copy my jar
    (when-let [j (:jar assembly-map)]
      (let [jar (jar/get-jar-filename project (get-in assembly-map [:jar :uberjar]))]
        (lein/info "Copying jar: " jar j)
        (do-copy-jar jar assembly-root j)))

    ; make a zip of the assembly
    (when-let [assembly (:archive assembly-map)]
      (lein/info "Making an archive")
      (let [archive-name (or (:name assembly) (str (:name project) "-" (:version project) "-archive"))
            archive-format (or (:format assembly) :tgz)
            target (or (:target assembly) "target")]
        (do-make-archive assembly-root archive-name archive-format target)))

    (lein/info "Done creating assembly")))
