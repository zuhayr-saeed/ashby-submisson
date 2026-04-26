import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
    private static final Logger LOG = Logger.getLogger("relevant-priors");
    private static final String DATA_PATH = envOrDefault("PUBLIC_DATA_PATH", "data/relevant_priors_public.json");
    private static final String HOST = envOrDefault("HOST", "0.0.0.0");
    private static final int PORT = Integer.parseInt(envOrDefault("PORT", "8000"));

    private static final Set<String> STOPWORDS = setOf(
            "and", "or", "the", "a", "an", "of", "for", "to", "with", "without", "wo", "w", "in", "on",
            "left", "right", "bilateral", "limited", "complete", "routine", "exam", "study"
    );

    private static final Map<String, List<String>> MODALITY_ALIASES = mapOfAliases(new String[][]{
            {"CT", "ct", "cta", "computed tomography", "coronary calc", "calcium score"},
            {"MR", "mri", "mr", "mra", "magnetic resonance"},
            {"XR", "xr", "xray", "x-ray", "radiograph", "plain film", "portable", "abdomen ap", "1 view", "2 view", "2v", "3 views", "3v", "ap", "lat"},
            {"US", "us", "ultrasound", "sonogram", "duplex", "doppler", "echo", "tte", "tee", "endovaginal", "transvaginal"},
            {"NM", "nm", "nuc", "nuclear", "spect", "pet", "myo perf", "bone scan", "sestamibi", "octreotide", "hepatobiliary", "hida", "renogram"},
            {"MG", "mam", "mammo", "mammogram", "mammography", "mammographic", "tomosynthesis", "breast tomo", "tomo"},
            {"FL", "fl", "fluoro", "fluoroscopy", "esophagram", "swallow", "upper gi", "ugi", "barium", "bae"},
            {"DXA", "dxa", "dexa", "bone density", "bonedensity", "densitometry"},
            {"IR", "stereotactic", "stereo bx", "biopsy", "drainage", "drain", "embolization", "venous access", "picc", "thrombolysis", "thoracentesis", "paracentesis", "fna", "guided"},
            {"WHOLEBODY_NM", "pet", "pet-ct", "pet/ct", "petct", "skull to thigh", "skullthigh", "bone scan", "octreotide", "whole body", "wholebody"}
    });

    private static final Map<String, List<String>> REGION_ALIASES = mapOfAliases(new String[][]{
            {"BRAIN_HEAD", "brain", "head", "skull", "iacs", "temporal bone", "pituitary", "internal auditory", "intracranial"},
            {"SINUS_FACE", "sinus", "orbit", "face", "facial", "maxillofacial", "maxfacial", "mandible", "tmj"},
            {"NECK", "neck", "soft tissue neck", "thyroid", "parathyroid", "larynx"},
            {"SPINE_CERVICAL", "cervical spine", "cerv spine", "c spine", "cervicl spine", "cervical"},
            {"SPINE_THORACIC", "thoracic spine", "t spine", "tspine"},
            {"SPINE_LUMBAR", "lumbar spine", "l spine", "lumbosacral", "lumbar sacral", "lumbar"},
            {"SPINE_SACRUM", "sacrum", "sacral", "coccyx"},
            {"SPINE_OTHER", "myelogram", "vertebral", "scoliosis"},
            {"CHEST", "chest", "thorax", "lung", "pulmonary", "rib", "sternum", "cxr", "mediastinum", "pleural"},
            {"CARDIAC", "heart", "cardiac", "coronary", "myo perf", "myocardial", "ffr", "tte", "tee", "pericardial", "calcium score", "coronary calc", "sestamibi", "echocardiogram", "echo", "transthorac", "transthoracic"},
            {"ABDOMEN_PELVIS", "abdomen", "abd", "pelvis", "renal", "kidney", "liver", "biliary", "pancreas", "spleen", "urogram", "kub", "gallbladder", "appendix", "peritoneal", "ascites", "hepatic", "splenic", "adrenal", "enterography", "entero", "ureter", "bladder", "prostate", "fistulogram", "colonography", "abd pel", "abdomenpelvis"},
            {"BREAST", "breast", "mam", "mammo", "mammogram", "mammography", "mammographic", "tomosynthesis", "tomo", "combohd", "combo hd", "screening combo", "screen combo", "dx bilateral", "dx unilateral", "dx combo", "diag mam", "diag combo", "diagnostic bilateral", "diagnostic unilateral", "dx bilat", "dx unilat", "screen bi", "screen unilat"},
            {"VASCULAR", "artery", "arterial", "venous", "vein", "vascular", "aorta", "aaa", "iliac", "femoral artery", "popliteal", "renal artery"},
            {"VASC_CAROTID", "carotid", "transcranial doppler"},
            {"VASC_LE", "venous doppler le", "doppler le", "le rt", "le lt", "le bi"},
            {"OB_GYN", "ob", "fetal", "pregnancy", "uterus", "ovary", "ovarian", "adnexa", "endovaginal", "transvaginal", "obstetric"},
            {"BONE_DENSITY", "dxa", "dexa", "bone density", "bonedensity", "densitometry", "appendicular skeleton"},
            {"MSK_SHOULDER", "shoulder", "scapula", "clavicle", "ac joint", "acromioclavicular"},
            {"MSK_HUMERUS_ELBOW", "humerus", "elbow"},
            {"MSK_FOREARM", "forearm", "radius", "ulna"},
            {"MSK_WRIST_HAND", "wrist", "hand", "finger", "thumb", "carpus"},
            {"MSK_HIP", "hip"},
            {"MSK_FEMUR_THIGH", "femur", "thigh"},
            {"MSK_KNEE", "knee", "patella"},
            {"MSK_TIB_FIB", "tibia", "fibula", "lower leg"},
            {"MSK_ANKLE_FOOT", "ankle", "foot", "toe", "calcaneus", "heel"},
            {"MSK_LE_OTHER", "lower extremity", "le", "long bone le", "lwr extremity", "lwr extrem", "lower extrem"},
            {"MSK_UE_OTHER", "upper extremity", "ue", "long bone ue", "upr extremity", "upr extrem", "upper extrem", "uppr extremity", "uppr extrem", "upper ext", "uppr ext"}
    });

    private static final Set<String> MSK_SUBREGIONS = setOf(
            "REG_MSK_SHOULDER", "REG_MSK_HUMERUS_ELBOW", "REG_MSK_FOREARM",
            "REG_MSK_WRIST_HAND", "REG_MSK_HIP", "REG_MSK_FEMUR_THIGH",
            "REG_MSK_KNEE", "REG_MSK_TIB_FIB", "REG_MSK_ANKLE_FOOT",
            "REG_MSK_LE_OTHER", "REG_MSK_UE_OTHER"
    );

    private static final Set<String> CROSS_REGION_PAIRS_RELATED = setOf(
            "BRAIN_HEAD|NECK", "NECK|BRAIN_HEAD",
            "BRAIN_HEAD|SINUS_FACE", "SINUS_FACE|BRAIN_HEAD",
            "BRAIN_HEAD|VASC_CAROTID", "VASC_CAROTID|BRAIN_HEAD",
            "BRAIN_HEAD|SPINE_CERVICAL", "SPINE_CERVICAL|BRAIN_HEAD",
            "CHEST|CARDIAC", "CARDIAC|CHEST",
            "CHEST|VASCULAR", "VASCULAR|CHEST",
            "CHEST|SPINE_THORACIC", "SPINE_THORACIC|CHEST",
            "CHEST|ABDOMEN_PELVIS", "ABDOMEN_PELVIS|CHEST",
            "ABDOMEN_PELVIS|SPINE_LUMBAR", "SPINE_LUMBAR|ABDOMEN_PELVIS",
            "ABDOMEN_PELVIS|SPINE_SACRUM", "SPINE_SACRUM|ABDOMEN_PELVIS",
            "SPINE_CERVICAL|SPINE_THORACIC", "SPINE_THORACIC|SPINE_CERVICAL",
            "SPINE_THORACIC|SPINE_LUMBAR", "SPINE_LUMBAR|SPINE_THORACIC",
            "SPINE_LUMBAR|SPINE_SACRUM", "SPINE_SACRUM|SPINE_LUMBAR",
            "SPINE_CERVICAL|SPINE_OTHER", "SPINE_OTHER|SPINE_CERVICAL",
            "SPINE_THORACIC|SPINE_OTHER", "SPINE_OTHER|SPINE_THORACIC",
            "SPINE_LUMBAR|SPINE_OTHER", "SPINE_OTHER|SPINE_LUMBAR",
            "SPINE_SACRUM|SPINE_OTHER", "SPINE_OTHER|SPINE_SACRUM",
            "ABDOMEN_PELVIS|OB_GYN", "OB_GYN|ABDOMEN_PELVIS",
            "ABDOMEN_PELVIS|VASCULAR", "VASCULAR|ABDOMEN_PELVIS",
            "NECK|VASCULAR", "VASCULAR|NECK",
            "NECK|VASC_CAROTID", "VASC_CAROTID|NECK",
            "NECK|SPINE_CERVICAL", "SPINE_CERVICAL|NECK",
            "VASCULAR|VASC_CAROTID", "VASC_CAROTID|VASCULAR",
            "VASCULAR|VASC_LE", "VASC_LE|VASCULAR",
            "MSK_SHOULDER|MSK_HUMERUS_ELBOW", "MSK_HUMERUS_ELBOW|MSK_SHOULDER",
            "MSK_SHOULDER|CHEST", "CHEST|MSK_SHOULDER",
            "MSK_HIP|MSK_FEMUR_THIGH", "MSK_FEMUR_THIGH|MSK_HIP",
            "MSK_FEMUR_THIGH|MSK_KNEE", "MSK_KNEE|MSK_FEMUR_THIGH",
            "MSK_KNEE|MSK_TIB_FIB", "MSK_TIB_FIB|MSK_KNEE",
            "MSK_TIB_FIB|MSK_ANKLE_FOOT", "MSK_ANKLE_FOOT|MSK_TIB_FIB",
            "MSK_FOREARM|MSK_WRIST_HAND", "MSK_WRIST_HAND|MSK_FOREARM",
            "MSK_HUMERUS_ELBOW|MSK_FOREARM", "MSK_FOREARM|MSK_HUMERUS_ELBOW",
            "MSK_HIP|SPINE_LUMBAR", "SPINE_LUMBAR|MSK_HIP",
            "MSK_HIP|SPINE_SACRUM", "SPINE_SACRUM|MSK_HIP",
            "MSK_HIP|MSK_LE_OTHER", "MSK_LE_OTHER|MSK_HIP",
            "MSK_SHOULDER|MSK_UE_OTHER", "MSK_UE_OTHER|MSK_SHOULDER",
            "MSK_WRIST_HAND|MSK_UE_OTHER", "MSK_UE_OTHER|MSK_WRIST_HAND",
            "MSK_HUMERUS_ELBOW|MSK_UE_OTHER", "MSK_UE_OTHER|MSK_HUMERUS_ELBOW",
            "MSK_FOREARM|MSK_UE_OTHER", "MSK_UE_OTHER|MSK_FOREARM",
            "MSK_KNEE|MSK_LE_OTHER", "MSK_LE_OTHER|MSK_KNEE",
            "MSK_FEMUR_THIGH|MSK_LE_OTHER", "MSK_LE_OTHER|MSK_FEMUR_THIGH",
            "MSK_TIB_FIB|MSK_LE_OTHER", "MSK_LE_OTHER|MSK_TIB_FIB",
            "MSK_ANKLE_FOOT|MSK_LE_OTHER", "MSK_LE_OTHER|MSK_ANKLE_FOOT"
    );

    private static final List<String> TEXT_FIELDS = Arrays.asList(
            "study_description", "description", "modality", "body_part", "body_part_examined",
            "procedure", "indication", "reason_for_exam", "accession_description"
    );

    private static volatile LogOddsModel MODEL = null;
    private static volatile boolean MODEL_LOAD_ATTEMPTED = false;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--predict-file".equals(args[0])) {
            getModel();
            Object parsed = Json.parse(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
            System.out.println(Json.stringify(predictPayload(asMap(parsed))));
            return;
        }

        if (args.length >= 1 && "--evaluate".equals(args[0])) {
            int folds = args.length >= 2 ? Integer.parseInt(args[1]) : 5;
            Evaluator.run(DATA_PATH, folds);
            return;
        }

        if (args.length >= 1 && "--show-errors".equals(args[0])) {
            int limit = args.length >= 2 ? Integer.parseInt(args[1]) : 30;
            Evaluator.showErrors(DATA_PATH, limit);
            return;
        }

        if (args.length == 3 && "--debug-pair".equals(args[0])) {
            Map<String, Object> cur = new LinkedHashMap<>(); cur.put("study_description", args[1]); cur.put("study_date", "2025-01-01");
            Map<String, Object> pri = new LinkedHashMap<>(); pri.put("study_description", args[2]); pri.put("study_date", "2024-01-01");
            Set<String> ct = tagSet(studyText(cur));
            Set<String> pt = tagSet(studyText(pri));
            System.out.println("curTags=" + ct);
            System.out.println("priTags=" + pt);
            System.out.println("strongRelevant=" + strongRelevantPair(cur, pri));
            System.out.println("strongIrrelevant=" + strongIrrelevantPair(cur, pri));
            System.out.println("features=" + pairFeatures(cur, pri));
            return;
        }

        getModel(); // Warm the model before accepting traffic.

        HttpServer server = HttpServer.create(new InetSocketAddress(HOST, PORT), 0);
        server.createContext("/health", new HealthHandler());
        server.createContext("/predict", new PredictHandler());
        server.setExecutor(Executors.newCachedThreadPool());
        LOG.info("server_started host=" + HOST + " port=" + PORT);
        server.start();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Map<String, List<String>> mapOfAliases(String[][] rows) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String[] row : rows) {
            List<String> values = new ArrayList<>();
            for (int i = 1; i < row.length; i++) {
                values.add(clean(row[i]));
            }
            out.put(row[0], values);
        }
        return out;
    }

    static class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            LogOddsModel model = getModel();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("model_loaded", model != null);
            body.put("trained_examples", model == null ? 0 : model.trainedExamples);
            sendJson(exchange, 200, body);
        }
    }

    static class PredictHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
            if (requestId == null || requestId.isBlank()) {
                requestId = Long.toUnsignedString(System.nanoTime(), 36);
            }
            long start = System.nanoTime();
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendJson(exchange, 405, Map.of("error", "method_not_allowed"));
                return;
            }
            try {
                String body = readAll(exchange.getRequestBody());
                if (body.isBlank()) {
                    LOG.warning("predict_bad_request id=" + requestId + " reason=empty_body");
                    sendJson(exchange, 400, Map.of("error", "empty_body"));
                    return;
                }
                Object parsed = Json.parse(body);
                if (!(parsed instanceof Map<?, ?>)) {
                    LOG.warning("predict_bad_request id=" + requestId + " reason=not_object");
                    sendJson(exchange, 400, Map.of("error", "expected_json_object"));
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) parsed;
                Map<String, Object> response = predictPayload(payload);
                long ms = (System.nanoTime() - start) / 1_000_000L;
                int caseCount = asList(payload.get("cases")).size();
                int predCount = asList(response.get("predictions")).size();
                LOG.info("predict_ok id=" + requestId + " cases=" + caseCount + " predictions=" + predCount + " elapsed_ms=" + ms);
                sendJson(exchange, 200, response);
            } catch (Json.JsonParseException badJson) {
                LOG.warning("predict_bad_request id=" + requestId + " reason=invalid_json");
                sendJson(exchange, 400, Map.of("error", "invalid_json"));
            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "predict_error id=" + requestId, ex);
                sendJson(exchange, 500, Map.of("error", "prediction_failed"));
            }
        }
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> predictPayload(Map<String, Object> payload) {
        List<Object> cases = asList(payload.get("cases"));
        LogOddsModel model = getModel();
        List<Object> predictions = new ArrayList<>();
        int priorCount = 0;

        for (Object caseObj : cases) {
            Map<String, Object> c = asMap(caseObj);
            String caseId = asString(c.get("case_id"));
            Map<String, Object> current = asMap(c.get("current_study"));
            for (Object priorObj : asList(c.get("prior_studies"))) {
                priorCount++;
                Map<String, Object> prior = asMap(priorObj);
                boolean pred;
                if (model != null) {
                    pred = model.predictPair(current, prior);
                } else {
                    pred = heuristicPredict(current, prior);
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("case_id", caseId);
                item.put("study_id", asString(prior.get("study_id")));
                item.put("predicted_is_relevant", pred);
                predictions.add(item);
            }
        }

        LOG.fine("predict_payload cases=" + cases.size() + " priors=" + priorCount + " predictions=" + predictions.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("predictions", predictions);
        return out;
    }

    private static synchronized LogOddsModel getModel() {
        if (MODEL_LOAD_ATTEMPTED) {
            return MODEL;
        }
        MODEL_LOAD_ATTEMPTED = true;
        try {
            List<Example> examples = loadTrainingExamples(DATA_PATH);
            double threshold = tuneThreshold(examples);
            MODEL = trainLogOdds(examples);
            MODEL.threshold = threshold;
            LOG.info("trained_model examples=" + MODEL.trainedExamples + " features=" + MODEL.weights.size()
                    + " threshold=" + String.format(Locale.US, "%.4f", MODEL.threshold));
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "model_training_failed; using deterministic fallback", ex);
            MODEL = null;
        }
        return MODEL;
    }

    /** Train on multiple inner splits, score the held-out half each time, then pick the
     *  single threshold that maximizes accuracy on the pooled out-of-fold scores. This is
     *  more stable than tuning on a single 20% holdout. */
    private static double tuneThreshold(List<Example> examples) {
        List<Double> pooledScores = new ArrayList<>();
        List<Boolean> pooledLabels = new ArrayList<>();
        String[] seeds = {"|innerA", "|innerB", "|innerC"};
        for (String salt : seeds) {
            List<Example> train = new ArrayList<>();
            List<Example> valid = new ArrayList<>();
            for (Example e : examples) {
                if (stableFold(e.caseId + salt, 5) == 0) valid.add(e);
                else train.add(e);
            }
            if (train.isEmpty() || valid.isEmpty()) continue;
            LogOddsModel m = trainLogOdds(train);
            for (Example e : valid) {
                pooledScores.add(m.scoreFeatures(pairFeatures(e.currentStudy, e.priorStudy)));
                pooledLabels.add(e.label);
            }
        }
        return pooledScores.isEmpty() ? 0.0 : bestThreshold(pooledScores, pooledLabels);
    }

    private static String clean(Object raw) {
        String text = raw == null ? "" : raw.toString();
        text = text.toLowerCase(Locale.ROOT).replace("&", " and ");
        // Split on slashes, underscores, and other separators so word-bounded matching works
        // on tokens like "ABD/PELVIS", "PA/LAT", "skull_to_thigh".
        text = text.replaceAll("[^a-z0-9+. -]+", " ");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private static String studyText(Map<String, Object> study) {
        StringBuilder sb = new StringBuilder();
        for (String field : TEXT_FIELDS) {
            Object value = study.get(field);
            if (value != null && !asString(value).isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(value);
            }
        }
        return sb.toString();
    }

    private static List<String> wordTokens(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = Pattern.compile("[a-z0-9]+").matcher(clean(text));
        while (matcher.find()) {
            String t = matcher.group();
            if (t.length() >= 2 && !STOPWORDS.contains(t)) {
                tokens.add(t);
            }
        }
        return tokens;
    }

    private static Set<String> tagSet(String text) {
        String s = clean(text);
        String padded = " " + s + " ";
        Set<String> tags = new HashSet<>();

        for (Map.Entry<String, List<String>> entry : MODALITY_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (matchAlias(padded, s, alias)) {
                    tags.add("MOD_" + entry.getKey());
                    break;
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : REGION_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (matchAlias(padded, s, alias)) {
                    tags.add("REG_" + entry.getKey());
                    break;
                }
            }
        }
        return tags;
    }

    private static boolean matchAlias(String padded, String s, String alias) {
        if (alias.isEmpty()) return false;
        if (matchAliasExact(padded, s, alias)) return true;
        // Tolerate simple English plurals so that "ribs", "lungs", "kidneys",
        // "ovaries" still match the singular alias (and vice versa).
        if (alias.length() >= 3) {
            if (matchAliasExact(padded, s, alias + "s")) return true;
            if (matchAliasExact(padded, s, alias + "es")) return true;
            if (alias.endsWith("y") && matchAliasExact(padded, s, alias.substring(0, alias.length() - 1) + "ies")) return true;
            if (alias.endsWith("s") && alias.length() >= 4 && matchAliasExact(padded, s, alias.substring(0, alias.length() - 1))) return true;
        }
        return false;
    }

    private static boolean matchAliasExact(String padded, String s, String alias) {
        return padded.contains(" " + alias + " ")
                || s.startsWith(alias + " ")
                || s.endsWith(" " + alias)
                || s.equals(alias);
    }

    private static String dateGapBucket(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
        try {
            LocalDate c = LocalDate.parse(asString(currentStudy.get("study_date")));
            LocalDate p = LocalDate.parse(asString(priorStudy.get("study_date")));
            long days = Math.abs(ChronoUnit.DAYS.between(c, p));
            if (days <= 14) return "AGE_0_14_DAYS";
            if (days <= 90) return "AGE_15_90_DAYS";
            if (days <= 365) return "AGE_91_365_DAYS";
            if (days <= 730) return "AGE_1_2_YEARS";
            if (days <= 1825) return "AGE_2_5_YEARS";
            if (days <= 3650) return "AGE_5_10_YEARS";
            return "AGE_OVER_10_YEARS";
        } catch (Exception ignored) {
            return "AGE_UNKNOWN";
        }
    }

    private static String laterality(String text) {
        String s = " " + clean(text) + " ";
        boolean lt = s.contains(" lt ") || s.contains(" left ") || s.contains(" lft ") || s.contains(" l ");
        boolean rt = s.contains(" rt ") || s.contains(" right ") || s.contains(" rgt ") || s.contains(" rght ") || s.contains(" r ");
        boolean bilat = s.contains(" bilat ") || s.contains(" bilateral ") || s.contains(" bi ") || s.contains(" both ")
                || s.contains(" bilat. ") || s.contains(" bilatl ");
        if (bilat) return "BILAT";
        if (lt && rt) return "BOTH";
        if (lt) return "LT";
        if (rt) return "RT";
        return "NONE";
    }

    private static boolean lateralityCompatible(String a, String b) {
        if ("NONE".equals(a) || "NONE".equals(b)) return true;
        if ("BILAT".equals(a) || "BILAT".equals(b) || "BOTH".equals(a) || "BOTH".equals(b)) return true;
        return a.equals(b);
    }

    private static boolean isProcedural(Set<String> tags, String text) {
        if (tags.contains("MOD_IR")) return true;
        String s = clean(text);
        return s.contains("biopsy") || s.contains(" bx ") || s.endsWith(" bx") || s.contains("drainage")
                || s.contains("aspiration") || s.contains("stereotactic") || s.contains("stereo bx")
                || s.contains("guided");
    }

    private static String normalizedDescriptionKey(Map<String, Object> study) {
        String s = clean(studyText(study));
        s = s.replace("/", " ");
        s = s.replace(",", " ");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    private static Set<String> pairFeatures(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
        String curText = studyText(currentStudy);
        String priorText = studyText(priorStudy);
        List<String> curWords = wordTokens(curText);
        List<String> priorWords = wordTokens(priorText);
        Set<String> curTags = tagSet(curText);
        Set<String> priorTags = tagSet(priorText);
        Set<String> sharedTags = intersection(curTags, priorTags);
        Set<String> curMods = filterPrefix(curTags, "MOD_");
        Set<String> priMods = filterPrefix(priorTags, "MOD_");
        Set<String> curRegs = filterPrefix(curTags, "REG_");
        Set<String> priRegs = filterPrefix(priorTags, "REG_");
        String curLat = laterality(curText);
        String priLat = laterality(priorText);

        Set<String> features = new HashSet<>();
        features.add("BIAS");
        features.add("DATE:" + dateGapBucket(currentStudy, priorStudy));
        for (String t : curTags) features.add("CURTAG:" + t);
        for (String t : priorTags) features.add("PRITAG:" + t);
        for (String t : sharedTags) features.add("SAMETAG:" + t);
        if (!curMods.isEmpty() && curMods.equals(priMods)) features.add("SAME_MODALITY_EXACT");
        if (!intersection(curMods, priMods).isEmpty()) features.add("SAME_MODALITY_ANY");
        if (!intersection(curRegs, priRegs).isEmpty()) features.add("SAME_REGION_ANY");
        if (!curRegs.isEmpty() && curRegs.equals(priRegs)) features.add("SAME_REGION_EXACT");
        if (!intersection(curMods, priMods).isEmpty() && !intersection(curRegs, priRegs).isEmpty()) {
            features.add("SAME_MOD_AND_REGION");
        }
        if (!intersection(curRegs, priRegs).isEmpty() && intersection(curMods, priMods).isEmpty()
                && !curMods.isEmpty() && !priMods.isEmpty()) {
            features.add("CROSS_MODALITY_SAME_REGION");
        }
        if (!intersection(curMods, priMods).isEmpty() && intersection(curRegs, priRegs).isEmpty()
                && !curRegs.isEmpty() && !priRegs.isEmpty()) {
            features.add("SAME_MODALITY_DIFF_REGION");
        }

        for (String cm : curMods) {
            for (String pm : priMods) {
                features.add("CROSSMOD:" + cm + ">" + pm);
            }
        }
        for (String cr : curRegs) {
            for (String pr : priRegs) {
                features.add("CROSSREG:" + cr + ">" + pr);
                String pairKey = cr.substring(4) + "|" + pr.substring(4);
                if (CROSS_REGION_PAIRS_RELATED.contains(pairKey)) {
                    features.add("RELATED_REGION_PAIR");
                }
            }
        }

        boolean curMsk = anySubregion(curRegs, "REG_MSK_");
        boolean priMsk = anySubregion(priRegs, "REG_MSK_");
        if (curMsk && priMsk) {
            Set<String> shared = new HashSet<>(curRegs); shared.retainAll(priRegs);
            if (anyStartsWith(shared, "REG_MSK_")) features.add("MSK_SAME_SUBREGION");
            else features.add("MSK_DIFF_SUBREGION");
        }

        features.add("CURLAT:" + curLat);
        features.add("PRILAT:" + priLat);
        if (curLat.equals(priLat)) features.add("SAME_LATERALITY");
        if (lateralityCompatible(curLat, priLat)) features.add("LAT_COMPATIBLE");
        if (("LT".equals(curLat) && "RT".equals(priLat)) || ("RT".equals(curLat) && "LT".equals(priLat))) {
            features.add("LATERALITY_OPPOSITE");
        }

        boolean curProc = isProcedural(curTags, curText);
        boolean priProc = isProcedural(priorTags, priorText);
        if (curProc) features.add("CUR_PROC");
        if (priProc) features.add("PRI_PROC");
        if (curProc && !priProc) features.add("CUR_PROC_PRI_DIAG");
        if (!curProc && priProc) features.add("CUR_DIAG_PRI_PROC");

        for (String token : firstN(curWords, 30)) features.add("CUR:" + token);
        for (String token : firstN(priorWords, 30)) features.add("PRI:" + token);
        Set<String> sharedTokens = intersection(new HashSet<>(curWords), new HashSet<>(priorWords));
        for (String token : sharedTokens) features.add("BOTH:" + token);
        if (sharedTokens.size() >= 2) features.add("SHARED_TOK_GE2");
        if (sharedTokens.size() >= 4) features.add("SHARED_TOK_GE4");
        for (String token : bigrams(firstN(curWords, 20))) features.add("CUR2:" + token);
        for (String token : bigrams(firstN(priorWords, 20))) features.add("PRI2:" + token);

        // Character n-grams of important words help when abbreviations differ (CNTRS / CNTRST).
        Set<String> curStems = stems(curWords);
        Set<String> priStems = stems(priorWords);
        Set<String> sharedStems = intersection(curStems, priStems);
        for (String stem : sharedStems) features.add("STEM:" + stem);
        if (sharedStems.size() >= 2) features.add("SHARED_STEM_GE2");
        if (sharedStems.size() >= 4) features.add("SHARED_STEM_GE4");

        List<String> importantCur = importantWords(curWords, 12);
        List<String> importantPrior = importantWords(priorWords, 12);
        for (String c : importantCur) {
            for (String p : importantPrior) {
                if (c.equals(p) || (c.length() >= 4 && p.length() >= 4 && (c.contains(p) || p.contains(c)))) {
                    features.add("PAIR:" + c + "|" + p);
                }
            }
        }

        if (curTags.contains("REG_BREAST") && priorTags.contains("REG_BREAST")) {
            features.add("CLINICAL:BREAST_FOLLOWUP");
        }
        if (curTags.contains("REG_ABDOMEN_PELVIS") && priorTags.contains("REG_ABDOMEN_PELVIS")
                && intersects(setOf("MOD_CT", "MOD_MR", "MOD_US"), union(curTags, priorTags))) {
            features.add("CLINICAL:ABD_PELVIS_CROSS_SECTIONAL");
        }
        if (curTags.contains("REG_CHEST") && priorTags.contains("REG_CHEST")
                && intersects(setOf("MOD_CT", "MOD_NM", "MOD_XR", "MOD_MR"), union(curTags, priorTags))) {
            features.add("CLINICAL:CHEST_PRIOR");
        }
        if (curTags.contains("REG_VASCULAR") && priorTags.contains("REG_VASCULAR")) {
            features.add("CLINICAL:VASCULAR_FOLLOWUP");
        }
        if (curTags.contains("REG_CARDIAC") && priorTags.contains("REG_CARDIAC")) {
            features.add("CLINICAL:CARDIAC_FOLLOWUP");
        }
        if (curTags.contains("REG_BRAIN_HEAD") && priorTags.contains("REG_BRAIN_HEAD")
                && intersects(setOf("MOD_CT", "MOD_MR"), union(curTags, priorTags))) {
            features.add("CLINICAL:NEURO_CROSS_SECTIONAL");
        }
        if (curTags.contains("REG_NECK") && priorTags.contains("REG_NECK")) {
            features.add("CLINICAL:NECK_FOLLOWUP");
        }
        boolean curSpine = anySubregion(curRegs, "REG_SPINE");
        boolean priSpine = anySubregion(priRegs, "REG_SPINE");
        Set<String> sharedSpine = new HashSet<>();
        for (String r : curRegs) if (r.startsWith("REG_SPINE") && priRegs.contains(r)) sharedSpine.add(r);
        if (curSpine && priSpine) features.add("CLINICAL:SPINE_BOTH");
        if (!sharedSpine.isEmpty()) features.add("CLINICAL:SPINE_SAME_SEGMENT");
        if (curSpine && priSpine && sharedSpine.isEmpty()) features.add("CLINICAL:SPINE_DIFF_SEGMENT");
        if (curTags.contains("REG_SINUS_FACE") && priorTags.contains("REG_SINUS_FACE")) {
            features.add("CLINICAL:SINUS_FACE_FOLLOWUP");
        }
        if (curTags.contains("REG_BONE_DENSITY") && priorTags.contains("REG_BONE_DENSITY")) {
            features.add("CLINICAL:BONE_DENSITY_FOLLOWUP");
        }
        if (curTags.contains("REG_OB_GYN") && priorTags.contains("REG_OB_GYN")) {
            features.add("CLINICAL:OB_GYN_FOLLOWUP");
        }
        boolean curWB = curTags.contains("MOD_WHOLEBODY_NM");
        boolean priWB = priorTags.contains("MOD_WHOLEBODY_NM");
        if (curWB || priWB) features.add("CLINICAL:WHOLEBODY_INVOLVED");
        if (curWB && priWB) features.add("CLINICAL:WHOLEBODY_BOTH");
        if (curWB && intersects(priorTags, setOf("MOD_CT", "MOD_MR"))) features.add("CLINICAL:WB_VS_CTMR");
        if (priWB && intersects(curTags, setOf("MOD_CT", "MOD_MR"))) features.add("CLINICAL:CTMR_VS_WB");
        if (curTags.contains("REG_VASC_CAROTID") && priorTags.contains("REG_VASC_CAROTID")) features.add("CLINICAL:CAROTID_FOLLOWUP");
        if (curTags.contains("REG_VASC_LE") && priorTags.contains("REG_VASC_LE")) features.add("CLINICAL:VASC_LE_FOLLOWUP");
        if (curTags.contains("REG_CARDIAC") && (priorTags.contains("REG_CHEST") || priorTags.contains("REG_VASCULAR"))) {
            features.add("CLINICAL:CARDIAC_VS_CHEST_VASC");
        }
        if (priorTags.contains("REG_CARDIAC") && (curTags.contains("REG_CHEST") || curTags.contains("REG_VASCULAR"))) {
            features.add("CLINICAL:CHEST_VASC_VS_CARDIAC");
        }

        if (normalizedDescriptionKey(currentStudy).equals(normalizedDescriptionKey(priorStudy))) {
            features.add("SAME_NORMALIZED_DESCRIPTION");
        }

        // Procedural follow-up: same region, prior is procedural (drainage, biopsy, etc.)
        // -- this captures cases like "CT abd/pel" with prior "drainage of collection".
        Set<String> sharedRegs = intersection(curRegs, priRegs);
        if (priProc && !sharedRegs.isEmpty()) features.add("PRI_PROC_SAME_REGION");
        if (curProc && !sharedRegs.isEmpty()) features.add("CUR_PROC_SAME_REGION");

        // Hand-written rule signals expressed as features so the model can learn how
        // much to trust them rather than enforcing them rigidly at inference time.
        if (strongRelevantPair(currentStudy, priorStudy)) features.add("OVR_REL");
        if (strongIrrelevantPair(currentStudy, priorStudy)) features.add("OVR_IRR");

        return features;
    }

    private static Set<String> filterPrefix(Set<String> values, String prefix) {
        Set<String> out = new HashSet<>();
        for (String v : values) if (v.startsWith(prefix)) out.add(v);
        return out;
    }

    private static boolean anySubregion(Set<String> tags, String prefix) {
        for (String t : tags) if (t.startsWith(prefix)) return true;
        return false;
    }

    private static List<String> importantWords(List<String> words, int limit) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 3 && !w.equals("contrast") && !w.equals("limited")) {
                out.add(w);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /** Coarse "stem" of each token (first 5 chars) plus a few common abbrev mappings.
     *  This catches near-duplicates like "cntrs" vs "cntrst" or "abdomen" vs "abd". */
    private static Set<String> stems(List<String> words) {
        Set<String> out = new HashSet<>();
        for (String w : words) {
            if (w.length() < 3) continue;
            String stem = w.length() <= 5 ? w : w.substring(0, 5);
            String mapped = STEM_ALIASES.getOrDefault(stem, stem);
            out.add(mapped);
        }
        return out;
    }

    private static final Map<String, String> STEM_ALIASES = stemAliasMap();
    private static Map<String, String> stemAliasMap() {
        Map<String, String> m = new HashMap<>();
        m.put("abdom", "abd");
        m.put("pelvi", "pel");
        m.put("cntrs", "cont");
        m.put("cntst", "cont");
        m.put("contr", "cont");
        m.put("cntrt", "cont");
        m.put("cntrt", "cont");
        m.put("ctrst", "cont");
        m.put("witho", "wo");
        m.put("withi", "wi");
        m.put("withr", "wi");
        m.put("ultra", "us");
        m.put("sonog", "us");
        m.put("magne", "mr");
        m.put("compu", "ct");
        m.put("xrayy", "xr");
        m.put("radio", "xr");
        m.put("brain", "brain");
        m.put("cereb", "brain");
        m.put("intra", "brain");
        m.put("crani", "brain");
        m.put("thora", "thora");
        m.put("thorx", "thora");
        m.put("cardi", "card");
        m.put("coron", "card");
        m.put("myoca", "card");
        m.put("artry", "vasc");
        m.put("arter", "vasc");
        m.put("venou", "vasc");
        m.put("kidne", "kid");
        m.put("renal", "kid");
        m.put("urete", "uret");
        m.put("urogr", "uret");
        m.put("blade", "blad");
        m.put("ovary", "ov");
        m.put("ovari", "ov");
        m.put("pregn", "ob");
        m.put("fetal", "ob");
        m.put("uteru", "ob");
        m.put("breas", "br");
        m.put("mammo", "br");
        m.put("mamm", "br");
        m.put("mamog", "br");
        return m;
    }

    private static List<String> bigrams(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            out.add(tokens.get(i) + "_" + tokens.get(i + 1));
        }
        return out;
    }

    private static <T> List<T> firstN(List<T> values, int n) {
        if (values.size() <= n) return values;
        return values.subList(0, n);
    }

    private static Set<String> intersection(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static boolean intersects(Set<String> a, Set<String> b) {
        for (String item : a) if (b.contains(item)) return true;
        return false;
    }

    private static boolean anyStartsWith(Set<String> values, String prefix) {
        for (String value : values) if (value.startsWith(prefix)) return true;
        return false;
    }

    private static int stableFold(String caseId, int folds) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(caseId.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 4; i++) {
                value = (value << 8) | (digest[i] & 0xffL);
            }
            return (int) (value % folds);
        } catch (Exception e) {
            return Math.floorMod(caseId.hashCode(), folds);
        }
    }

    private static double bestThreshold(List<Double> scores, List<Boolean> labels) {
        if (scores.isEmpty()) return 0.0;
        List<Double> candidates = new ArrayList<>(new LinkedHashSet<>(scores));
        Collections.sort(candidates);
        if (candidates.size() > 400) {
            List<Double> compact = new ArrayList<>();
            int step = Math.max(1, candidates.size() / 400);
            for (int i = 0; i < candidates.size(); i += step) compact.add(candidates.get(i));
            candidates = compact;
        }
        double bestT = 0.0;
        double bestAcc = -1.0;
        for (double t : candidates) {
            int correct = 0;
            for (int i = 0; i < scores.size(); i++) {
                if ((scores.get(i) >= t) == labels.get(i)) correct++;
            }
            double acc = (double) correct / scores.size();
            if (acc > bestAcc) {
                bestAcc = acc;
                bestT = t;
            }
        }
        return bestT;
    }

    /** L2-regularized logistic regression trained with SGD, warm-started from Naive-Bayes
     *  log-odds. Singletons are dropped to control variance, and class weights are softened
     *  to the square root of the inverse class frequency. */
    private static LogOddsModel trainLogOdds(List<Example> examples) {
        if (examples.isEmpty()) {
            return new LogOddsModel(new HashMap<>(), 0.0, 0.0, 0);
        }

        List<Set<String>> exampleFeatures = new ArrayList<>(examples.size());
        Map<String, Integer> docFreq = new HashMap<>();
        int posDocs = 0;
        for (Example e : examples) {
            Set<String> feats = pairFeatures(e.currentStudy, e.priorStudy);
            exampleFeatures.add(feats);
            if (e.label) posDocs++;
            for (String f : feats) docFreq.merge(f, 1, Integer::sum);
        }
        int negDocs = examples.size() - posDocs;
        if (posDocs == 0 || negDocs == 0) {
            return new LogOddsModel(new HashMap<>(), 0.0, 0.0, examples.size());
        }

        Set<String> kept = new HashSet<>();
        for (Map.Entry<String, Integer> e : docFreq.entrySet()) {
            if (e.getValue() >= 2) kept.add(e.getKey());
        }

        Map<String, Integer> posCounts = new HashMap<>();
        Map<String, Integer> negCounts = new HashMap<>();
        for (int i = 0; i < examples.size(); i++) {
            Map<String, Integer> target = examples.get(i).label ? posCounts : negCounts;
            for (String f : exampleFeatures.get(i)) {
                if (kept.contains(f)) target.merge(f, 1, Integer::sum);
            }
        }
        double alpha = 1.0;
        Map<String, Double> weights = new HashMap<>();
        for (String feat : kept) {
            int pos = posCounts.getOrDefault(feat, 0);
            int neg = negCounts.getOrDefault(feat, 0);
            double posRate = (pos + alpha) / (posDocs + 2.0 * alpha);
            double negRate = (neg + alpha) / (negDocs + 2.0 * alpha);
            weights.put(feat, 0.5 * Math.log(posRate / negRate));
        }
        double bias = Math.log((posDocs + 1.0) / (negDocs + 1.0));

        double posWeight = 1.0;
        double negWeight = 1.0;

        int epochs = 40;
        double lr0 = 0.2;
        double l2 = 3e-4;
        long seed = 1234567L;
        int[] order = new int[examples.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;

        for (int epoch = 0; epoch < epochs; epoch++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long s = seed;
            for (int i = order.length - 1; i > 0; i--) {
                s = s * 6364136223846793005L + 1442695040888963407L;
                int j = (int) Math.floorMod(s >>> 33, i + 1);
                int tmp = order[i]; order[i] = order[j]; order[j] = tmp;
            }
            double lr = lr0 / (1.0 + epoch * 0.5);

            for (int idx : order) {
                Set<String> feats = exampleFeatures.get(idx);
                double z = bias;
                for (String f : feats) {
                    Double w = weights.get(f);
                    if (w != null) z += w;
                }
                double p = 1.0 / (1.0 + Math.exp(-z));
                double y = examples.get(idx).label ? 1.0 : 0.0;
                double w = examples.get(idx).label ? posWeight : negWeight;
                double grad = w * (p - y);

                bias -= lr * grad;
                for (String f : feats) {
                    Double cur = weights.get(f);
                    if (cur == null) continue;
                    double updated = cur - lr * (grad + l2 * cur);
                    if (updated > 6.0) updated = 6.0;
                    if (updated < -6.0) updated = -6.0;
                    weights.put(f, updated);
                }
            }
        }

        return new LogOddsModel(weights, bias, 0.0, examples.size());
    }

    private static boolean strongRelevantPair(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
        String curDesc = asString(currentStudy.get("study_description"));
        String priDesc = asString(priorStudy.get("study_description"));
        String curLat = laterality(curDesc);
        String priLat = laterality(priDesc);

        String curNorm = normalizedDescriptionKey(currentStudy);
        String priNorm = normalizedDescriptionKey(priorStudy);
        if (!curNorm.isBlank() && curNorm.equals(priNorm) && lateralityCompatible(curLat, priLat)) {
            return true;
        }

        Set<String> curTags = tagSet(studyText(currentStudy));
        Set<String> priorTags = tagSet(studyText(priorStudy));
        Set<String> curMods = filterPrefix(curTags, "MOD_");
        Set<String> priMods = filterPrefix(priorTags, "MOD_");
        Set<String> curRegs = filterPrefix(curTags, "REG_");
        Set<String> priRegs = filterPrefix(priorTags, "REG_");
        Set<String> sharedMods = intersection(curMods, priMods);
        Set<String> sharedRegs = intersection(curRegs, priRegs);

        boolean curMsk = anySubregion(curRegs, "REG_MSK_");
        boolean priMsk = anySubregion(priRegs, "REG_MSK_");
        boolean sharedMsk = anyStartsWith(sharedRegs, "REG_MSK_");
        boolean curProc = isProcedural(curTags, studyText(currentStudy));
        boolean priProc = isProcedural(priorTags, studyText(priorStudy));
        boolean procMismatch = curProc != priProc;

        boolean curSpine = anySubregion(curRegs, "REG_SPINE");
        boolean priSpine = anySubregion(priRegs, "REG_SPINE");
        Set<String> sharedSpine = new HashSet<>();
        for (String r : curRegs) if (r.startsWith("REG_SPINE") && priRegs.contains(r)) sharedSpine.add(r);

        // Same modality + same exact non-MSK region (stricter than "any shared region").
        if (!sharedMods.isEmpty() && !sharedRegs.isEmpty()) {
            if (curMsk && priMsk && !sharedMsk) return false; // different MSK sub-region
            if (sharedMsk && !lateralityCompatible(curLat, priLat)) return false;
            if (curSpine && priSpine && sharedSpine.isEmpty()) return false; // different spine segments
            if (sharedRegs.contains("REG_BREAST") && !lateralityCompatible(curLat, priLat)) return false;
            return true;
        }

        if (curMods.contains("MOD_DXA") && priMods.contains("MOD_DXA")) return true;
        if (curTags.contains("REG_BONE_DENSITY") && priorTags.contains("REG_BONE_DENSITY")) return true;
        if (sharedRegs.contains("REG_BREAST") && lateralityCompatible(curLat, priLat) && !procMismatch) return true;

        // Cross-modality same anatomy: only override when BOTH sides are "imaging-rich" modalities.
        Set<String> abdMods = setOf("MOD_CT", "MOD_MR", "MOD_US");
        Set<String> brainMods = setOf("MOD_CT", "MOD_MR");
        Set<String> chestMods = setOf("MOD_CT", "MOD_MR", "MOD_NM", "MOD_XR");
        Set<String> spineMods = setOf("MOD_CT", "MOD_MR", "MOD_XR");
        Set<String> neckMods = setOf("MOD_CT", "MOD_MR", "MOD_US");
        Set<String> obMods = setOf("MOD_US", "MOD_MR", "MOD_CT");
        Set<String> cardiacMods = setOf("MOD_CT", "MOD_MR", "MOD_NM", "MOD_US");

        if (sharedRegs.contains("REG_BRAIN_HEAD")
                && intersects(curMods, brainMods) && intersects(priMods, brainMods)) return true;
        if (sharedRegs.contains("REG_SINUS_FACE")
                && intersects(curMods, brainMods) && intersects(priMods, brainMods)) return true;
        if (sharedRegs.contains("REG_ABDOMEN_PELVIS")
                && intersects(curMods, abdMods) && intersects(priMods, abdMods)) return true;
        if (sharedRegs.contains("REG_CHEST")
                && intersects(curMods, chestMods) && intersects(priMods, chestMods)) return true;
        if (sharedRegs.contains("REG_NECK")
                && intersects(curMods, neckMods) && intersects(priMods, neckMods)) return true;
        if (!sharedSpine.isEmpty()
                && intersects(curMods, spineMods) && intersects(priMods, spineMods)) return true;
        if (sharedRegs.contains("REG_OB_GYN")
                && intersects(curMods, obMods) && intersects(priMods, obMods)) return true;
        if (sharedRegs.contains("REG_CARDIAC")
                && intersects(curMods, cardiacMods) && intersects(priMods, cardiacMods)) return true;
        if (sharedMsk && (curMods.contains("MOD_MR") || curMods.contains("MOD_CT"))
                && (priMods.contains("MOD_MR") || priMods.contains("MOD_CT"))
                && lateralityCompatible(curLat, priLat)) return true;

        Set<String> majorOncology = setOf(
                "REG_CHEST", "REG_ABDOMEN_PELVIS", "REG_BRAIN_HEAD", "REG_NECK",
                "REG_BREAST"
        );
        Set<String> ctMr = setOf("MOD_CT", "MOD_MR");
        boolean curWholeBody = curMods.contains("MOD_WHOLEBODY_NM");
        boolean priWholeBody = priMods.contains("MOD_WHOLEBODY_NM");
        if (curWholeBody && intersects(priMods, ctMr) && intersects(priRegs, majorOncology)) return true;
        if (priWholeBody && intersects(curMods, ctMr) && intersects(curRegs, majorOncology)) return true;
        if (curWholeBody && priWholeBody) return true;

        return false;
    }

    /** Hard "definitely not relevant" override. Be conservative. */
    private static boolean strongIrrelevantPair(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
        Set<String> curTags = tagSet(studyText(currentStudy));
        Set<String> priorTags = tagSet(studyText(priorStudy));
        Set<String> curMods = filterPrefix(curTags, "MOD_");
        Set<String> priMods = filterPrefix(priorTags, "MOD_");
        Set<String> curRegs = filterPrefix(curTags, "REG_");
        Set<String> priRegs = filterPrefix(priorTags, "REG_");

        boolean curMsk = anySubregion(curRegs, "REG_MSK_");
        boolean priMsk = anySubregion(priRegs, "REG_MSK_");
        boolean sharedMsk = anyStartsWith(intersection(curRegs, priRegs), "REG_MSK_");

        if (curMsk && priMsk && !sharedMsk
                && (curMods.contains("MOD_XR") || curMods.contains("MOD_MR"))
                && (priMods.contains("MOD_XR") || priMods.contains("MOD_MR"))) {
            // e.g. XR wrist vs XR knee -- different MSK sub-regions
            return true;
        }
        if (curTags.contains("REG_BONE_DENSITY") && !priorTags.contains("REG_BONE_DENSITY")
                && !anySubregion(priRegs, "REG_MSK_") && !anySubregion(priRegs, "REG_SPINE")
                && !priorTags.contains("REG_BREAST")) {
            return true;
        }
        return false;
    }

    private static boolean heuristicPredict(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
        return strongRelevantPair(currentStudy, priorStudy);
    }

    private static List<Example> loadTrainingExamples(String path) throws IOException {
        String json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        Object parsed = Json.parse(json);
        Map<String, Object> publicData = asMap(parsed);

        Map<String, Boolean> truth = new HashMap<>();
        for (Object truthObj : asList(publicData.get("truth"))) {
            Map<String, Object> t = asMap(truthObj);
            truth.put(asString(t.get("case_id")) + "|" + asString(t.get("study_id")), asBoolean(t.get("is_relevant_to_current")));
        }

        List<Example> examples = new ArrayList<>();
        for (Object caseObj : asList(publicData.get("cases"))) {
            Map<String, Object> c = asMap(caseObj);
            String caseId = asString(c.get("case_id"));
            Map<String, Object> current = asMap(c.get("current_study"));
            for (Object priorObj : asList(c.get("prior_studies"))) {
                Map<String, Object> prior = asMap(priorObj);
                String key = caseId + "|" + asString(prior.get("study_id"));
                if (truth.containsKey(key)) {
                    examples.add(new Example(caseId, current, prior, truth.get(key)));
                }
            }
        }
        return examples;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?>) return (Map<String, Object>) value;
        return Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List<?>) return (List<Object>) value;
        return Collections.emptyList();
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(asString(value));
    }

    static class Example {
        final String caseId;
        final Map<String, Object> currentStudy;
        final Map<String, Object> priorStudy;
        final boolean label;

        Example(String caseId, Map<String, Object> currentStudy, Map<String, Object> priorStudy, boolean label) {
            this.caseId = caseId;
            this.currentStudy = currentStudy;
            this.priorStudy = priorStudy;
            this.label = label;
        }
    }

    static class LogOddsModel {
        final Map<String, Double> weights;
        final double bias;
        double threshold;
        final int trainedExamples;

        LogOddsModel(Map<String, Double> weights, double bias, double threshold, int trainedExamples) {
            this.weights = weights;
            this.bias = bias;
            this.threshold = threshold;
            this.trainedExamples = trainedExamples;
        }

        double scoreFeatures(Set<String> features) {
            double sum = bias;
            for (String f : features) sum += weights.getOrDefault(f, 0.0);
            return sum;
        }

        double probabilityFeatures(Set<String> features) {
            double z = scoreFeatures(features);
            if (z >= 36) return 1.0;
            if (z <= -36) return 0.0;
            return 1.0 / (1.0 + Math.exp(-z));
        }

        boolean predictPair(Map<String, Object> currentStudy, Map<String, Object> priorStudy) {
            return scoreFeatures(pairFeatures(currentStudy, priorStudy)) >= threshold;
        }
    }

    /** Grouped K-fold cross validation harness. Run with `--evaluate [folds]`. */
    static class Evaluator {
        static void run(String dataPath, int folds) throws IOException {
            List<Example> examples = loadTrainingExamples(dataPath);
            LOG.info("evaluator_loaded examples=" + examples.size() + " folds=" + folds);

            int total = examples.size();
            int positives = 0;
            for (Example e : examples) if (e.label) positives++;
            int negatives = total - positives;
            System.out.println("examples=" + total + " positives=" + positives + " negatives=" + negatives
                    + " base_rate_positive=" + String.format(Locale.US, "%.4f", positives / (double) total));
            System.out.println("baseline_always_false_accuracy=" + String.format(Locale.US, "%.4f", negatives / (double) total));
            System.out.println("baseline_always_true_accuracy=" + String.format(Locale.US, "%.4f", positives / (double) total));

            int strongCorrect = 0;
            for (Example e : examples) {
                boolean pred = strongRelevantPair(e.currentStudy, e.priorStudy);
                if (pred == e.label) strongCorrect++;
            }
            System.out.println("baseline_strong_overrides_only_accuracy="
                    + String.format(Locale.US, "%.4f", strongCorrect / (double) total));

            List<List<Example>> bins = new ArrayList<>();
            for (int i = 0; i < folds; i++) bins.add(new ArrayList<>());
            for (Example e : examples) bins.get(stableFold(e.caseId, folds)).add(e);

            int totalCorrect = 0;
            int totalSeen = 0;
            int totalTp = 0;
            int totalFp = 0;
            int totalFn = 0;
            int totalTn = 0;

            for (int fold = 0; fold < folds; fold++) {
                List<Example> test = bins.get(fold);
                List<Example> train = new ArrayList<>();
                for (int j = 0; j < folds; j++) if (j != fold) train.addAll(bins.get(j));

                double threshold = tuneThreshold(train);
                LogOddsModel foldModel = trainLogOdds(train);
                foldModel.threshold = threshold;

                int correct = 0;
                int tp = 0, fp = 0, fn = 0, tn = 0;
                for (Example e : test) {
                    boolean pred = foldModel.predictPair(e.currentStudy, e.priorStudy);
                    if (pred == e.label) correct++;
                    if (pred && e.label) tp++;
                    else if (pred && !e.label) fp++;
                    else if (!pred && e.label) fn++;
                    else tn++;
                }
                System.out.println("fold=" + fold + " size=" + test.size()
                        + " threshold=" + String.format(Locale.US, "%.4f", threshold)
                        + " accuracy=" + String.format(Locale.US, "%.4f", correct / (double) Math.max(1, test.size()))
                        + " tp=" + tp + " fp=" + fp + " fn=" + fn + " tn=" + tn);
                totalCorrect += correct;
                totalSeen += test.size();
                totalTp += tp; totalFp += fp; totalFn += fn; totalTn += tn;
            }

            double accuracy = totalSeen == 0 ? 0.0 : totalCorrect / (double) totalSeen;
            double precision = (totalTp + totalFp) == 0 ? 0.0 : totalTp / (double) (totalTp + totalFp);
            double recall = (totalTp + totalFn) == 0 ? 0.0 : totalTp / (double) (totalTp + totalFn);
            double f1 = (precision + recall) == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            System.out.println("--- summary ---");
            System.out.println("cv_accuracy=" + String.format(Locale.US, "%.4f", accuracy)
                    + " precision=" + String.format(Locale.US, "%.4f", precision)
                    + " recall=" + String.format(Locale.US, "%.4f", recall)
                    + " f1=" + String.format(Locale.US, "%.4f", f1)
                    + " tp=" + totalTp + " fp=" + totalFp + " fn=" + totalFn + " tn=" + totalTn);
        }

        static void showErrors(String dataPath, int limit) throws IOException {
            List<Example> examples = loadTrainingExamples(dataPath);
            double threshold = tuneThreshold(examples);
            LogOddsModel model = trainLogOdds(examples);
            model.threshold = threshold;
            int fpShown = 0, fnShown = 0;
            for (Example e : examples) {
                if (stableFold(e.caseId, 5) != 0) continue; // only test on held-out fold
                boolean pred = model.predictPair(e.currentStudy, e.priorStudy);
                boolean overrideFalse = strongIrrelevantPair(e.currentStudy, e.priorStudy);
                boolean overrideTrue = !overrideFalse && strongRelevantPair(e.currentStudy, e.priorStudy);
                String override = overrideFalse ? "FALSE" : (overrideTrue ? "TRUE" : "none");
                if (pred == e.label) continue;
                if (pred && !e.label && fpShown < limit) {
                    System.out.println("FP cur=" + asString(e.currentStudy.get("study_description"))
                            + " | prior=" + asString(e.priorStudy.get("study_description"))
                            + " | override=" + override
                            + " | score=" + String.format(Locale.US, "%.3f", model.scoreFeatures(pairFeatures(e.currentStudy, e.priorStudy))));
                    fpShown++;
                } else if (!pred && e.label && fnShown < limit) {
                    System.out.println("FN cur=" + asString(e.currentStudy.get("study_description"))
                            + " | prior=" + asString(e.priorStudy.get("study_description"))
                            + " | score=" + String.format(Locale.US, "%.3f", model.scoreFeatures(pairFeatures(e.currentStudy, e.priorStudy))));
                    fnShown++;
                }
                if (fpShown >= limit && fnShown >= limit) break;
            }
        }
    }

    /** Small JSON parser/writer so the service can be built with javac only. */
    static class Json {
        static Object parse(String text) {
            Parser p = new Parser(text);
            Object value = p.parseValue();
            p.skipWhitespace();
            if (!p.isEnd()) throw new JsonParseException("Trailing data at position " + p.pos);
            return value;
        }

        static String stringify(Object value) {
            StringBuilder sb = new StringBuilder();
            writeJson(sb, value);
            return sb.toString();
        }

        private static void writeJson(StringBuilder sb, Object value) {
            if (value == null) {
                sb.append("null");
            } else if (value instanceof String s) {
                writeString(sb, s);
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value);
            } else if (value instanceof Map<?, ?> map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    writeJson(sb, e.getValue());
                }
                sb.append('}');
            } else if (value instanceof Iterable<?> items) {
                sb.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) sb.append(',');
                    first = false;
                    writeJson(sb, item);
                }
                sb.append(']');
            } else {
                writeString(sb, value.toString());
            }
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                    }
                }
            }
            sb.append('"');
        }

        static class JsonParseException extends RuntimeException {
            JsonParseException(String message) { super(message); }
        }

        static class Parser {
            final String text;
            int pos = 0;

            Parser(String text) { this.text = text; }

            boolean isEnd() { return pos >= text.length(); }

            void skipWhitespace() {
                while (!isEnd()) {
                    char c = text.charAt(pos);
                    if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                    else break;
                }
            }

            Object parseValue() {
                skipWhitespace();
                if (isEnd()) throw new JsonParseException("Unexpected end of JSON");
                char c = text.charAt(pos);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't' -> parseLiteral("true", Boolean.TRUE);
                    case 'f' -> parseLiteral("false", Boolean.FALSE);
                    case 'n' -> parseLiteral("null", null);
                    default -> {
                        if (c == '-' || Character.isDigit(c)) yield parseNumber();
                        throw new JsonParseException("Unexpected character '" + c + "' at position " + pos);
                    }
                };
            }

            Object parseLiteral(String literal, Object value) {
                if (!text.startsWith(literal, pos)) throw new JsonParseException("Expected " + literal + " at position " + pos);
                pos += literal.length();
                return value;
            }

            Map<String, Object> parseObject() {
                expect('{');
                Map<String, Object> out = new LinkedHashMap<>();
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return out;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    out.put(key, value);
                    skipWhitespace();
                    if (peek('}')) {
                        pos++;
                        return out;
                    }
                    expect(',');
                }
            }

            List<Object> parseArray() {
                expect('[');
                List<Object> out = new ArrayList<>();
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return out;
                }
                while (true) {
                    out.add(parseValue());
                    skipWhitespace();
                    if (peek(']')) {
                        pos++;
                        return out;
                    }
                    expect(',');
                }
            }

            String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (!isEnd()) {
                    char c = text.charAt(pos++);
                    if (c == '"') return sb.toString();
                    if (c == '\\') {
                        if (isEnd()) throw new JsonParseException("Bad escape at end of input");
                        char e = text.charAt(pos++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (pos + 4 > text.length()) throw new JsonParseException("Short unicode escape at position " + pos);
                                String hex = text.substring(pos, pos + 4);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException nfe) {
                                    throw new JsonParseException("Bad unicode escape at position " + pos);
                                }
                                pos += 4;
                            }
                            default -> throw new JsonParseException("Bad escape character '" + e + "' at position " + pos);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                throw new JsonParseException("Unterminated string");
            }

            Number parseNumber() {
                int start = pos;
                if (peek('-')) pos++;
                while (!isEnd() && Character.isDigit(text.charAt(pos))) pos++;
                boolean isDouble = false;
                if (!isEnd() && text.charAt(pos) == '.') {
                    isDouble = true;
                    pos++;
                    while (!isEnd() && Character.isDigit(text.charAt(pos))) pos++;
                }
                if (!isEnd() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                    isDouble = true;
                    pos++;
                    if (!isEnd() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                    while (!isEnd() && Character.isDigit(text.charAt(pos))) pos++;
                }
                String n = text.substring(start, pos);
                try {
                    return isDouble ? Double.parseDouble(n) : Long.parseLong(n);
                } catch (NumberFormatException nfe) {
                    throw new JsonParseException("Bad number at position " + start);
                }
            }

            boolean peek(char c) {
                return !isEnd() && text.charAt(pos) == c;
            }

            void expect(char c) {
                if (isEnd() || text.charAt(pos) != c) {
                    throw new JsonParseException("Expected '" + c + "' at position " + pos);
                }
                pos++;
            }
        }
    }
}
