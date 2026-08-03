(ns csv-cleaver.encoding
  "Works out how a CSV file is encoded, so it can be read without mangling
   accented characters and written back the same way.

   Nothing here asks the user anything. The detection order is:

     1. A byte-order mark, if present, is definitive.
     2. Otherwise, if the opening bytes decode cleanly as UTF-8, it is UTF-8.
        Real-world non-UTF-8 files fail this test almost immediately, because
        the high bytes they use are not valid UTF-8 continuation sequences.
     3. Otherwise windows-1252, which is what Excel writes on Windows and which
        can decode any byte sequence at all, so it never throws."
  (:import
   (java.io File FileInputStream)
   (java.nio ByteBuffer CharBuffer)
   (java.nio.charset Charset CodingErrorAction)))

(def sample-size
  "Bytes read from the head of the file when sniffing the encoding."
  65536)

(def fallback-charset-name
  "Used when the bytes are not valid UTF-8. Decodes anything, never throws."
  "windows-1252")

(def bom-signatures
  "Byte-order marks, longest first: the UTF-32LE mark begins with the whole of
   the UTF-16LE mark, so testing shortest-first would misidentify it."
  [{:id :utf-32le :signature [0xFF 0xFE 0x00 0x00] :charset "UTF-32LE"}
   {:id :utf-32be :signature [0x00 0x00 0xFE 0xFF] :charset "UTF-32BE"}
   {:id :utf-8    :signature [0xEF 0xBB 0xBF]      :charset "UTF-8"}
   {:id :utf-16le :signature [0xFF 0xFE]           :charset "UTF-16LE"}
   {:id :utf-16be :signature [0xFE 0xFF]           :charset "UTF-16BE"}])

(defn read-sample
  "Read up to `sample-size` bytes from the head of `file`. Returns a byte array,
   which is shorter than requested when the file itself is shorter."
  ^bytes [^File file]
  (with-open [in (FileInputStream. file)]
    (let [buf (byte-array sample-size)
          n   (loop [total 0]
                (if (>= total sample-size)
                  total
                  (let [read (.read in buf total (- sample-size total))]
                    (if (neg? read)
                      total
                      (recur (+ total read))))))]
      (if (= n sample-size)
        buf
        (java.util.Arrays/copyOf buf ^int n)))))

(defn match-bom
  "Return the signature map whose byte-order mark opens `data`, or nil."
  [^bytes data]
  (let [len (alength data)]
    (some (fn [{:keys [signature] :as sig}]
            (when (and (>= len (count signature))
                       (every? (fn [i]
                                 (= (nth signature i)
                                    (bit-and (aget data ^int i) 0xFF)))
                               (range (count signature))))
              sig))
          bom-signatures)))

(defn decodes-cleanly?
  "True when `data` decodes as `charset` with no malformed or unmappable input.

   `end-of-input?` must be false for a sample taken from a longer file:
   a multi-byte character straddling the end of the sample is a truncation, not
   a malformed sequence, and would otherwise be misreported as an error."
  [^bytes data ^Charset charset end-of-input?]
  (let [decoder (doto (.newDecoder charset)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))
        chars   (CharBuffer/allocate
                 (inc (int (* (.maxCharsPerByte decoder) (alength data)))))
        result  (.decode decoder (ByteBuffer/wrap data) chars
                         (boolean end-of-input?))]
    (not (.isError result))))

(defn detect
  "Inspect `file` and return how to read it:

     :charset     a java.nio.charset.Charset
     :label       what to show the user, e.g. \"UTF-8\"
     :bom-length  bytes of byte-order mark to skip, 0 when there is none
     :basis       :bom, :utf-8 or :fallback — why we settled on this charset

   An empty file is reported as UTF-8, which is the harmless choice: there are
   no bytes to get wrong."
  [^File file]
  (let [sample    (read-sample file)
        truncated (= (alength sample) sample-size)]
    (if-let [{:keys [charset signature]} (match-bom sample)]
      {:charset    (Charset/forName charset)
       :label      charset
       :bom-length (count signature)
       :bom-bytes  signature
       :basis      :bom}
      (if (decodes-cleanly? sample (Charset/forName "UTF-8") (not truncated))
        {:charset    (Charset/forName "UTF-8")
         :label      "UTF-8"
         :bom-length 0
         :bom-bytes  []
         :basis      :utf-8}
        {:charset    (Charset/forName fallback-charset-name)
         :label      fallback-charset-name
         :bom-length 0
         :bom-bytes  []
         :basis      :fallback}))))

(defn describe
  "One plain sentence about `detection`, for the file card. Deliberately avoids
   the words \"byte-order mark\" and \"codec\"."
  [{:keys [label basis]}]
  (case basis
    :bom      (str "Text: " label)
    :utf-8    "Text: UTF-8"
    :fallback (str "Text: " label " (Western European)")))
