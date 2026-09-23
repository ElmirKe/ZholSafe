package kz.zholsafe.smoke;

import kz.zholsafe.ai.DetectionException;
import kz.zholsafe.ai.DetectorTimings;
import kz.zholsafe.ai.Frame;
import kz.zholsafe.ai.LabelMap;
import kz.zholsafe.ai.ModelNotAvailableException;
import kz.zholsafe.ai.OnnxRoadDetector;
import kz.zholsafe.ai.OrtSessionFactory;
import kz.zholsafe.ai.decode.DetectionDecoder;
import kz.zholsafe.ai.decode.Nms;
import kz.zholsafe.ai.decode.RawDetection;
import kz.zholsafe.ai.infer.TensorSession;
import kz.zholsafe.ai.preprocess.Nv21Preprocessor;
import kz.zholsafe.ai.spec.ModelSpec;
import kz.zholsafe.benchmark.BenchmarkResult;
import kz.zholsafe.benchmark.DetectorBenchmark;
import kz.zholsafe.benchmark.EvaluationCategory;
import kz.zholsafe.model.BoundingBox;
import kz.zholsafe.model.Detection;
import kz.zholsafe.model.ObjectClass;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Stage 2.5 real-model smoke test (DESKTOP/JVM). Drives the unmodified production chain:
 *
 * <pre>
 * JPEG/PNG → ImageFrames.nv21 (RGB→NV21, optional synthetic rotation) → Frame(NV21)
 *   → OnnxRoadDetector.detect(): Nv21Preprocessor (rotation + letterbox + normalise)
 *     → OrtTensorSession.run → ai.onnxruntime.OrtSession.run (REAL ONNX Runtime)
 *     → YoloRawDecoder / YoloEnd2EndDecoder → Nms.classAware → LabelMap → Detection[]
 * → results.json + summary.csv + annotated/*.jpg
 * </pre>
 *
 * <p>Usage:
 * <pre>
 * SmokeTestRunner --model-dir models/road/yolo11n --input demo/stage2_5/images
 *                 [--manifest demo/stage2_5/test-manifest.json] --output demo/stage2_5/results
 *                 [--rotations 0,90,180,270] [--warmup 10] [--bench-runs 30] [--bench-images a.jpg,b.jpg]
 *                 [--threads 2] [--dump-tensor name.jpg] [--reference reference.json]
 * </pre>
 * The model directory must contain model.onnx + model-spec.json + labels.txt exactly as the app
 * assets do; the detector, not this harness, reads and validates them.
 *
 * <p>Numbers produced here are a DESKTOP/JVM BENCHMARK and say nothing about Android performance.
 * Detection hit rates on a couple of dozen images are a smoke test, not accuracy (no mAP).
 */
public final class SmokeTestRunner {

    /** Stage at which a failure happened (for diagnostics). */
    enum Stage { MODEL_LOAD, IMAGE_DECODE, PREPROCESS, ORT_RUN, OUTPUT_SHAPE, DECODE, NMS, LABEL_MAP, VISUALIZATION }

    private final Map<String, String> args;
    private final Path modelDir;
    private final Path input;
    private final Path output;
    private final int[] rotations;
    private final int warmup;
    private final int benchRuns;
    private final int threads;

    private SmokeTestRunner(Map<String, String> args) {
        this.args = args;
        this.modelDir = Paths.get(require("model-dir"));
        this.input = Paths.get(require("input"));
        this.output = Paths.get(args.getOrDefault("output", "demo/stage2_5/results"));
        this.rotations = Arrays.stream(args.getOrDefault("rotations", "0,90,180,270").split(","))
                .mapToInt(s -> Integer.parseInt(s.trim())).toArray();
        this.warmup = Integer.parseInt(args.getOrDefault("warmup", "10"));
        this.benchRuns = Integer.parseInt(args.getOrDefault("bench-runs", "30"));
        this.threads = Integer.parseInt(args.getOrDefault("threads", "2"));
    }

