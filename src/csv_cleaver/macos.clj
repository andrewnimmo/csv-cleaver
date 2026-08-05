(ns csv-cleaver.macos
  "The one native macOS integration: an About item in the application menu.

   That menu — the bold one named after the application — is built natively by
   JavaFX's Glass toolkit (MacApplication.installDefaultMenus) and carries Hide
   and Quit but no About, with no public API to add one. java.awt.Desktop's
   AboutHandler cannot help: it handles an event that AWT never receives when
   JavaFX owns the menu. This project shipped an About 'in the app menu' that
   did not exist by assuming otherwise.

   So this talks to the Objective-C runtime directly, through JNA: find
   NSApp's main menu, take the application submenu at index zero, and insert an
   About item whose target is a small Objective-C class defined here at
   runtime, whose one method calls back into Clojure. This is the same route
   the NSMenuFX library takes; only the minimal slice is implemented, and
   every step is guarded so that a macOS release that moves the furniture
   degrades to the menu simply not gaining the item.

   Everything here must run on the AppKit main thread — which on macOS is the
   JavaFX application thread, so callers use fx/on-fx-thread."
  (:import
   (com.sun.jna Function NativeLibrary Pointer)))

(def ^:private objc
  (delay (NativeLibrary/getInstance "objc")))

(defn- objc-fn ^Function [name]
  (.getFunction ^NativeLibrary @objc name))

(defn- sel ^Pointer [s]
  (.invokePointer (objc-fn "sel_registerName") (to-array [s])))

(defn- objc-class ^Pointer [s]
  (.invokePointer (objc-fn "objc_getClass") (to-array [s])))

(defn msg
  "objc_msgSend returning an id. Vararg-free calls only, which is all the menu
   work needs."
  ^Pointer [target selector & args]
  (.invokePointer (objc-fn "objc_msgSend")
                  (to-array (concat [target (sel selector)] args))))

(defn- msg-long
  ;; No primitive return hint: a variadic fn cannot carry one in Clojure.
  [target selector & args]
  (.invokeLong (objc-fn "objc_msgSend")
               (to-array (concat [target (sel selector)] args))))

(defn- nsstring ^Pointer [s]
  (msg (objc-class "NSString") "stringWithUTF8String:" (str s)))

(defn- nsstring->str [^Pointer p]
  (when p
    (when-let [utf8 (msg p "UTF8String")]
      (.getString utf8 0 "UTF-8"))))

;; The callback target. An Objective-C class is defined once, with a single
;; method whose implementation is a JNA callback into this atom. Both the
;; callback object and the target instance are held forever: JNA callbacks
;; that get collected leave a dangling function pointer for AppKit to call.

(defonce ^:private on-about (atom (fn [])))

;; JNA finds the native signature on a single-method interface extending its
;; marker Callback; reify cannot add methods a marker does not declare, so the
;; interface is generated here.
(gen-interface
 :name csv_cleaver.macos.AboutIMP
 :extends [com.sun.jna.Callback]
 :methods [[callback [com.sun.jna.Pointer com.sun.jna.Pointer com.sun.jna.Pointer] void]])

(defonce ^:private about-callback
  (reify csv_cleaver.macos.AboutIMP
    ;; imp signature: void handleAbout(id self, SEL _cmd, id sender)
    (callback [_ _self _cmd _sender]
      (try (@on-about) (catch Throwable _ nil)))))

(defonce ^:private target-instance
  (delay
    (let [alloc-pair (objc-fn "objc_allocateClassPair")
          add-method (objc-fn "class_addMethod")
          register   (objc-fn "objc_registerClassPair")
          cls        (.invokePointer alloc-pair
                                     (to-array [(objc-class "NSObject")
                                                "CSVCleaverAboutTarget"
                                                (long 0)]))]
      (.invoke add-method
               (to-array [cls (sel "handleAbout:") about-callback "v@:@"]))
      (.invoke register (to-array [cls]))
      (msg (msg cls "alloc") "init"))))

(defn- app-menu
  "The application submenu — item zero of NSApp's main menu — or nil when the
   furniture is not where this expects it."
  ^Pointer []
  (when-let [nsapp (msg (objc-class "NSApplication") "sharedApplication")]
    (when-let [main (msg nsapp "mainMenu")]
      (when (pos? (msg-long main "numberOfItems"))
        (msg (msg main "itemAtIndex:" (long 0)) "submenu")))))

(defonce ^:private installed-item (atom nil))

(defn install-about-item!
  "Insert an About item at the top of the application menu, above Hide and
   Quit where macOS convention puts it, firing `handler` when chosen. Returns
   true when the item is in the menu, false when anything about the native
   side was not as expected. Idempotent: a second call retitles rather than
   duplicates. Must run on the JavaFX (AppKit main) thread."
  [title handler]
  (try
    (reset! on-about handler)
    (if-let [existing @installed-item]
      (do (msg existing "setTitle:" (nsstring title))
          true)
      (if-let [menu (app-menu)]
        (let [item (-> (objc-class "NSMenuItem")
                       (msg "alloc")
                       (msg "initWithTitle:action:keyEquivalent:"
                            (nsstring title)
                            (sel "handleAbout:")
                            (nsstring "")))]
          (msg item "setTarget:" ^Pointer @target-instance)
          (msg menu "insertItem:atIndex:" item (long 0))
          (msg menu "insertItem:atIndex:"
               (msg (objc-class "NSMenuItem") "separatorItem") (long 1))
          (reset! installed-item item)
          true)
        false))
    (catch Throwable _ false)))

(defn about-item-title
  "What the application menu's first item currently says, read back from
   AppKit — the ground truth a test can hold, rather than what we intended."
  []
  (try
    (when-let [menu (app-menu)]
      (nsstring->str (msg (msg menu "itemAtIndex:" (long 0)) "title")))
    (catch Throwable _ nil)))

(defn perform-about-item!
  "Fire the application menu's first item exactly as AppKit would on a click.
   For tests: this exercises the target/selector plumbing, not our Clojure
   handler called directly."
  []
  (try
    (when-let [menu (app-menu)]
      (msg menu "performActionForItemAtIndex:" (long 0))
      true)
    (catch Throwable _ false)))
