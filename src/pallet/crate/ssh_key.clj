(ns pallet.crate.ssh-key
  "Crate functions for manipulating SSH-keys"
  (:require
   [clojure.string :as string]
   [pallet.script.lib :as lib]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.actions
    :only [directory exec-checked-script file remote-file remote-file-content]]
   [pallet.crate :only [def-plan-fn]]))

(defn user-ssh-dir [user]
  (str (stevedore/script (~lib/user-home ~user)) "/.ssh/"))

(def-plan-fn authorize-key
  "Authorize a public key on the specified user."
  [user public-key-string & {:keys [authorize-for-user]}]
  (let [target-user (or authorize-for-user user)
        dir (user-ssh-dir target-user)
        auth-file (str dir "authorized_keys")]
    (directory dir :owner target-user :mode "755")
    (file auth-file :owner target-user :mode "644")
    (exec-checked-script
     (format "authorize-key on user %s" user)
     (var auth_file ~auth-file)
     (if-not (fgrep (quoted ~(string/trim public-key-string)) @auth_file)
       (echo (quoted ~public-key-string) ">>" @auth_file)))
    (exec-checked-script
     "Set selinux permissions"
     (~lib/selinux-file-type ~dir "user_home_t"))))

(def-plan-fn authorize-key-for-localhost
  "Authorize a user's public key on the specified user, for ssh access to
  localhost.  The :authorize-for-user option can be used to specify the
  user to who's authorized_keys file is modified."
  [user public-key-filename & {:keys [authorize-for-user] :as options}]
  (let [target-user (or authorize-for-user user)
        key-file (str (user-ssh-dir user) public-key-filename)
        auth-file (str (user-ssh-dir target-user) "authorized_keys")]
    (directory
     (user-ssh-dir target-user) :owner target-user :mode "755")
    (file auth-file :owner target-user :mode "644")
    (exec-checked-script
     "authorize-key"
     (var key_file ~key-file)
     (var auth_file ~auth-file)
     (if-not (grep (quoted @(cat @key_file)) @auth_file)
       (do
         (echo -n (quoted "from=\\\"localhost\\\" ") ">>" @auth_file)
         (cat @key_file ">>" @auth_file))))))

(def-plan-fn install-key
  "Install a ssh private key."
  [user key-name private-key-string public-key-string]
  (let [ssh-dir (user-ssh-dir user)]
    (directory ssh-dir :owner user :mode "755")
    (remote-file
     (str ssh-dir key-name)
     :owner user :mode "600"
     :content private-key-string)
    (remote-file
     (str ssh-dir key-name ".pub")
     :owner user :mode "644"
     :content public-key-string)))

(def ssh-default-filenames
     {"rsa1" "identity"
      "rsa" "id_rsa"
      "dsa" "id_dsa"})

(def-plan-fn generate-key
  "Generate an ssh key pair for the given user, unless one already
   exists. Options are:
     :filename path -- output file name (within ~user/.ssh directory)
     :type key-type -- key type selection
     :no-dir true   -- do note ensure directory exists
     :passphrase    -- new passphrase for encrypring the private key
     :comment       -- comment for new key"
  [user & {:keys [type filename passphrase no-dir comment]
           :or {type "rsa" passphrase ""}
           :as  options}]

  (let [key-type type
        path (stevedore/script
              ~(str (user-ssh-dir user)
                    (or filename (ssh-default-filenames key-type))))
        ssh-dir (.getParent (java.io.File. path))]
    (when-not (or (:no-dir options))
      (directory ssh-dir :owner user :mode "755"))
    (exec-checked-script
     "ssh-keygen"
     (var key_path ~path)
     (if-not (file-exists? @key_path)
       (ssh-keygen ~(stevedore/map-to-arg-string
                     {:f (stevedore/script @key_path)
                      :t key-type
                      :N passphrase
                      :C (or (:comment options "generated by pallet"))}))))
    (file path :owner user :mode "0600")
    (file (str path ".pub") :owner user :mode "0644")))

(def-plan-fn public-key
  "Returns the public key for the specified remote `user`. By default it returns
the user's id_rsa key from `~user/.ssh/id_rsa.pub`.

You can specify a different key type by passing :type. This assumes the public
key has a `.pub` extension.

Passing a :filename value allows direct specification of the filename.

`:dir` allows specification of a different location."
  [user & {:keys [filename dir type] :or {type "rsa"} :as options}]
  (let [filename (or filename (str (ssh-default-filenames type) ".pub"))
        path (str (or dir (user-ssh-dir user)) filename)]
    (remote-file-content path)))