    private String require(String k) {
        String v = args.get(k);
        if (v == null) throw new IllegalArgumentException("--" + k + " is required");
        return v;
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < argv.length; i++) {
            if (!argv[i].startsWith("--")) throw new IllegalArgumentException("unexpected argument " + argv[i]);
            String k = argv[i].substring(2);
            String v = (i + 1 < argv.length && !argv[i + 1].startsWith("--")) ? argv[++i] : "true";
            a.put(k, v);
        }
        System.exit(new SmokeTestRunner(a).run());
    }

    // ------------------------------------------------------------------------------------ main

    private int run() throws Exception {
        Files.createDirectories(output.resolve("annotated"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("stage", "2.5");
        report.put("kind", "DESKTOP/JVM REAL-MODEL SMOKE TEST — NOT Android performance, NOT an accuracy benchmark");
        report.put("environment", environment());

        // ---- model load (real detector, real ORT) ----
        long rssBeforeLoad = rssKb();
        long heapBeforeLoad = usedHeap();
        RecordingSessionFactory sessions = new RecordingSessionFactory(new OrtSessionFactory("CPU", threads));
        OnnxRoadDetector detector = new OnnxRoadDetector(modelDir.getFileName().toString(),
                new FileModelFiles(modelDir.getParent() == null ? Paths.get(".") : modelDir.getParent()), sessions);
        Map<String, Object> model = new LinkedHashMap<>();
        report.put("model", model);
        try {
            long t0 = System.nanoTime();
            detector.load();
            model.put("load_ms", (System.nanoTime() - t0) / 1e6);
        } catch (ModelNotAvailableException e) {
            model.put("status", "MODEL_LOAD FAILED");
            model.put("failed_stage", Stage.MODEL_LOAD);
            model.put("exception", e.toString());
            model.put("stack_trace", stackTrace(e));
            model.put("detector_state", detector.state() + " " + detector.stateDetail());
            writeReport(report, List.of());
            System.err.println("MODEL_LOAD FAILED: " + e);
            return 2;
        }
        ModelSpec spec = detector.spec();
        TensorSession real = sessions.session().real();
        model.put("status", "LOADED");
        model.put("model_dir", modelDir.toString());
        model.put("model_id", spec.modelId());
        model.put("model_file_sha256", sha256(modelDir.resolve(spec.modelFile())));
        model.put("spec_sha256", spec.sha256());
        model.put("input", Map.of("name", spec.inputName(), "width", spec.inputWidth(), "height", spec.inputHeight(),
                "layout", spec.layout(), "normalization", spec.normalization(), "letterbox", spec.letterbox(), "pad", spec.padValue()));
        model.put("runtime_inputs", describe(real.inputs()));
        model.put("runtime_outputs", describe(real.outputs()));
        model.put("decoder", spec.decoder());
        model.put("nms_in_model", spec.nmsInModel());
        model.put("confidence_threshold", spec.confidenceThreshold());
        model.put("iou_threshold", spec.iouThreshold());
        model.put("max_detections", spec.maxDetections());
        model.put("execution_provider", real.executionProvider());
        model.put("intra_op_threads", threads);
        model.put("label_map", labelMapInfo(detector.labelMap()));
        model.put("memory", memory(rssBeforeLoad, heapBeforeLoad));

        // ---- test images ----
        Map<String, Map<String, Object>> manifest = readManifest();
        List<Path> images = listImages();
        List<Map<String, Object>> perImage = new ArrayList<>();
        Map<String, Object> tensorDump = null;
        int okCount = 0;
        int failCount = 0;
        for (Path img : images) {
            Map<String, Object> entry = manifest.getOrDefault(img.getFileName().toString(), new LinkedHashMap<>());
            Map<String, Object> r = testImage(detector, sessions.session(), img, entry);
            perImage.add(r);
            if ("DETECTOR_OK".equals(r.get("status"))) okCount++; else failCount++;
            String dump = args.get("dump-tensor");
            if (dump != null && dump.equals(img.getFileName().toString())) {
                tensorDump = dumpTensor(spec, img);
            }
            System.out.printf(Locale.ROOT, "%-45s %-22s det=%s%n", img.getFileName(), r.get("status"), r.get("num_canonical_detections"));
        }
        report.put("images_total", images.size());
        report.put("images_detector_ok", okCount);
        report.put("images_failed", failCount);
        report.put("ort_run_calls", sessions.session().runCount());
        report.put("colour_sanity", colourSanity(spec));
        if (tensorDump != null) report.put("tensor_dump", tensorDump);

        // ---- benchmark (DESKTOP/JVM) ----
        report.put("benchmark", benchmark(detector, images, manifest));
        model.put("memory_after_inference", memory(rssBeforeLoad, heapBeforeLoad));

        writeReport(report, perImage);
        detector.close();
        System.out.println("wrote " + output.resolve("results.json") + " and summary.csv; ORT runs=" + sessions.session().runCount());
        return failCount == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------------------------ per image

    private Map<String, Object> testImage(OnnxRoadDetector detector, RecordingSessionFactory.Recording rec,
                                          Path imgPath, Map<String, Object> manifestEntry) {
        Map<String, Object> r = new LinkedHashMap<>();
        String name = imgPath.getFileName().toString();
        r.put("filename", name);
        r.put("category", manifestEntry.getOrDefault("category", "UNSPECIFIED"));
        r.put("expected_targets", manifestEntry.getOrDefault("expected", List.of()));
        ModelSpec spec = detector.spec();
        r.put("model_id", spec.modelId());
        r.put("model_input", spec.inputWidth() + "x" + spec.inputHeight());
        BufferedImage img;
        try {
            img = ImageIO.read(imgPath.toFile());
            if (img == null) throw new IOException("unsupported image format");
        } catch (IOException e) {
            r.put("status", "IMAGE_DECODE_FAILED");
            r.put("failed_stage", Stage.IMAGE_DECODE);
            r.put("exception", e.toString());
            return r;
        }
        r.put("image_width", img.getWidth());
        r.put("image_height", img.getHeight());
        r.put("orientation", img.getWidth() > img.getHeight() ? "LANDSCAPE" : img.getWidth() < img.getHeight() ? "PORTRAIT" : "SQUARE");
        r.put("aspect_ratio", Math.round(1000.0 * img.getWidth() / img.getHeight()) / 1000.0);

        // ---- rotation 0: the primary result ----
        Frame frame = ImageFrames.nv21(img, 0, 1L);
        r.put("rotation", 0);
        List<Detection> dets;
        try {
            dets = detector.detect(frame);
        } catch (DetectionException e) {
            r.put("status", "DETECTION_UNAVAILABLE");
            r.put("failed_stage", classifyFailure(e));
            r.put("exception", e.toString());
            r.put("stack_trace", stackTrace(e));
            r.put("detector_state", detector.state() + " " + detector.stateDetail());
            return r;
        }
        DetectorTimings t = detector.lastTimings();
        r.put("timings_ms", timings(t));
        r.put("output_shape", rec.lastShape());
        r.put("candidates", candidateCounts(rec, spec, detector.labelMap()));
        r.put("num_canonical_detections", dets.size());
        r.put("status", "DETECTOR_OK");
        r.put("result_kind", dets.isEmpty() ? "DETECTOR_EXECUTED_SUCCESSFULLY_ZERO_TARGET_DETECTIONS" : "DETECTOR_EXECUTED_SUCCESSFULLY_WITH_DETECTIONS");
        List<Map<String, Object>> dl = new ArrayList<>();
        for (Detection d : dets) dl.add(detection(d, detector.labelMap()));
        r.put("detections", dl);

        // expected-target check (smoke expectation, not ground truth)
        @SuppressWarnings("unchecked")
        List<Object> expected = (List<Object>) manifestEntry.getOrDefault("expected", List.of());
        Map<String, Object> exp = new LinkedHashMap<>();
        List<String> found = dets.stream().map(d -> d.objectClass().name()).distinct().sorted().collect(Collectors.toList());
        exp.put("found_classes", found);
        if (expected.isEmpty() || (expected.size() == 1 && "NONE".equals(expected.get(0)))) {
            exp.put("expected", "NONE");
            exp.put("expected_target_found", dets.isEmpty() ? "N/A (none expected)" : "N/A (none expected)");
            exp.put("unexpected_detections", dets.size());
        } else {
            List<String> missing = expected.stream().map(String::valueOf).filter(c -> !found.contains(c)).collect(Collectors.toList());
            exp.put("expected", expected);
            exp.put("expected_target_found", missing.isEmpty() ? "YES" : (missing.size() < expected.size() ? "PARTIAL" : "NO"));
            exp.put("missing", missing);
        }
        r.put("expectation", exp);

        // ---- synthetic rotation variants: boxes must stay in upright coordinates ----
        Object rotFlag = manifestEntry.get("rotation_test");
        if (Boolean.TRUE.equals(rotFlag)) {
            r.put("rotation_tests", rotationTests(detector, img, dets));
        }

        // ---- annotated image ----
        try {
            Path out = output.resolve("annotated").resolve(stripExt(name) + "_detected.jpg");
            if (!Boolean.FALSE.equals(manifestEntry.get("store_annotated"))) {
                annotate(img, dets, detector.labelMap(), out);
                r.put("annotated", output.relativize(out).toString());
            } else {
                r.put("annotated", "NOT STORED (licence)");
            }
        } catch (IOException e) {
            r.put("annotated_error", Stage.VISUALIZATION + ": " + e);
        }
        return r;
    }

    private List<Map<String, Object>> rotationTests(OnnxRoadDetector detector, BufferedImage img, List<Detection> base) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int rot : rotations) {
            if (rot == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rotation", rot);
            Frame f = ImageFrames.nv21(img, rot, 2L);
            m.put("stored_buffer", f.width() + "x" + f.height());
            m.put("upright", f.uprightWidth() + "x" + f.uprightHeight());
            try {
                List<Detection> d = detector.detect(f);
                m.put("num_detections", d.size());
                // match against rotation-0 detections by class + IoU (in upright coordinates)
                List<Map<String, Object>> matches = new ArrayList<>();
                int matched = 0;
                double iouSum = 0;
                double minIou = 1;
                for (Detection b : base) {
                    Detection best = null;
                    float bestIou = 0;
                    for (Detection c : d) {
                        if (c.objectClass() != b.objectClass()) continue;
                        float iou = b.box().iou(c.box());
                        if (iou > bestIou) { bestIou = iou; best = c; }
                    }
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("class", b.objectClass());
                    mm.put("base_bbox", bbox(b.box()));
                    mm.put("rotated_bbox", best == null ? null : bbox(best.box()));
                    mm.put("iou", best == null ? 0.0 : (double) bestIou);
                    mm.put("confidence_delta", best == null ? null : (double) (best.confidence() - b.confidence()));
                    matches.add(mm);
                    if (best != null && bestIou >= 0.5f) { matched++; iouSum += bestIou; minIou = Math.min(minIou, bestIou); }
                }
                m.put("base_detections", base.size());
                m.put("matched_iou_ge_0_5", matched);
                m.put("mean_iou_matched", matched == 0 ? null : iouSum / matched);
                m.put("min_iou_matched", matched == 0 ? null : minIou);
                m.put("consistent", base.isEmpty() ? (d.isEmpty() ? "YES (both empty)" : "NO (extra detections)") : (matched == base.size() && d.size() == base.size() ? "YES" : "PARTIAL/NO"));
                m.put("matches", matches);
                m.put("timings_ms", timings(detector.lastTimings()));
            } catch (DetectionException e) {
                m.put("status", "DETECTION_UNAVAILABLE");
                m.put("exception", e.toString());
            }
            out.add(m);
        }
        return out;
    }

    /** Re-runs decoder/NMS on the recorded raw tensor purely to REPORT stage counts; the detector's own result is untouched. */
    private static Map<String, Object> candidateCounts(RecordingSessionFactory.Recording rec, ModelSpec spec, LabelMap labels) {
        Map<String, Object> c = new LinkedHashMap<>();
        float[] data = rec.lastOutput();
        long[] shape = rec.lastShape();
        if (data == null) return c;
        try {
            DetectionDecoder dec = DetectionDecoder.forType(spec.decoder());
            c.put("raw_candidates", spec.decoder() == ModelSpec.DecoderType.YOLO_RAW_CXCYWH_NC ? shape[2] : shape[1]);
            List<RawDetection> raw = new ArrayList<>();
            dec.decode(data, shape, spec, raw);
            c.put("after_confidence_filter", raw.size());
            List<RawDetection> kept = dec.requiresNms() ? Nms.classAware(raw, spec.iouThreshold(), spec.maxDetections()) : raw;
            c.put("after_nms", kept.size());
            long supported = kept.stream().filter(x -> labels.isSupported(x.classIndex())).count();
            c.put("after_label_map", supported);
            // all model labels surviving NMS (incl. unsupported) — useful for false-positive review
            Map<String, Integer> byLabel = new LinkedHashMap<>();
            for (RawDetection x : kept) byLabel.merge(labels.labelFor(x.classIndex()), 1, Integer::sum);
            c.put("after_nms_by_model_label", byLabel);
        } catch (Exception e) {
            c.put("error", e.toString());
        }
        return c;
    }

    // ------------------------------------------------------------------------------------ benchmark

    private Map<String, Object> benchmark(OnnxRoadDetector detector, List<Path> images, Map<String, Map<String, Object>> manifest) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("label", "DESKTOP/JVM BENCHMARK — not Android");
        b.put("warmup_runs", warmup);
        b.put("measured_runs_per_image", benchRuns);
        List<Path> pick = new ArrayList<>();
        String benchArg = args.get("bench-images");
        if (benchArg != null) {
            for (String n : benchArg.split(",")) images.stream().filter(p -> p.getFileName().toString().equals(n.trim())).findFirst().ifPresent(pick::add);
        } else {
            for (Path p : images) if (Boolean.TRUE.equals(manifest.getOrDefault(p.getFileName().toString(), Map.of()).get("benchmark"))) pick.add(p);
        }
        if (pick.isEmpty() && !images.isEmpty()) pick.add(images.get(0));
        List<Map<String, Object>> per = new ArrayList<>();
        List<Double> allTotals = new ArrayList<>();
        double preSum = 0;
        double infSum = 0;
        double postSum = 0;
        int n = 0;
        DetectorBenchmark bench = new DetectorBenchmark();
        for (Path p : pick) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("image", p.getFileName().toString());
            try {
                BufferedImage img = ImageIO.read(p.toFile());
                Frame f = ImageFrames.nv21(img, 0, 3L);
                BenchmarkResult res = bench.run(detector, DetectorBenchmark.repeat(f, benchRuns), warmup, environment().get("summary").toString(), EvaluationCategory.LATENCY_ONLY);
                m.put("image_size", img.getWidth() + "x" + img.getHeight());
                m.put("measured", res.measuredFrames());
                m.put("failures", res.failures());
                m.put("preprocess", stats(res.preprocess()));
                m.put("inference", stats(res.inference()));
                m.put("postprocess", stats(res.postprocess()));
                m.put("total", stats(res.total()));
                m.put("fps_desktop", res.fps());
                // collect raw totals again for the pooled distribution
                for (int i = 0; i < benchRuns; i++) {
                    long t0 = System.nanoTime();
                    detector.detect(f);
                    allTotals.add((System.nanoTime() - t0) / 1e6);
                    DetectorTimings t = detector.lastTimings();
                    preSum += t.preprocessNanos() / 1e6;
                    infSum += t.inferenceNanos() / 1e6;
                    postSum += t.postprocessNanos() / 1e6;
                    n++;
                }
            } catch (Exception e) {
                m.put("error", e.toString());
            }
            per.add(m);
        }
        b.put("per_image", per);
        if (n > 0) {
            double[] sorted = allTotals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            Map<String, Object> pooled = new LinkedHashMap<>();
            pooled.put("runs", n);
            pooled.put("preprocess_mean_ms", preSum / n);
            pooled.put("inference_mean_ms", infSum / n);
            pooled.put("postprocess_mean_ms", postSum / n);
            pooled.put("total_mean_ms", Arrays.stream(sorted).average().orElse(Double.NaN));
            pooled.put("total_median_ms", percentile(sorted, 50));
            pooled.put("total_p95_ms", percentile(sorted, 95));
            pooled.put("total_min_ms", sorted[0]);
            pooled.put("total_max_ms", sorted[sorted.length - 1]);
            b.put("pooled", pooled);
        }
        return b;
    }

    private static double percentile(double[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
    }

    private static Map<String, Object> stats(DetectorBenchmark.Stats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mean_ms", s.meanMs());
        m.put("median_ms", s.medianMs());
        m.put("p95_ms", s.p95Ms());
        m.put("min_ms", s.minMs());
        m.put("max_ms", s.maxMs());
        return m;
    }

    // ------------------------------------------------------------------------------------ colour sanity

    /**
     * Deterministic RGB/BGR check through the PRODUCTION preprocessor: a pure-red image must land
     * in tensor channel 0 (R) with channels 1/2 near zero, both for an RGB_888 frame and for the
     * NV21 path used by the smoke test (the NV21 round trip is lossy by a few code values).
     */
    private static Map<String, Object> colourSanity(ModelSpec spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        int w = 64;
        int h = 48;
        Color[] colours = {Color.RED, Color.GREEN, Color.BLUE};
        String[] names = {"pure_red", "pure_green", "pure_blue"};
        boolean pass = true;
        for (int k = 0; k < colours.length; k++) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setColor(colours[k]);
            g.fillRect(0, 0, w, h);
            g.dispose();
            Nv21Preprocessor pp = new Nv21Preprocessor(spec);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("rgb888_channel_means", channelMeans(pp, ImageFrames.rgb888(img, 1L), spec));
            double[] nv = channelMeans(pp, ImageFrames.nv21(img, 0, 1L), spec);
            c.put("nv21_channel_means", nv);
            int dominant = 0;
            for (int i = 1; i < 3; i++) if (nv[i] > nv[dominant]) dominant = i;
            boolean ok = dominant == k && nv[k] > 0.9 && nv[(k + 1) % 3] < 0.1 && nv[(k + 2) % 3] < 0.1;
            c.put("expected_dominant_channel", k);
            c.put("observed_dominant_channel", dominant);
            c.put("pass", ok);
            pass &= ok;
            m.put(names[k], c);
        }
        m.put("channel_order", "R=0,G=1,B=2 (NCHW plane order)");
        m.put("result", pass ? "PASS — no RGB/BGR swap in Nv21Preprocessor (RGB_888 and NV21 paths)" : "FAIL — channel order suspicious");
        return m;
    }

    /** Mean per channel over the CONTENT region (excludes letterbox padding). */
    private static double[] channelMeans(Nv21Preprocessor pp, Frame f, ModelSpec spec) {
        var lb = pp.process(f);
        float[] t = pp.tensor();
        int mw = spec.inputWidth();
        int plane = mw * spec.inputHeight();
        double[] sum = new double[3];
        int n = 0;
        for (int y = lb.padY(); y < lb.padY() + lb.newHeight(); y++) {
            for (int x = lb.padX(); x < lb.padX() + lb.newWidth(); x++) {
                int p = y * mw + x;
                sum[0] += t[p];
                sum[1] += t[plane + p];
                sum[2] += t[2 * plane + p];
                n++;
            }
        }
        return new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
    }

    /** Writes the exact float32 NCHW tensor the production preprocessor produced for one image (for reference comparison). */
    private Map<String, Object> dumpTensor(ModelSpec spec, Path img) throws IOException {
        BufferedImage bi = ImageIO.read(img.toFile());
        Nv21Preprocessor pp = new Nv21Preprocessor(spec);
        var lb = pp.process(ImageFrames.nv21(bi, 0, 1L));
        float[] t = pp.tensor();
        ByteBuffer bb = ByteBuffer.allocate(t.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(t);
        Path out = output.resolve(stripExt(img.getFileName().toString()) + "_input_tensor_f32.bin");
        Files.write(out, bb.array());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("image", img.getFileName().toString());
        m.put("file", output.relativize(out).toString());
        m.put("dtype", "float32 little-endian, NCHW [1,3," + spec.inputHeight() + "," + spec.inputWidth() + "]");
        m.put("letterbox", Map.of("scale", (double) lb.scale(), "newWidth", lb.newWidth(), "newHeight", lb.newHeight(), "padX", lb.padX(), "padY", lb.padY()));
        return m;
    }

    // ------------------------------------------------------------------------------------ output

    private void writeReport(Map<String, Object> report, List<Map<String, Object>> perImage) throws IOException {
        report.put("images", perImage);
        Files.writeString(output.resolve("results.json"), Json.write(report), StandardCharsets.UTF_8);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(output.resolve("summary.csv"), StandardCharsets.UTF_8))) {
            w.println("filename,category,width,height,orientation,status,preprocess_ms,inference_ms,postprocess_ms,total_ms,"
                    + "raw_candidates,after_conf,after_nms,canonical_detections,found_classes,expected,expected_found,rotation_consistent");
            for (Map<String, Object> r : perImage) {
                @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) r.getOrDefault("timings_ms", Map.of());
                @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) r.getOrDefault("candidates", Map.of());
                @SuppressWarnings("unchecked") Map<String, Object> e = (Map<String, Object>) r.getOrDefault("expectation", Map.of());
                @SuppressWarnings("unchecked") List<Map<String, Object>> rt = (List<Map<String, Object>>) r.get("rotation_tests");
                String rotc = rt == null ? "" : rt.stream().map(x -> x.get("rotation") + ":" + x.getOrDefault("consistent", x.getOrDefault("status", "?"))).collect(Collectors.joining(" "));
                w.println(String.join(",",
                        csv(r.get("filename")), csv(r.get("category")), csv(r.get("image_width")), csv(r.get("image_height")),
                        csv(r.get("orientation")), csv(r.get("status")),
                        csv(t.get("preprocess")), csv(t.get("inference")), csv(t.get("postprocess")), csv(t.get("total")),
                        csv(c.get("raw_candidates")), csv(c.get("after_confidence_filter")), csv(c.get("after_nms")),
                        csv(r.get("num_canonical_detections")), csv(String.join(" ", listOf(e.get("found_classes")))),
                        csv(e.get("expected") instanceof List<?> l ? l.stream().map(String::valueOf).collect(Collectors.joining(" ")) : e.get("expected")),
                        csv(e.get("expected_target_found")), csv(rotc)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOf(Object o) {
        return o instanceof List<?> l ? ((List<Object>) l).stream().map(String::valueOf).collect(Collectors.toList()) : List.of();
    }

    private static String csv(Object o) {
        if (o == null) return "";
        String s = o instanceof Double d ? String.format(Locale.ROOT, "%.3f", d) : String.valueOf(o);
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    private static void annotate(BufferedImage img, List<Detection> dets, LabelMap labels, Path out) throws IOException {
        BufferedImage copy = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.setStroke(new BasicStroke(Math.max(2f, img.getWidth() / 400f)));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, img.getWidth() / 50)));
        for (Detection d : dets) {
            Color c = colour(d.objectClass());
            BoundingBox b = d.box();
            g.setColor(c);
            g.drawRect(Math.round(b.x1()), Math.round(b.y1()), Math.round(b.width()), Math.round(b.height()));
            String text = String.format(Locale.ROOT, "%s (%s) %.2f", d.objectClass().name(), labels.labelFor(d.classId()), d.confidence());
            int tw = g.getFontMetrics().stringWidth(text);
            int th = g.getFontMetrics().getHeight();
            int ty = Math.max(th, Math.round(b.y1()));
            g.fillRect(Math.round(b.x1()), ty - th, tw + 6, th);
            g.setColor(Color.BLACK);
            g.drawString(text, Math.round(b.x1()) + 3, ty - 4);
        }
        g.setColor(Color.WHITE);
        g.drawString("ZholSafe Stage 2.5 smoke test — engineering diagnostic, not a product view", 5, copy.getHeight() - 6);
        g.dispose();
        ImageIO.write(copy, "jpg", out.toFile());
    }

    private static Color colour(ObjectClass c) {
        switch (c) {
            case PERSON: return Color.YELLOW;
            case DOG: return Color.CYAN;
            case HORSE: return Color.ORANGE;
            case COW: return Color.MAGENTA;
            case SHEEP: return Color.GREEN;
            default: return Color.WHITE;
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private Map<String, Map<String, Object>> readManifest() throws IOException {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        String path = args.get("manifest");
        if (path == null) return m;
        Object parsed = JsonReader.parse(Files.readString(Paths.get(path), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> images = (List<Map<String, Object>>) ((Map<String, Object>) parsed).get("images");
        for (Map<String, Object> e : images) m.put(String.valueOf(e.get("file")), e);
        return m;
    }

    private List<Path> listImages() throws IOException {
        if (Files.isRegularFile(input)) return List.of(input);
        try (Stream<Path> s = Files.list(input)) {
            return s.filter(p -> p.toString().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png)$")).sorted().collect(Collectors.toList());
        }
    }

    private static Map<String, Object> detection(Detection d, LabelMap labels) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("object_class", d.objectClass());
        m.put("model_label", labels.labelFor(d.classId()));
        m.put("model_class_index", d.classId());
        m.put("confidence", (double) d.confidence());
        m.put("bbox", bbox(d.box()));
        return m;
    }

    private static List<Object> bbox(BoundingBox b) {
        return List.of(r1(b.x1()), r1(b.y1()), r1(b.x2()), r1(b.y2()));
    }

    private static double r1(float v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Map<String, Object> timings(DetectorTimings t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("preprocess", t.preprocessNanos() / 1e6);
        m.put("inference", t.inferenceNanos() / 1e6);
        m.put("postprocess", t.postprocessNanos() / 1e6);
        m.put("total", t.totalNanos() / 1e6);
        return m;
    }

    private static Stage classifyFailure(DetectionException e) {
        String m = String.valueOf(e.getMessage());
        if (m.startsWith("preprocess failed")) return Stage.PREPROCESS;
        if (m.startsWith("inference failed")) return Stage.ORT_RUN;
        if (m.contains("shape") || m.contains("rank")) return Stage.OUTPUT_SHAPE;
        return Stage.DECODE;
    }

    private static List<Map<String, Object>> describe(List<TensorSession.TensorInfo> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TensorSession.TensorInfo t : l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("shape", t.shape());
            m.put("element_type", t.elementType());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> labelMapInfo(LabelMap lm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("num_labels", lm.size());
        Map<String, Object> idx = new LinkedHashMap<>();
        for (int i = 0; i < lm.size(); i++) {
            if (lm.isSupported(i)) idx.put(lm.labelFor(i), Map.of("index", i, "object_class", lm.classFor(i)));
        }
        m.put("supported_model_labels", idx);
        m.put("supported_classes", lm.supportedClasses());
        List<String> notPresent = new ArrayList<>();
        for (ObjectClass c : ObjectClass.values()) if (c != ObjectClass.UNKNOWN && !lm.supportedClasses().contains(c)) notPresent.add(c.name());
        m.put("canonical_classes_not_in_model", notPresent);
        m.put("unmapped_model_labels", lm.unmappedLabels().size());
        return m;
    }

    private Map<String, Object> environment() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("arch", System.getProperty("os.arch"));
        m.put("cpus_available", Runtime.getRuntime().availableProcessors());
        m.put("cpu_model", cpuModel());
        m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version"));
        String ort;
        try {
            ort = ai.onnxruntime.OrtEnvironment.getEnvironment().getVersion();
        } catch (Throwable e) {
            ort = "unavailable: " + e;
        }
        m.put("onnxruntime_java_version", ort);
        m.put("summary", System.getProperty("os.name") + "/" + System.getProperty("os.arch") + " " + Runtime.getRuntime().availableProcessors() + "cpu ORT " + ort);
        return m;
    }

    private static String cpuModel() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) return line.substring(line.indexOf(':') + 1).trim();
            }
        } catch (IOException ignored) { }
        return "unknown";
    }

    private static Map<String, Object> memory(long rssBeforeLoadKb, long heapBeforeLoad) {
        Map<String, Object> m = new LinkedHashMap<>();
        long rss = rssKb();
        m.put("process_rss_before_load_mb", rssBeforeLoadKb < 0 ? null : rssBeforeLoadKb / 1024.0);
        m.put("process_rss_now_mb", rss < 0 ? null : rss / 1024.0);
        m.put("jvm_heap_used_before_load_mb", heapBeforeLoad / 1048576.0);
        m.put("jvm_heap_used_now_mb", usedHeap() / 1048576.0);
        m.put("note", rss < 0 ? "RSS NOT MEASURED (no /proc)" : "approximate; RSS from /proc/self/status (Linux), includes JVM + ORT native");
        return m;
    }

    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (IOException | RuntimeException ignored) { }
        return -1;
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(p));
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String stackTrace(Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String stripExt(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(0, i);
    }
}
