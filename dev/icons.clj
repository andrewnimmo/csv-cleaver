(ns icons
  "Draws the application icon and writes every platform asset from one
   geometry. Run with `bb icons`.

   JavaFX rather than an SVG rasteriser, for the same reason `bb shots` renders
   with JavaFX: the project already ships it on every platform, so icon
   generation needs no tool that a contributor might not have. The geometry
   mirrors package/icon.svg — icon B from the option sheet, one file becoming
   two — and the two must be edited together.

   What it writes, all committed to the repository:

     package/macos/icon.icns    via iconutil, so macOS only; the other assets
                                regenerate anywhere. The tile is inset to 80.5%
                                on macOS, which is Apple's own grid — a
                                full-bleed tile looks swollen next to every
                                other icon in the Dock.
     package/windows/icon.ico   PNG-compressed ICO, written by hand below; the
                                format is a 6-byte header, 16 bytes per entry,
                                then the PNGs. Vista and later read this.
     package/linux/icon.png     512 px, what jpackage wants for .deb.
     target/icon-contact-sheet.png  every size on one image, for eyeballing."
  (:require
   [clojure.java.io :as io])
  (:import
   (java.awt.image BufferedImage)
   (java.io ByteArrayOutputStream DataOutputStream File)
   (javafx.application Platform)
   (javafx.embed.swing SwingFXUtils)
   (javafx.scene Group Scene SnapshotParameters)
   (javafx.scene.image WritableImage)
   (javafx.scene.paint Color CycleMethod LinearGradient Stop)
   (javafx.scene.shape Rectangle)
   (javafx.scene.transform Transform)
   (javax.imageio ImageIO)))

(def lime-top    (Color/web "#8fd41f"))
(def lime-foot   (Color/web "#65a30d"))
(def lime-detail (Color/web "#65a30d" 0.85))
(def ink         (Color/web "#1a2e05" 0.9))
(def paper       Color/WHITE)

(defn- rect
  ([x y w h fill] (rect x y w h fill 0))
  ([x y w h fill arc]
   (doto (Rectangle. x y w h)
     (.setFill fill)
     (.setArcWidth (* 2.0 arc))
     (.setArcHeight (* 2.0 arc)))))

(defn icon-node
  "Icon B in a 128-unit space, optionally inset for the macOS grid.

   `inset` is the fraction of the canvas the tile occupies — 1.0 full bleed,
   0.805 for macOS. The drawing itself never changes; it is scaled and centred.

   The transparent backing rectangle is not decoration. snapshot renders a
   node's *bounds*, translating their origin to zero — so without a backing
   that pins the bounds to the full canvas, the centring translate is silently
   cancelled and the scaled tile lands in the top-left corner. That shipped:
   the first icns had all of its margin on the right and bottom, which in the
   Dock read as an icon sitting high and left of every neighbour."
  [inset]
  (let [tile-fill (LinearGradient. 0.0 0.0 0.0 1.0 true CycleMethod/NO_CYCLE
                                   (into-array Stop [(Stop. 0.0 lime-top)
                                                     (Stop. 1.0 lime-foot)]))
        g (Group.)
        add (fn [node] (.add (.getChildren g) node))]
    (add (rect 0 0 128 128 tile-fill 29))
    ;; The tall file on the left…
    (add (rect 24 34 34 60 paper))
    (add (rect 24 34 34 7 ink))
    (add (rect 30 48 22 5 lime-detail 2))
    (add (rect 30 60 22 5 lime-detail 2))
    (add (rect 30 72 22 5 lime-detail 2))
    ;; …becomes the two shorter files on the right.
    (add (rect 72 26 32 34 paper))
    (add (rect 72 26 32 6 ink))
    (add (rect 78 38 20 5 lime-detail 2))
    (add (rect 72 68 32 34 paper))
    (add (rect 72 68 32 6 ink))
    (add (rect 78 80 20 5 lime-detail 2))
    (when (< inset 1.0)
      (let [s inset
            off (/ (* 128.0 (- 1.0 s)) 2.0)]
        (.add (.getTransforms g) (Transform/translate off off))
        (.add (.getTransforms g) (Transform/scale s s))))
    (let [backing (doto (Rectangle. 0 0 128 128)
                    (.setFill Color/TRANSPARENT))
          outer   (Group.)]
      (.add (.getChildren outer) backing)
      (.add (.getChildren outer) g)
      outer)))

(defn render
  "The icon at `size` pixels as a BufferedImage with alpha."
  ^BufferedImage [size inset]
  (let [node   (icon-node inset)
        _      (Scene. (Group. (into-array javafx.scene.Node [node])))
        scale  (/ (double size) 128.0)
        params (doto (SnapshotParameters.)
                 (.setFill Color/TRANSPARENT)
                 (.setTransform (Transform/scale scale scale)))
        img    (WritableImage. (int size) (int size))]
    (.snapshot node params img)
    (SwingFXUtils/fromFXImage img nil)))

(defn alpha-margins
  "How much fully transparent border surrounds the visible content, in pixels,
   as [left right top bottom]. The check the first icns would have failed."
  [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)
        opaque? (fn [x y] (> (bit-and (bit-shift-right (.getRGB img x y) 24) 0xff) 8))
        xs (for [y (range h) x (range w) :when (opaque? x y)] x)
        ys (for [y (range h) x (range w) :when (opaque? x y)] y)]
    (if (empty? xs)
      [w w h h]
      [(apply min xs) (- w 1 (apply max xs))
       (apply min ys) (- h 1 (apply max ys))])))

