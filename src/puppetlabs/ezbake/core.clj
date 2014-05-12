(ns puppetlabs.ezbake.core
  (:import (java.io File InputStreamReader)
           (java.util.jar JarEntry))
  (:require [me.raynes.fs :as fs]
            [leiningen.core.project :as project]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clj-time.local :as local-time]
            [stencil.core :as stencil]
            [puppetlabs.ezbake.dependency-utils :as deputils]
            [puppetlabs.config.typesafe :as ts]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Vars

(def ^:dynamic resource-path
  ;; This is bound dynamically so that the value can be modified appropriately
  ;; when run in leiningen plugin context. When ezbake is run as an app (ie,
  ;; from a git repo head) the default given here is sufficient, but when run as
  ;; a plugin on a project, this is necessary to ensure that files are copied to
  ;; the project's preferred resources directory.
  "resources")
(def resource-prefix "puppetlabs/lein-ezbake/")
(def staging-dir "target/staging")
(def shared-bin-prefix "ext/bin/")
(def shared-config-prefix "ext/config/")
(def shared-cli-apps-prefix "ext/cli/")
(def docs-prefix "ext/docs/")
(def terminus-prefix "puppet/")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Shell / Filesystem Helpers

(defn get-resource-file
  [resource-type args]
  (->> (concat [resource-path resource-prefix resource-type] args)
       (str/join "/")
       io/file))

(defn get-template-file
  [& args]
  (get-resource-file "template" args))

(defn get-staging-template-file
  [& args]
  (get-resource-file "staging-templates" args))

(defn exec
  [& args]
  (let [result (apply sh/sh args)]
    (when (not= 0 (:exit result))
      (throw (RuntimeException. (str
                                  "Failed to execute shell command:\n\t"
                                  (str/join " " args)
                                  "\n\nOutput:"
                                  (:out result)
                                  (:err result)))))
    result))

(defn staging-dir-git-cmd
  [& args]
  (apply exec "git"
         (format "--git-dir=%s" (fs/file staging-dir ".git"))
         (format "--work-tree=%s" staging-dir)
         args))

(defn files-from-dir-iter
  "Given an individual entry from a `fs/iterate-dir` result set, return a flat
  list of File objects for all of the files referenced in the entry."
  [iter-entry]
  {:pre [(vector? iter-entry)
         (= 3 (count iter-entry))]
   :post [(coll? %)
          (every? #(instance? File %) %)]}
  (let [dir   (first iter-entry)
        files (get iter-entry 2)]
    (->> files
         (remove (partial re-find #"^\."))
         (map #(fs/file dir %)))))

(defn find-files-recursively
  "Given a File object representing a directory, walks the directory (recursively)
  and returns a list of File objects for all of the files in the directory.
  (Return value does not include directories, only regular files.)"
  [dir]
  {:pre [(instance? File dir)
         (.isDirectory dir)]
   :post [(coll? %)
          (every? #(instance? File %) %)]}
  (let [iter (fs/iterate-dir dir)]
    (mapcat files-from-dir-iter iter)))

(defn get-ezbake-sha
  "Get the commit SHA of the current ezbake working copy, plus an asterisk if the
  working tree is dirty."
  []
  (let [sha     (str/trim (:out (exec "git" "rev-parse" "HEAD")))
        dirty?  (not= "" (str/trim (:out (exec "git" "diff" "--shortstat"))))]
    (str sha (if dirty? "*" ""))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; General Staging Helper functions

(defn clean
  []
  (println "deleting staging directory:" staging-dir)
  (fs/delete-dir staging-dir))

(defn relativize
  "Convert an absolute File to a relative File"
  [base-path absolute-file]
  (let [base-file (if (instance? File base-path)
                    base-path
                    (File. base-path))]
    (-> base-file
        .toURI
        (.relativize (.toURI absolute-file))
        .getPath
        (File.))))

(defn remove-erb-extension
  [f]
  {:pre [(or (instance? JarEntry f) (instance? File f) (instance? String f))]}
  (let [filename (condp instance? f
                   JarEntry (.getName f)
                   File (.getPath f)
                   String f)]
    (if (.endsWith filename ".erb")
      (.substring filename 0 (- (.length filename) 4))
      filename)))

(defn quoted-list
  [l]
  (if (empty? l) "" (format "\"%s\"" (str/join "\",\"" l))))

(defn cp-template-files
  [template-dir]
  (println "copying template files from" (.toString template-dir) "to" staging-dir)
  (doseq [f (fs/glob (fs/file template-dir) "*")]
    (if (fs/directory? f)
      (fs/copy-dir f staging-dir)
      (fs/copy+ f (format "%s/%s" staging-dir (fs/base-name f))))))

(defn get-project-config-dir
  [project]
  (let [configs-by-dir (fs/file "config")
        configs-by-name (fs/file "configs/" project "config")]
    (if (.isDirectory configs-by-dir)
      configs-by-dir
      configs-by-name)))

(defn get-project-file-path
  [project]
  (let [project-file-by-dir (.toString (fs/file project "project.clj"))
        project-file-by-name (.toString (fs/file "configs/" project
                                                 (str project ".clj")))]
    (if (fs/file? project-file-by-dir)
      project-file-by-dir
      project-file-by-name)))

(defn cp-project-file
  [project-file template-vars]
  (println "copying ezbake lein packaging project file" project-file)
  (spit (fs/file staging-dir "project.clj")
        (stencil/render-string (slurp project-file) template-vars)))

(defn generate-manifest-file
  [lein-project]
  (spit
    (fs/file staging-dir "ext" "ezbake.manifest")
    (format "
This package was built by the Puppet Labs packaging system.

EZBake version: %s
Release package: %s/%s (%s)
Bundled packages: %s
"
            (get-ezbake-sha)
            (:group lein-project)
            (:name lein-project)
            (:version lein-project)
            (deputils/generate-manifest-string lein-project))))

(defn- get-config-files-in
  [jar]
  (deputils/find-files-in-dir-in-jar jar shared-config-prefix))

(defn cp-shared-config-files
  [dependencies]
  (let [files (for [{:keys [project jar]} dependencies]
                [project jar (get-config-files-in jar)])]
    (doseq [[project jar config-files] files]
      (deputils/cp-files-from-jar config-files jar staging-dir))
    ;; Return just a list of the files
    (mapcat last files)))

(defn cp-project-config-file
  [project-config-dir config-file]
  (let [out-file (fs/file staging-dir "ext" "config"
                          (relativize project-config-dir config-file))
        out-dir (.getParent out-file)]
    (fs/mkdirs out-dir)
    (fs/copy config-file out-file)
    (relativize staging-dir out-file)))

(defn cp-project-config-files
  [project config-files]
  (let [project-config-dir    (get-project-config-dir project)
        project-config-files  (if (fs/directory? project-config-dir)
                                (find-files-recursively project-config-dir))
        rel-files             (for [config-file project-config-files]
                                (cp-project-config-file project-config-dir config-file))]
    (concat config-files rel-files)))

(defn- get-cli-app-files-in
  [jar]
  (deputils/find-files-in-dir-in-jar jar shared-cli-apps-prefix))

(defn cp-shared-cli-app-files
  [dependencies]
  (let [files (for [{:keys [project jar]} dependencies]
                [project jar (get-cli-app-files-in jar)])
        cli-dir (str template-dir-prefix "/global/ext/cli")]
    (doseq [[project jar cli-app-files] files]
      (deputils/cp-files-from-jar cli-app-files jar staging-dir))
    (doseq [f (fs/glob (fs/file cli-dir) "*")]
      (fs/copy+ f (format "%s/%s/%s" staging-dir "ext/cli" (fs/base-name f))))
    ;; Return just a list of the files
    (concat (mapcat last files)
            (map #(str "ext/cli/" %) (fs/list-dir cli-dir)))))

(defn get-real-name
  [project-name]
  (str/replace-first project-name #"^pe-" ""))

(defn cp-cli-wrapper-scripts
  [project]
  (fs/copy+ (get-staging-template-file "cli-app.erb")
            (fs/file staging-dir "ext" "bin" (str (get-real-name project) ".erb"))))

(defn get-out-dir-for-doc-file
  [dep jar-entry]
  (let [proj-name (name (first dep))
        orig-file (File. (.getName jar-entry))
        ;; This is a bit complex; we want to put the doc files into the staging
        ;; dir under `ext/docs/<project-name>/<original-dir-structure-in-project`.
        ;; To build this path we need to find <original-dir-structure-in-project>
        ;; and then remove the first two elements (which will be "ext/docs").
        rel-dir (->> orig-file
                     .getParentFile
                     .toPath
                     .iterator
                     iterator-seq
                     (drop 2)
                     (mapv #(.toString %))
                     (str/join "/")
                     (File.))]
    (fs/file staging-dir "ext" "docs" proj-name rel-dir)))

(defn cp-doc-files
  [lein-project]
  (mapv (partial relativize staging-dir)
        (deputils/cp-files-of-type lein-project "doc"
                                   docs-prefix get-out-dir-for-doc-file)))

(defn- get-bin-files-in
  [jar]
  (deputils/find-files-in-dir-in-jar jar shared-bin-prefix))

(defn cp-shared-bin-files
  [dependencies]
  (let [files (for [{:keys [project jar]} dependencies]
                [project jar (get-bin-files-in jar)])]
    (doseq [[project jar bin-files] files]
      (deputils/cp-files-from-jar bin-files jar staging-dir))
    ;; Return just a list of the files
    (mapcat last files)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Upstream EZBake config handling

(defn get-local-ezbake-var
  "Get the value of a variable from the local ezbake config (inside of the
  ezbake lein project file."
  [lein-project key default]
  (get-in lein-project [:ezbake key]
          default))

(defn get-ezbake-vars
  [lein-project build-target]
  ;; This function should build up a map of variables that are allowed to
  ;; be interpolated into an upstream ezbake config file.  For now, the only one
  ;; we've had a need for is :user.
  {:user (get-local-ezbake-var lein-project :user (:name lein-project))})

(defn interpolate-ezbake-config
  [ezbake-vars s]
  {:pre [(map? ezbake-vars)
         (string? s)]
   :post [(string? %)]}
  ;; TODO: now that we've introduced a dependency on stencil/mustache, we probably
  ;; might as well replace the heredoc-y template stuff elsewhere in this file
  ;; with it as well.
  (stencil/render-string s ezbake-vars))

(defn get-ezbake-value
  [ezbake-vars upstream-ezbake-configs build-target os ezbake-keyword]
  {:pre [(map? ezbake-vars)
         (map? upstream-ezbake-configs)
         (string? build-target)
         (contains? #{:redhat :debian} os)
         (keyword? ezbake-keyword)]}
  (map (partial interpolate-ezbake-config ezbake-vars)
       (mapcat #(get-in % [:ezbake (keyword build-target) os ezbake-keyword])
               (vals upstream-ezbake-configs))))

(defn add-ezbake-config-to-map
  [acc [proj-name config-stream]]
  (assoc acc proj-name (ts/reader->map config-stream)))

(defn get-upstream-ezbake-configs
  [lein-project]
  (let [upstream-config-streams (deputils/file-file-in-jars lein-project "ext/ezbake.conf")]
    (reduce add-ezbake-config-to-map {} upstream-config-streams)))

(defn- get-terminus-files-in
  [jar]
  (deputils/find-files-in-dir-in-jar jar terminus-prefix))

(defn- prefix-project-name
  [project build-target]
    (if (= build-target "pe")
      (str "pe-" (name project))
      (name project)))

(defn generate-terminus-list
  [dependencies build-target]
  (for [{:keys [project version jar]} dependencies
        :let [terminus-files (get-terminus-files-in jar)]
        :when (not (empty? terminus-files))]
    [(prefix-project-name project build-target) version terminus-files jar]))

(defn cp-terminus-files "Stage all terminus files. Returns a sequence zipping project names and
  their terminus files."
  [dependencies build-target]
  (let [files (generate-terminus-list dependencies build-target)]
    (doseq [[project version terminus-files jar] files]
      (println (str "Staging terminus files for " project " version " version))
      (deputils/cp-files-from-jar terminus-files jar staging-dir))
    ;; Remove the jars from the returned data
    (map (partial take 3) files)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Ephemeral Git Repo functions

(defn get-timestamp-string
  []
  (-> (local-time/format-local-time (local-time/local-now) :date-hour-minute)
      ;; packaging system expects for there to be no colons or dashes after
      ;; the 'x.y.z-' version string prefix
      (str/replace ":" "")
      (str/replace "-" ".")))

(defn generate-git-tag-from-version
  [lein-version]
  {:pre [(string? lein-version)]
   :post [(string? %)]}
  (if (.endsWith lein-version "-SNAPSHOT")
    (format "%s.%s"
            (str/replace lein-version "-" ".")
            (get-timestamp-string))
    lein-version))

;; TODO: this is a horrible, horrible hack; I can't yet see a good way to
;; let the packaging library know what the version number is without faking
;; up a git tag; it seems like the packaging code is pretty well hard-coded
;; to try to pull this info from git.
(defn create-git-repo
  [lein-project]
  (println "Creating temporary git repo")
  (exec "git" "init" staging-dir)
  (println "Adding all files to git repo")
  (staging-dir-git-cmd "add" "*")
  (println "Committing git repo")
  (staging-dir-git-cmd "commit" "-m" "'Temporary git repo to house packaging code'")
  (let [git-tag (generate-git-tag-from-version (:version lein-project))]
    (println "Tagging git repo at" git-tag)
    (staging-dir-git-cmd "tag" "-a" git-tag "-m" "Tag for packaging code")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; File templates

;; TODO: this is wonky; we're basically doing some templating here and it
;; might make more sense to use an actual template for it.  However, I'm a bit
;; leery of introducing another template language since we're already using
;; erb... but I think I'd have to in order to do this from clojure.  The other
;; option would be to implement all of this logic in ruby and just use erb, but
;; then I couldn't use the lein project file format as the way to represent
;; the data for a project (e.g. `configs/jvm-puppet.clj`).  I'd have to use some
;; other config format that ruby could read, and then generate a lein project
;; file from that.  All of these options sound unappealing in their own special
;; ways.
(defn generate-ezbake-config-file
  [lein-project build-target config-files cli-app-files bin-files terminus-files]
  (println "generating ezbake config file")
  (let [upstream-ezbake-configs (get-upstream-ezbake-configs lein-project)
        ezbake-vars             (get-ezbake-vars lein-project build-target)
        termini (for [[name version files] terminus-files]
                  {:name name
                   :version version
                   :files (quoted-list files)})]
    (spit
      (fs/file staging-dir "ezbake.rb")
      (stencil/render-string
        (slurp (get-staging-template-file "ezbake.rb.mustache"))
        {:project                   (:name lein-project)
         :real-name                 (get-real-name (:name lein-project))
         :user                      (get-local-ezbake-var lein-project :user
                                                          (:name lein-project))
         :group                     (get-local-ezbake-var lein-project :group
                                                          (:name lein-project))
         :uberjar-name              (:uberjar-name lein-project)
         :config-files              (quoted-list (map remove-erb-extension config-files))
         :cli-app-files             (quoted-list (map remove-erb-extension cli-app-files))
         :bin-files                 (quoted-list bin-files)
         :create-varlib             (str (boolean (get-local-ezbake-var lein-project :create-varlib false)))
         :debian-deps               (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :debian :dependencies))
         :debian-preinst            (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :debian :preinst))
         :debian-postinst           (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :debian :postinst))
         :debian-install            (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :debian :install))
         :debian-post-start-action  (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :debian :post-start-action))
         :redhat-deps               (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :redhat :dependencies))
         :redhat-preinst            (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :redhat :preinst))
         :redhat-postinst           (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :redhat :postinst))
         :redhat-install            (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :redhat :install))
         :redhat-post-start-action  (quoted-list (get-ezbake-value ezbake-vars upstream-ezbake-configs build-target :redhat :post-start-action))
         :terminus-map              termini
         :replaces-pkgs             (get-local-ezbake-var lein-project :replaces-pkgs [])
         :start-timeout             (get-local-ezbake-var lein-project :start-timeout "60")
         :main-namespace            (get-local-ezbake-var lein-project
                                                          :main-namespace
                                                          "puppetlabs.trapperkeeper.main")
         :java-args                 (get-local-ezbake-var lein-project :java-args
                                                          "-Xmx192m")}))))

(defn generate-project-data-yaml
  [lein-project build-target]
  (println "generating project_data.yaml file")
  (spit
    (fs/file staging-dir "ext" "project_data.yaml")
    (stencil/render-string
      (slurp (get-staging-template-file "project_data.yaml.mustache"))
      {:project       (:name lein-project)
       :summary       (:description lein-project)
       :description   (format "%s (%s)"
                              (:description lein-project)
                              (deputils/generate-manifest-string lein-project))
       :uberjar-name  (:uberjar-name lein-project)
       :is-pe-build   (format "%s" (= (get-local-ezbake-var lein-project :build-type "foss") "pe"))})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Downstream Job Handling

(defn get-package-build-version
  "Grabs the ephemeral repo version from packaging."
  []
  (let [raw-version-string (exec "rake" "pl:print_build_param[ref]" :dir staging-dir)]
    (second (re-matches #".*\n(.*)\n" (:out raw-version-string)))))

(defn get-downstream-job
  "Creates a DOWNSTREAM_JOB string for passing to pl:jenkins:uber_build rake
  task. Requires DOWNSTREAM_JOB_URL environment variable to be defined otherwise
  returns nil."
  []
  (let [base-url (System/getenv "DOWNSTREAM_JOB_URL")
        build-parameters {:PACKAGE_BUILD_VERSION (get-package-build-version)
                          :token (System/getenv "TOKEN_NAME")}]
    (if base-url
      (if-not (:token build-parameters)
        (throw (RuntimeException. (str "Environment variable $TOKEN_NAME "
                                       "required to trigger $DOWNSTREAM_JOB "
                                       "remotely.")))
        (str "DOWNSTREAM_JOB=" base-url "/buildWithParameters?"
             (str/join "&" (map #(str (name %1) "=" %2)
                                (keys build-parameters)
                                (vals build-parameters))))))))

(defn usage
  []
  (str/join \newline ["EZBake can be used to generate native packages suitable for"
                      "consumption or an artifact ready for packaging"
                      ""
                      "Usage: lein run action"
                      ""
                      "Actions:"
                      "  stage <project-name>      Generate and stage ezbake artifacts"
                      "  build <project-name>      Build native packages from staged artifacts"
                      ""]))

(defn exit
  [status msg]
  (println msg)
  (System/exit status))

(defmulti ezbake-action
  (fn [action & params] action))

(defmethod ezbake-action "stage"
  [_ project project-file lein-project build-target template-dir]
  (cp-template-files template-dir)
  (cp-template-files (get-template-file "global"))
  (let [dependencies (deputils/get-dependencies-with-jars lein-project)
        config-files (cp-shared-config-files dependencies)
        config-files (cp-project-config-files project config-files)
        cli-app-files (cp-shared-cli-app-files dependencies)
        bin-files (cp-shared-bin-files dependencies)
        terminus-files (cp-terminus-files dependencies build-target)]
    (if cli-app-files
      (cp-cli-wrapper-scripts project))
    (cp-doc-files lein-project)
    (generate-ezbake-config-file lein-project build-target config-files cli-app-files bin-files terminus-files)
    (generate-project-data-yaml lein-project build-target)
    (generate-manifest-file lein-project)
    (create-git-repo lein-project)))

; TODO: make PE_VER either command line or config file driven
(defmethod ezbake-action "build"
  [_ project project-file lein-project build-target template-dir]
  (ezbake-action "stage" project project-file lein-project build-target template-dir)
  (exec "rake" "package:bootstrap" :dir staging-dir)
  (let [downstream-job (get-downstream-job)
        rake-call (if (= build-target "foss")
                    ["rake" "pl:jenkins:uber_build"]
                    ["rake" "pe:jenkins:uber_build" "PE_VER=3.7"])
        full-command (if downstream-job
                       ["env" downstream-job rake-call]
                       rake-call)]
    (if downstream-job
      (println "Using" downstream-job))
    (println (:out (apply exec (flatten [full-command :dir staging-dir]))))))

(defmethod ezbake-action :default
  [action & params]
  (exit 1 (str/join \newline ["Unrecognized option:" action "" (usage)])))

(defn ezbake-project
  [ezbake-project-name]
  (let [lein-project (-> (fs/file staging-dir "project.clj")
                         .toString
                         project/read)
        ezbake-dependencies (-> (:lein-ezbake lein-project)
                                (get :dependencies))]
    (if ezbake-dependencies
      (merge lein-project {:dependencies ezbake-dependencies})
      lein-project)))

(defn ezbake-init
  [action project template-vars]
  (clean)
  (fs/mkdirs staging-dir)
  (let [project-file (get-project-file-path project)
        template-vars (->> template-vars
                        (map #(str/split % #"="))
                        (into {}))]
    (cp-project-file project-file template-vars)
    (let [lein-project (ezbake-project project)
          build-target (get-local-ezbake-var lein-project :build-type "foss")
          template-dir (get-template-file build-target)
          project-name (:name lein-project)]
      (ezbake-action action project-name project-file lein-project build-target template-dir))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Main

(defn -main
  [& args]
  (if (>= (count args) 2)
    (let [[action project & template-vars] args]
      (if-not (every? (partial re-find #"=") template-vars)
        (exit 1 "Arguments after the project name are expected to be variable bindings of the form <variable>=<value>")
        (try
          (ezbake-init action project template-vars)
          (finally
            ;; this is required in order to make the threads started by sh/sh terminate,
            ;; and thus allow the jvm to exit
            (shutdown-agents)))))
    (println "Incorrect # of arguments. Expected 2, received:" (count args) "\n\n" (usage))))
