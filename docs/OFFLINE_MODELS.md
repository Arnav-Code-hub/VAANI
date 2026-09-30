# Offline model provenance

The app packages the models below. Neither runtime calls a speech-recognition or translation server. Fixed emergency SMS, instructions, and warnings remain in English until human-reviewed locale resources are available.

## Speech

- Engine: [whisper.cpp](https://github.com/ggml-org/whisper.cpp), commit `6e4ab854f67f743900934a703d5603419384c961` (MIT).
- Model: [ggerganov/whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp), `ggml-base-q5_1.bin`, revision `5359861c739e955e79d9a303bcbc70fb988958b1` (MIT).
- Bundled model SHA-256: `422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898`.
- Input: 16 kHz mono microphone PCM. English, Hindi, Bengali, Marathi, and Tamil are selected explicitly at inference time.
- Android provider recognition is not used. Voice protection requires a successful local test of the selected language and safeword. A test taking over 3.5 seconds of inference does not enable voice arming. Continuous mode holds the microphone open and processes bounded four-second chunks; it disables itself if inference falls behind.

## Generated text translation

- Model: [public IndicTrans2 INT8 ONNX export](https://huggingface.co/hari31416/indictrans2-en-indic-dist-200M-ONNX-int8), revision `1eaa43a3fb76f382f1b22c76e56f6ab4926ad68b` (model card: MIT). It identifies [AI4Bharat IndicTrans2 En-Indic Distilled 200M](https://huggingface.co/ai4bharat/indictrans2-en-indic-dist-200M) as its base model (MIT). The official base weights require an access agreement, so this public export has not been byte-compared with them.
- Runtime: ONNX Runtime Android 1.23.2 AAR, SHA-256 `82048d1f462218adae4ba76477089ab0ba76093d84f733540066db1a8ba6b827`.
- The model and tokenizers are packaged in `app/src/main/assets/models/indictrans2/`. Model weights are extracted into app-private storage and checked against the hashes below before first use. Translation is lazy and used only for generated Vault summaries.

| Asset | SHA-256 |
| --- | --- |
| `encoder_model.onnx` | `87c3d6c6fbc48d9d7081927f3bd59915d5443a06cb63460e0d3705bae74de5a9` |
| `encoder_model.onnx.data` | `71a4d119514411a3cb3f9d76d2d88d5c4eac7c8e907397d62d5b29e2468a0c16` |
| `decoder_model.onnx` | `58c0fafb7ba0f343acba8643a386fab08143d49ef24472cefaecc87080f44e75` |
| `decoder_with_past_model.onnx` | `a2e9a87ff7563f5fc87fe8102188be37fb66771cf30970e649ca67be7f895497` |
| `decoder_shared.onnx.data` | `902314746ac629ae2ff584709c03eecdb0b3f7be4fb70ce0ff1bfbe66f14e574` |
| `tokenizer_src.json` | `ac1930c411028ba3a7929f35bc7f62d2e5e837ca24150b661de4f4606469eeca` |
| `tokenizer_tgt.json` | `51b05cd37636765e158146534d54452be558df32c6ab06ba4caeecdd0266a580` |

The simplified Android text preprocessor and script conversion need output-parity testing against the export's Python reference. Until that passes, a translation failure shows the original English summary. Never route safety-critical copy through this model.
