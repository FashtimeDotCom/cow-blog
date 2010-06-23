(ns blog.db
  "This is the DB layer, using Oyako."
  (:require (clojure.contrib [sql :as sql]
                             [string :as s])
            (oyako [core :as oyako]
                   [query :as query])
            (blog [config :as config]
                  [util :as util]
                  [gravatar :as gravatar]
                  [time :as time]
                  [markdown :as markdown]))
  (:refer-clojure :exclude [comment type]))

(defn sha-256 [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (.update md (.getBytes s))
    (s/join ""
            (mapcat #(Integer/toHexString (bit-and 0xff %))
                    (into [] (.digest md))))))

(def schema
     (oyako/make-datamap config/DB
      [:posts
       [belongs-to :categories as :category]
       [belongs-to :users as :user]
       [has-many :comments]
       [habtm :tags via :post_tags]
       [belongs-to :posts as :parent parent-key :parent_id]]
      [:comments
       [belongs-to :posts as :post]]
      [:categories [has-many :posts]]
      [:tags [habtm :posts via :post_tags]]))

(oyako/def-helper with-db #'schema)

(defn id [query id] (query/where query {:id (util/safe-int id)}))
(defn url [query url] (query/where query {:url url}))
(defn title [query title] (query/where query {:title title}))
(defn post-type [query type] (query/where query {:type type}))
(defn admin? [query admin?]
  (if admin?
    (assoc query
      :except-columns nil
      :where (dissoc (:where query) :status))
    (query/where query {:status "public"})))

(query/register-query-clause :id id)
(query/register-query-clause :url url)
(query/register-query-clause :title title)
(query/register-query-clause :admin? admin?)
(query/register-query-clause :post-type post-type)

(defmethod oyako/hook [:before-save :posts] [_ post]
  (assoc post :html (markdown/markdown-to-html (:markdown post) false)))

(defmethod oyako/hook [:before-save :comments] [_ comment]
  (assoc comment :html (markdown/markdown-to-html (:markdown comment) false)))


(def ^{:private true} parent
     (query/query-> :parent
                    :columns [:id :title :status :type]))

(def comments
     (query/query-> :comments
                    :admin? false
                    :except-columns [:markdown]
                    :order "date_created asc"))

(def posts
     (query/query-> :posts
                    :admin? false
                    :include [parent :tags :category :user]
                    :except-columns [:markdown]
                    :order "date_created desc"))

(def categories
     (query/query-> :categories
                    :order "title"))

(def tags
     (query/query-> :tags
                    :order "title"))

(def users (query/query :users))

(def post_tags (query/query :post-tags))

(defn gravatar
  "Returns the URI for the gravatar for this user."
  [comment]
  (gravatar/gravatar (or (or (:email comment)
                             (:ip comment)))))

(defn count-rows
  "Returns a query for table which counts all rows in that table."
  [table]
  (query/query-> table :columns ["COUNT(*) AS count"]))

(defn tag-from-title
  "Given a title (human-readable), return a tag object.
  The title is turned into a URL by lowercasing it and
  replacing special characters."
  [title]
  (let [s (map #(if (re-matches config/TAG-CATEGORY-TITLE-REGEX (str %))
                  (str %) "-")
               (seq title))
        url (s/lower-case
             (s/replace-re #"\s+" "-" (apply str s)))]
    {:title title :url url}))

(defn update-counts
  "Iterate over everything in the database and update
  post counts for tags and categories, and comment counts
  for posts.  DB updates are only run for items that need
  to be updated."
  []
  (doseq [post (oyako/fetch-all :posts
                                :columns [:id :num_comments]
                                :include (query/query-> :comments
                                                        :columns [:post_id]
                                                        :admin? false))
          :let [c (count (:comments post))]]
    (when (not= c (:num_comments post))
      (oyako/save (assoc post :num_comments c))))
  (doseq [xs [(oyako/fetch-all :categories
                               :include (query/query-> :posts
                                                       :columns [:id :category_id]
                                                       :post-type "blog"))
              (oyako/fetch-all :tags
                               :include (query/query-> :posts
                                                       :columns [:id]
                                                       :post-type "blog"))]
          x xs
          :let [c (count (:posts x))]]
    (when (not= (:num_posts x) c)
      (oyako/save (assoc x :num_posts c)))))

(defn check-user
  "Check whether there's a user in the DB for a given
  username and password.  If so, return the user.  If not,
  return nil."
  [username password]
  (let [user (oyako/fetch-one users
                              :where {:username username})]
    (when (= (:password user)
             (sha-256 (str password (:salt user))))
      user)))

(defn create-user
  "Create a new user with a given username and password"
  [username password]
  (let [salt (sha-256 (str (java.util.UUID/randomUUID)))
        password (sha-256 (str password salt))]
    (oyako/insert :users {:username username
                          :password password
                          :salt salt})))

(defn add-tags-to-post
  "Given a post and a seq of tag titles, adds tags as appropriate."
  [post tag-titles] 
  (doseq [title tag-titles
          :let [wanted-tag (or (oyako/fetch-one :tags :where {:title title})
                               (tag-from-title title))
                tag (oyako/insert-or-select :tags wanted-tag)]]
    (oyako/insert-or-select :post_tags
                            {:post_id (:id post)
                             :tag_id (:id tag)})))

(defn remove-tags-from-post
  "Give a post and a seq of tag IDs, removes those tags from this post."
  [post tag-ids]
  (doseq [id tag-ids
          :let [tag (oyako/fetch-one :tags :id id)
                pt (oyako/fetch-one :post_tags :where {:post_id (:id post)
                                                       :tag_id  (:id tag)})]]
    (when pt
     (oyako/delete pt))))