(defn assert-margins!
  "Refuse to write an icon whose content is not where it claims to be.
   `expected` is the margin each side should have; a tolerance of two pixels
   absorbs antialiasing at the tile's rounded corners."
  [^BufferedImage img expected what]
  (let [[l r t b] (alpha-margins img)]
    (when-not (every? #(<= (Math/abs (- (long %) (long expected))) 2) [l r t b])
      (throw (ex-info (str what ": margins L" l " R" r " T" t " B" b
                           ", expected ~" expected " on every side")
                      {})))))

(defn write-png! [^BufferedImage img ^File out]
  (io/make-parents out)
  (ImageIO/write img "png" out))

(defn png-bytes ^bytes [^BufferedImage img]
  (let [baos (ByteArrayOutputStream.)]
    (ImageIO/write img "png" baos)
    (.toByteArray baos)))

(def ico-sizes [16 24 32 48 64 128 256])

(defn write-ico!
  "A PNG-compressed .ico. Windows reads PNG entries since Vista, and writing
   the container by hand is sixteen bytes of arithmetic per entry — far less to
   trust than a dependency."
  [images ^File out]
  (io/make-parents out)
  (let [pngs   (mapv png-bytes images)
        count' (count pngs)
        header (+ 6 (* 16 count'))]
    (with-open [o (DataOutputStream. (io/output-stream out))]
      (doto o
        (.writeShort 0)                                   ; reserved, LE-safe: 0
        (.write (byte-array [1 0]))                       ; type 1 = icon, LE
        (.write (byte-array [(bit-and count' 0xff) 0])))  ; count, LE
      (loop [offset header [img & more] images [png & pngs'] pngs]
        (when img
          (let [side (.getWidth ^BufferedImage img)
                len  (alength ^bytes png)
                le32 (fn [n] (byte-array [(bit-and n 0xff)
                                          (bit-and (bit-shift-right n 8) 0xff)
                                          (bit-and (bit-shift-right n 16) 0xff)
                                          (bit-and (bit-shift-right n 24) 0xff)]))]
            (doto o
              (.writeByte (if (= side 256) 0 side))       ; 0 means 256
              (.writeByte (if (= side 256) 0 side))
              (.writeByte 0) (.writeByte 0)               ; palette, reserved
              (.write (byte-array [1 0]))                 ; planes, LE
              (.write (byte-array [32 0]))                ; bit depth, LE
              (.write ^bytes (le32 len))
              (.write ^bytes (le32 offset)))
            (recur (+ offset len) more pngs'))))
      (doseq [^bytes png pngs] (.write o png)))))

(def iconset-entries
  "Name and pixel size of every file iconutil expects."
  [["icon_16x16.png" 16]      ["icon_16x16@2x.png" 32]
   ["icon_32x32.png" 32]      ["icon_32x32@2x.png" 64]
   ["icon_128x128.png" 128]   ["icon_128x128@2x.png" 256]
   ["icon_256x256.png" 256]   ["icon_256x256@2x.png" 512]
   ["icon_512x512.png" 512]   ["icon_512x512@2x.png" 1024]])

(defn write-iconset!
  ^File [dir]
  (doseq [[name size] iconset-entries]
    (let [img (render size 0.805)]
      (assert-margins! img (Math/round (* size 0.0975)) name)
      (write-png! img (io/file dir name))))
  (io/file dir))

(defn contact-sheet!
  "Every size on one transparent image, largest first, for eyeballing."
  [^File out]
  (let [sizes  [256 128 64 48 32 24 16]
        gap    16
        width  (+ gap (reduce + (map #(+ % gap) sizes)))
        height (+ 256 (* 2 gap))
        sheet  (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g      (.createGraphics sheet)]
    (loop [x gap [s & more] sizes]
      (when s
        (.drawImage g (render s 1.0) x (- (+ 256 gap) s) nil)
        (recur (+ x s gap) more)))
    (.dispose g)
    (write-png! sheet out)))

(defn -main [& _]
  ;; The toolkit needs starting exactly once, and Platform/startup is the way
  ;; to do that without a Stage.
  (Platform/startup (fn []))
  (let [done (promise)]
    (Platform/runLater
     (fn []
       (try
         (let [full (render 512 1.0)]
           (assert-margins! full 0 "linux 512")
           (write-png! full (io/file "package/linux/icon.png")))
         ;; The About dialog shows the icon too, from the classpath. Full
         ;; bleed: inside a window it is artwork, not a Dock resident.
         (write-png! (render 256 1.0) (io/file "resources/icon.png"))
         (write-ico! (map #(render % 1.0) ico-sizes)
                     (io/file "package/windows/icon.ico"))
         (write-iconset! (io/file "target/icon.iconset"))
         (contact-sheet! (io/file "target/icon-contact-sheet.png"))
         (deliver done :ok)
         (catch Throwable t (deliver done t)))))
    (let [result (deref done 60000 :timeout)]
      (when-not (= :ok result)
        (println "Icon generation failed:" result)
        (System/exit 1))))
  (println "Wrote package/linux/icon.png, package/windows/icon.ico,")
  (println "target/icon.iconset and target/icon-contact-sheet.png.")
  (println "On macOS, finish with:")
  (println "  iconutil -c icns target/icon.iconset -o package/macos/icon.icns")
  (System/exit 0))
