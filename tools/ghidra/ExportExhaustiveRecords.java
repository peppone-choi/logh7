import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class ExportExhaustiveRecords extends GhidraScript {
    private static final long PARSER = 0x004B8B00L;
    private static final long DISPATCHER = 0x004BA2B0L;
    private static final long OUTBOUND = 0x004B78A0L;
    private static final long SELECTED_PLANET_BUILDER = 0x004D3BD0L;
    private static final long SELECTED_PLANET_RENDERER = 0x004D68D0L;
    private static final int EXPECTED_STREAM_CONTRACTS = 410;
    private static final int EXPECTED_RECORD_FAMILIES = 166;
    private static final int EXPECTED_FAMILY_FIELDS = 230;
    private static final int EXPECTED_PARSER_CODES = 167;
    private static final int EXPECTED_DISPATCHER_CODES = 162;
    private static final int EXPECTED_DESTINATIONS = 347;
    private static final int EXPECTED_HELPERS = 280;
    private static final int EXPECTED_PROTOCOL_LABELS = 545;
    private static final Pattern STREAM_PATTERN = Pattern.compile(
        "^\\[(Input|Output)_(.+?)::(input_from_stream|get_length|output_to_stream)\\]\\s+" +
        "(.+?)_size\\[%d\\]\\s+is over than\\s+([0-9]+|%d)\\.?$"
    );

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(bytes)) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static String hex(long value, int width) {
        return String.format(Locale.ROOT, "0x%0" + width + "X", value);
    }

    private static JsonObject readJson(File path) throws Exception {
        return JsonParser.parseString(Files.readString(path.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Map<String, Object> unknownPlain() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownId() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("name", null);
        result.put("fields", new ArrayList<>());
        result.put("widthBits", null);
        result.put("signedness", "UNKNOWN");
        result.put("uniquenessScope", "UNKNOWN");
        result.put("comparisonFunctions", new ArrayList<>());
        result.put("nullSemantics", "UNKNOWN");
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownRelation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("edges", new ArrayList<>());
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownOperation() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("operations", new ArrayList<>());
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownProjection() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("protocolKeys", new ArrayList<>());
        result.put("fieldKeys", new ArrayList<>());
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownCache() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("writers", new ArrayList<>());
        result.put("readers", new ArrayList<>());
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> unknownRenderer() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("consumers", new ArrayList<>());
        result.put("evidence", List.of("ghidra:record-surface:unjoined"));
        return result;
    }

    private static Map<String, Object> requiredImplementation() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String target : List.of(
            "CONTRACT", "SERVER", "LEGACY_GATEWAY", "NEW_CLIENT", "DATABASE",
            "CONTENT_ADMIN", "QA", "INDEPENDENT_REVIEW"
        )) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", "REQUIRED");
            item.put("reason", null);
            item.put("evidence", List.of("goal:implementation-layer:" + target));
            result.put(target, item);
        }
        return result;
    }

    private static Map<String, Object> baseRow(
        String candidateId, String rowKind, String entityType, String recordType,
        String name, String provenance, String reachability, String recovery,
        String firstMissingBoundary, List<Map<String, Object>> cardinality,
        List<String> sourceCandidateIds
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateId", candidateId);
        result.put("rowKind", rowKind);
        result.put("entityType", entityType);
        if (recordType != null) result.put("recordType", recordType);
        result.put("name", name);
        result.put("stateBearing", !rowKind.equals("RECORD_TYPE"));
        result.put("provenance", provenance);
        result.put("reachability", reachability);
        result.put("reachabilityEvidence", List.of("ghidra:record-surface:unjoined"));
        result.put("recoveryDisposition", recovery);
        result.put("idNamespace", unknownId());
        Map<String, Object> relations = new LinkedHashMap<>();
        for (String relation : List.of("parent", "owner", "faction", "location", "visibility")) {
            relations.put(relation, unknownRelation());
        }
        result.put("relations", relations);
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        for (String phase : List.of(
            "definition", "create", "select", "query", "update", "transfer", "destroy", "terminal"
        )) lifecycle.put(phase, unknownOperation());
        result.put("lifecycle", lifecycle);
        Map<String, Object> projections = new LinkedHashMap<>();
        for (String projection : List.of("static", "dynamic", "notification")) {
            projections.put(projection, unknownProjection());
        }
        result.put("wireProjections", projections);
        Map<String, Object> representation = new LinkedHashMap<>();
        representation.put("cache", unknownCache());
        representation.put("renderer", unknownRenderer());
        result.put("clientRepresentation", representation);
        result.put("authority", unknownPlain());
        result.put("persistence", unknownPlain());
        result.put("reconnectReplay", unknownPlain());
        result.put("implementationDisposition", requiredImplementation());
        result.put("catalogCardinality", cardinality);
        result.put("firstMissingBoundary", firstMissingBoundary);
        result.put("evidence", List.of("ghidra:record-surface:" + candidateId));
        result.put("sourceCandidateIds", sourceCandidateIds);
        return result;
    }

    private static String entityTypeForRecord(String recordType) {
        String value = recordType.toLowerCase(Locale.ROOT);
        if (value.contains("character")) return "CHARACTER";
        if (value.contains("cardcommand")) return "CARD_COMMAND";
        if (value.contains("card")) return "AUTHORITY_CARD";
        if (value.contains("institution")) return "INSTITUTION";
        if (value.contains("tacticsgrid")) return "TACTICS_GRID";
        if (value.contains("gridtype")) return "GRID_TYPE";
        if (value.contains("grid")) return "GRID_CELL";
        if (value.contains("outfit")) return "OUTFIT";
        if (value.contains("corps")) return "CORPS";
        if (value.contains("unitship")) return value.contains("static") ? "SHIP_TEMPLATE" : "SHIP_UNIT_INSTANCE";
        if (value.contains("unittroop")) return value.contains("static") ? "TROOP_TEMPLATE" : "TROOP_UNIT";
        if (value.contains("fighter")) return "FIGHTER_TEMPLATE";
        if (value.contains("warehouse")) return "WAREHOUSE";
        if (value.contains("package")) return "PACKAGE";
        if (value.contains("messenger")) return "MESSENGER";
        if (value.contains("mailaddress")) return "MAIL_ADDRESS";
        if (value.contains("mail")) return "MAIL";
        if (value.contains("ranking")) return "RANKING";
        if (value.contains("rank")) return "RANK";
        if (value.contains("strategy")) return "STRATEGIC_MISSION";
        if (value.contains("base")) return "BASE";
        if (value.contains("unit")) return "UNIT";
        if (value.contains("parentage")) return "CHARACTER_PARENTAGE";
        if (value.contains("account")) return "ACCOUNT";
        return "EVENT_RECORD";
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += Math.max(1, needle.length());
        }
        return count;
    }

    private static Map<String, Object> catalogClaim(JsonObject claim) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateId", claim.get("candidateId").getAsString());
        result.put("entityType", claim.get("entityType").getAsString());
        result.put("sourceId", claim.get("sourceId").getAsString());
        result.put("status", claim.get("status").getAsString());
        result.put(
            "count",
            claim.get("count").isJsonNull() ? null : claim.get("count").getAsLong()
        );
        result.put("membershipStatus", claim.get("membershipStatus").getAsString());
        List<String> evidence = new ArrayList<>();
        for (JsonElement item : claim.getAsJsonArray("evidence")) evidence.add(item.getAsString());
        result.put("evidence", evidence);
        if (claim.has("manualMembers")) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (JsonElement memberElement : claim.getAsJsonArray("manualMembers")) {
                JsonObject member = memberElement.getAsJsonObject();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("term", member.get("term").getAsString());
                item.put("pdfPage", member.get("pdfPage").getAsLong());
                members.add(item);
            }
            result.put("manualMembers", members);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void bindManualMembers(
        Map<String, Object> claim,
        List<String> pageTexts,
        String manualPdfSha,
        String manualPageXmlSha
    ) {
        Object rawMembers = claim.get("manualMembers");
        if (!"ORIGINAL_MANUAL".equals(claim.get("status"))) {
            if (rawMembers != null) throw new IllegalStateException("non-manual claim has manual members");
            return;
        }
        if (!(rawMembers instanceof List<?>)) {
            throw new IllegalStateException("original manual cardinality lacks members");
        }
        List<Map<String, Object>> members = (List<Map<String, Object>>)rawMembers;
        long count = ((Number)claim.get("count")).longValue();
        if (count != members.size()) {
            throw new IllegalStateException("original manual count differs from member count");
        }
        List<Map<String, Object>> boundMembers = new ArrayList<>();
        List<String> evidence = new ArrayList<>((List<String>)claim.get("evidence"));
        for (Map<String, Object> member : members) {
            String term = String.valueOf(member.get("term")).replaceAll("\\s+", "");
            int page = ((Number)member.get("pdfPage")).intValue();
            if (page < 1 || page > pageTexts.size() || !pageTexts.get(page - 1).contains(term)) {
                throw new IllegalStateException("manual cardinality member page anchor absent: " + term);
            }
            Map<String, Object> bound = new LinkedHashMap<>();
            bound.put("term", term);
            bound.put("pdfPage", page);
            bound.put("pdfSha256", manualPdfSha);
            bound.put("pageXmlSha256", manualPageXmlSha);
            boundMembers.add(bound);
            evidence.add("manual-pdf:" + manualPdfSha + ":page:" + page + ":term:" + term);
            evidence.add("manual-page-xml:" + manualPageXmlSha + ":page:" + page + ":term:" + term);
        }
        claim.remove("manualMembers");
        claim.put("members", boundMembers);
        claim.put("evidence", evidence);
    }

    private static List<String> manualPageTexts(File pageXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(pageXml);
        NodeList objects = document.getElementsByTagName("OBJECT");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < objects.getLength(); index++) {
            Element object = (Element)objects.item(index);
            NodeList words = object.getElementsByTagName("WORD");
            StringBuilder text = new StringBuilder();
            for (int word = 0; word < words.getLength(); word++) {
                text.append(words.item(word).getTextContent());
            }
            result.add(text.toString().replaceAll("\\s+", ""));
        }
        return result;
    }

    private List<Map<String, Object>> scanStreamFields(JsonObject protocolRaw) {
        Map<String, JsonObject> protocolByAddress = new LinkedHashMap<>();
        for (JsonElement element : protocolRaw.getAsJsonArray("streamContracts")) {
            JsonObject item = element.getAsJsonObject();
            protocolByAddress.put(item.get("address").getAsString().toUpperCase(Locale.ROOT), item);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Data data : currentProgram.getListing().getDefinedData(true)) {
            if (!data.hasStringValue() || !(data.getValue() instanceof String)) continue;
            String value = ((String)data.getValue()).stripTrailing();
            Matcher matcher = STREAM_PATTERN.matcher(value);
            if (!matcher.matches()) continue;
            String address = data.getAddress().toString().toUpperCase(Locale.ROOT);
            JsonObject prior = protocolByAddress.remove(address);
            if (prior == null || !prior.get("value").getAsString().equals(value) ||
                !prior.get("message").getAsString().equals(matcher.group(2)) ||
                !prior.get("field").getAsString().equals(matcher.group(4))) {
                throw new IllegalStateException("stream contract differs from Task3 at " + address);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "RECORD_FIELD:" + address);
            item.put("address", address);
            item.put("directionLabel", matcher.group(1));
            item.put("recordType", matcher.group(2));
            item.put("method", matcher.group(3));
            item.put("fieldName", matcher.group(4));
            item.put("cap", matcher.group(5).equals("%d") ? null : Long.parseLong(matcher.group(5)));
            item.put("capStatus", matcher.group(5).equals("%d") ? "DYNAMIC" : "FIXED_CAP");
            item.put("notPopulationCount", true);
            item.put("value", value);
            item.put("evidence", List.of("ghidra:string:" + address));
            result.add(item);
        }
        if (!protocolByAddress.isEmpty()) {
            throw new IllegalStateException("Task3 stream contracts absent from current program: " + protocolByAddress.keySet());
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> recordParserCandidates(
        List<Map<String, Object>> fields, Set<Long> recordFunctions
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            Address target = toAddr(Long.parseUnsignedLong(String.valueOf(field.get("address")), 16));
            for (Reference reference : getReferencesTo(target)) {
                Function function = getFunctionContaining(reference.getFromAddress());
                if (function != null) recordFunctions.add(function.getEntryPoint().getOffset());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("candidateId", "RECORD_PARSER_REF:" + field.get("address") + ":" + reference.getFromAddress().toString().toUpperCase(Locale.ROOT));
                item.put("fieldCandidateId", field.get("candidateId"));
                item.put("from", reference.getFromAddress().toString().toUpperCase(Locale.ROOT));
                item.put("referenceType", reference.getReferenceType().toString());
                item.put("function", function == null ? null : function.getName().toUpperCase(Locale.ROOT));
                item.put("functionEntry", function == null ? null : function.getEntryPoint().toString().toUpperCase(Locale.ROOT));
                item.put("status", "UNJOINED");
                item.put("firstMissingBoundary", function == null ? "FUNCTION_BOUNDARY" : "FIELD_OFFSET_WIDTH");
                result.add(item);
            }
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> recordSchemas(List<Map<String, Object>> fields) {
        Map<String, List<Map<String, Object>>> byRecord = new LinkedHashMap<>();
        for (Map<String, Object> field : fields) {
            byRecord.computeIfAbsent(String.valueOf(field.get("recordType")), ignored -> new ArrayList<>()).add(field);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        List<String> recordTypes = new ArrayList<>(byRecord.keySet());
        Collections.sort(recordTypes);
        for (String recordType : recordTypes) {
            List<Map<String, Object>> candidates = byRecord.get(recordType);
            Map<String, List<Map<String, Object>>> byField = new LinkedHashMap<>();
            for (Map<String, Object> candidate : candidates) {
                byField.computeIfAbsent(String.valueOf(candidate.get("fieldName")), ignored -> new ArrayList<>()).add(candidate);
            }
            List<Map<String, Object>> normalizedFields = new ArrayList<>();
            List<String> fieldNames = new ArrayList<>(byField.keySet());
            Collections.sort(fieldNames);
            int ordinal = 0;
            for (String fieldName : fieldNames) {
                List<Map<String, Object>> fieldCandidates = byField.get(fieldName);
                Long cap = null;
                List<String> evidence = new ArrayList<>();
                for (Map<String, Object> candidate : fieldCandidates) {
                    if (candidate.get("cap") instanceof Number) {
                        long value = ((Number)candidate.get("cap")).longValue();
                        cap = cap == null ? value : Math.max(cap, value);
                    }
                    evidence.add("ghidra:string:" + candidate.get("address"));
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", "FIELD:" + recordType + ":" + fieldName);
                item.put("ordinal", ordinal++);
                item.put("name", fieldName);
                item.put("semanticNameStatus", "CANDIDATE");
                item.put("status", "CANDIDATE");
                item.put("offsetBytes", null);
                item.put("widthBits", null);
                item.put("scalarKind", "ARRAY_OR_STRING");
                item.put("signedness", "UNKNOWN");
                item.put("arrayCap", cap);
                item.put("aliasGroup", null);
                item.put("reads", new ArrayList<>());
                item.put("writes", new ArrayList<>());
                item.put("comparisons", new ArrayList<>());
                item.put("evidence", evidence);
                normalizedFields.add(item);
            }
            List<String> sourceCandidateIds = candidates.stream()
                .map(candidate -> String.valueOf(candidate.get("candidateId"))).distinct().sorted().toList();
            Map<String, Object> row = baseRow(
                "RECORD_SCHEMA:" + recordType, "RECORD_TYPE", entityTypeForRecord(recordType),
                recordType, recordType, "ORIGINAL_OBSERVED", "UNKNOWN", "RECOVERABLE_STATIC",
                "FIELD_OFFSET_WIDTH", new ArrayList<>(), sourceCandidateIds
            );
            Map<String, Object> layout = new LinkedHashMap<>();
            layout.put("status", "CANDIDATE");
            layout.put("layoutSpace", "WIRE");
            layout.put("strideBytes", null);
            layout.put("recordCap", null);
            layout.put("fields", normalizedFields);
            layout.put("evidence", List.of("ghidra:stream-contract-family:" + recordType));
            row.put("layout", layout);
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> manualEntityRows(
        JsonObject catalog,
        String manualText,
        File manualPageXml,
        String manualPdfSha,
        String manualPageXmlSha,
        List<Map<String, Object>> schemas,
        List<Map<String, Object>> catalogRows
    ) throws Exception {
        String normalizedManual = manualText.replaceAll("\\s+", "");
        List<String> pageTexts = manualPageTexts(manualPageXml);
        Map<String, List<Map<String, Object>>> claims = new LinkedHashMap<>();
        for (Map<String, Object> claim : catalogRows) {
            String entityType = String.valueOf(claim.get("entityType"));
            claims.computeIfAbsent(entityType, ignored -> new ArrayList<>()).add(claim);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement typeElement : catalog.getAsJsonArray("entityTypes")) {
            JsonObject type = typeElement.getAsJsonObject();
            String entityType = type.get("entityType").getAsString();
            String term = type.get("manualTerm").getAsString().replaceAll("\\s+", "");
            long occurrences = countOccurrences(normalizedManual, term);
            if (occurrences == 0) throw new IllegalStateException("manual entity term absent: " + term);
            int pdfPage = type.get("manualPdfPage").getAsInt();
            if (pdfPage < 1 || pdfPage > pageTexts.size() || !pageTexts.get(pdfPage - 1).contains(term)) {
                throw new IllegalStateException("manual PDF page anchor absent: " + entityType + " page=" + pdfPage);
            }
            List<Map<String, Object>> cardinality = new ArrayList<>();
            List<String> sourceCandidateIds = new ArrayList<>();
            for (Map<String, Object> claim : claims.getOrDefault(entityType, Collections.emptyList())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceId", claim.get("sourceId"));
                item.put("status", claim.get("status"));
                item.put("count", claim.get("count"));
                item.put("membershipStatus", claim.get("membershipStatus"));
                item.put("members", claim.getOrDefault("members", new ArrayList<>()));
                item.put("evidence", claim.get("evidence"));
                cardinality.add(item);
                sourceCandidateIds.add(String.valueOf(claim.get("candidateId")));
            }
            List<String> recordEvidence = new ArrayList<>();
            for (Map<String, Object> schema : schemas) {
                if (!entityType.equals(String.valueOf(schema.get("entityType")))) continue;
                sourceCandidateIds.add(String.valueOf(schema.get("candidateId")));
                recordEvidence.add("ghidra:record-schema:" + schema.get("recordType"));
            }
            Map<String, Object> row = baseRow(
                "MANUAL_ENTITY:" + entityType, "ENTITY_TYPE", entityType, null,
                type.get("name").getAsString(), "ORIGINAL_MANUAL", "UNKNOWN",
                type.get("recoveryDisposition").getAsString(), "ID_NAMESPACE",
                cardinality, sourceCandidateIds
            );
            row.put("manualTerm", term);
            row.put("manualOccurrenceCount", occurrences);
            row.put("manualPdfPage", pdfPage);
            List<String> evidence = new ArrayList<>();
            evidence.add("manual-pdf:" + manualPdfSha + ":page:" + pdfPage + ":term:" + term);
            evidence.add("manual-page-xml:" + manualPageXmlSha + ":page:" + pdfPage + ":term:" + term);
            evidence.add("manual-text-search-only:term:" + term + ":count:" + occurrences);
            evidence.addAll(recordEvidence);
            row.put("evidence", evidence);
            result.add(row);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> catalogCandidates(
        JsonObject catalog,
        File manualPageXml,
        String manualPdfSha,
        String manualPageXmlSha
    ) throws Exception {
        JsonObject policy = catalog.getAsJsonObject("policy");
        if (policy.get("legacyCatalogsAreOriginalFacts").getAsBoolean() ||
            policy.get("capsArePopulationCounts").getAsBoolean() ||
            policy.get("assetCountsAreEntityCounts").getAsBoolean() ||
            policy.get("catalogParentIsRuntimeJoin").getAsBoolean()) {
            throw new IllegalStateException("catalog candidate policy is fail-open");
        }
        for (JsonElement sourceElement : catalog.getAsJsonArray("sourceNotes")) {
            JsonObject source = sourceElement.getAsJsonObject();
            File file = new File(source.get("path").getAsString());
            String expected = source.get("sha256").getAsString().toUpperCase(Locale.ROOT);
            if (!file.isFile() || !sha256(Files.readAllBytes(file.toPath())).equals(expected)) {
                throw new IllegalStateException("catalog candidate source hash mismatch: " + file);
            }
        }
        List<String> pageTexts = manualPageTexts(manualPageXml);
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement claimElement : catalog.getAsJsonArray("catalogClaims")) {
            JsonObject claim = claimElement.getAsJsonObject();
            Map<String, Object> item = catalogClaim(claim);
            bindManualMembers(item, pageTexts, manualPdfSha, manualPageXmlSha);
            item.put("firstMissingBoundary", "ORIGINAL_LIVE_POPULATION");
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<String> caseCodes(JsonObject item) {
        List<String> result = new ArrayList<>();
        for (JsonElement code : item.getAsJsonArray("codes")) result.add(code.getAsString().toUpperCase(Locale.ROOT));
        return result;
    }

    private List<Map<String, Object>> wireProjectionCandidates(JsonObject protocolRaw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String collection : List.of("parserCases", "dispatcherCases")) {
            String direction = collection.equals("parserCases") ? "PARSER" : "DISPATCHER";
            for (JsonElement element : protocolRaw.getAsJsonArray(collection)) {
                JsonObject item = element.getAsJsonObject();
                for (String code : caseCodes(item)) {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("candidateId", "WIRE_" + direction + ":" + code);
                    record.put("direction", direction);
                    record.put("protocolCode", code);
                    record.put("messageName", item.has("messageName") && !item.get("messageName").isJsonNull() ? item.get("messageName").getAsString() : null);
                    record.put("status", "UNJOINED");
                    record.put("firstMissingBoundary", "RECORD_TYPE_JOIN");
                    result.add(record);
                }
            }
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> strideCapCandidates(JsonObject protocolRaw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement element : protocolRaw.getAsJsonArray("parserCases")) {
            JsonObject item = element.getAsJsonObject();
            JsonObject allocation = item.getAsJsonObject("allocationSize");
            for (String code : caseCodes(item)) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("candidateId", "STRIDE_CAP:" + code);
                record.put("protocolCode", code);
                record.put("allocationStatus", allocation.get("status").getAsString());
                record.put("allocationBytes", allocation.has("bytes") && !allocation.get("bytes").isJsonNull() ? allocation.get("bytes").getAsLong() : null);
                record.put("notPopulationCount", true);
                record.put("status", "UNJOINED");
                record.put("firstMissingBoundary", "LOOP_CONFIRMED_STRIDE");
                result.add(record);
            }
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> registryAndCacheCandidates(
        JsonObject protocolRaw, boolean registry
    ) {
        List<Map<String, Object>> result = new ArrayList<>();
        String prefix = registry ? "RECORD_REGISTRY" : "CACHE_CONSUMER";
        for (JsonElement element : protocolRaw.getAsJsonArray("dispatcherCases")) {
            JsonObject item = element.getAsJsonObject();
            for (String code : caseCodes(item)) {
                int ordinal = 0;
                for (JsonElement expression : item.getAsJsonArray("destinationExpressions")) {
                    Map<String, Object> record = new LinkedHashMap<>();
                    record.put("candidateId", prefix + ":DEST:" + code + ":" + String.format(Locale.ROOT, "%03d", ordinal++));
                    record.put("protocolCode", code);
                    record.put("kind", "DESTINATION_EXPRESSION");
                    record.put("expression", expression.getAsString());
                    record.put("status", "UNJOINED");
                    record.put("firstMissingBoundary", "CACHE_REGION_NORMALIZATION");
                    result.add(record);
                }
                if (!registry) {
                    ordinal = 0;
                    for (JsonElement helper : item.getAsJsonArray("helperCalls")) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        record.put("candidateId", prefix + ":HELPER:" + code + ":" + String.format(Locale.ROOT, "%03d", ordinal++));
                        record.put("protocolCode", code);
                        record.put("kind", "HELPER_CALL");
                        record.put("function", helper.getAsString().toUpperCase(Locale.ROOT));
                        record.put("status", "UNJOINED");
                        record.put("firstMissingBoundary", "CACHE_EFFECT");
                        result.add(record);
                    }
                }
            }
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> labelCandidates(JsonObject protocolRaw) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement element : protocolRaw.getAsJsonArray("protocolStrings")) {
            JsonObject item = element.getAsJsonObject();
            Map<String, Object> record = new LinkedHashMap<>();
            String address = item.get("address").getAsString().toUpperCase(Locale.ROOT);
            record.put("candidateId", "RECORD_LABEL:" + address);
            record.put("address", address);
            record.put("value", item.get("value").getAsString());
            record.put("status", "UNJOINED");
            record.put("firstMissingBoundary", "ENTITY_OR_RECORD_OWNER");
            result.add(record);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> instructionCandidates(
        Set<Long> functionEntries, String mnemonic, String prefix
    ) {
        Map<Long, Map<String, Object>> unique = new LinkedHashMap<>();
        for (long entry : functionEntries) {
            Function function = getFunctionAt(toAddr(entry));
            if (function == null) continue;
            for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
                if (!instruction.getMnemonicString().equalsIgnoreCase(mnemonic)) continue;
                long address = instruction.getAddress().getOffset();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("candidateId", prefix + ":" + instruction.getAddress().toString().toUpperCase(Locale.ROOT));
                item.put("address", instruction.getAddress().toString().toUpperCase(Locale.ROOT));
                item.put("function", function.getName().toUpperCase(Locale.ROOT));
                item.put("instruction", instruction.toString());
                item.put("status", "UNCLASSIFIED");
                item.put("firstMissingBoundary", prefix.equals("ID_COMPARISON") ? "IDENTITY_OPERAND_JOIN" : "LIFECYCLE_SEMANTICS");
                unique.put(address, item);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(unique.values());
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> relationshipCandidates(List<Map<String, Object>> fields) {
        Pattern terms = Pattern.compile("(?i).*(parent|owner|base|outfit|spot|grid|position|faction|country|character|unit).*");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> field : fields) {
            if (!terms.matcher(String.valueOf(field.get("fieldName"))).matches()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "RELATION_FIELD:" + field.get("address"));
            item.put("fieldCandidateId", field.get("candidateId"));
            item.put("recordType", field.get("recordType"));
            item.put("fieldName", field.get("fieldName"));
            item.put("status", "NAME_ONLY_CANDIDATE");
            item.put("firstMissingBoundary", "CROSS_RECORD_COMPARISON");
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    private List<Map<String, Object>> rendererConsumerCandidates() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (long entry : new long[] {SELECTED_PLANET_BUILDER, SELECTED_PLANET_RENDERER}) {
            Function function = getFunctionAt(toAddr(entry));
            if (function == null) throw new IllegalStateException("selected-system renderer function missing");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "RENDER_CONSUMER:" + hex(entry, 8));
            item.put("function", function.getName().toUpperCase(Locale.ROOT));
            item.put("functionEntry", function.getEntryPoint().toString().toUpperCase(Locale.ROOT));
            item.put("status", "PRESENTATION_CAPACITY_ONLY");
            item.put("firstMissingBoundary", "GLOBAL_PLANET_RECORD_JOIN");
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> derivedEntityTypeRows(
        List<Map<String, Object>> schemas,
        List<Map<String, Object>> manualRows,
        List<Map<String, Object>> catalogRows
    ) {
        Set<String> manualTypes = new LinkedHashSet<>();
        for (Map<String, Object> row : manualRows) manualTypes.add(String.valueOf(row.get("entityType")));
        Set<String> schemaTypes = new LinkedHashSet<>();
        for (Map<String, Object> schema : schemas) schemaTypes.add(String.valueOf(schema.get("entityType")));
        Set<String> derivedTypes = new LinkedHashSet<>(schemaTypes);
        for (Map<String, Object> claim : catalogRows) derivedTypes.add(String.valueOf(claim.get("entityType")));
        derivedTypes.removeAll(manualTypes);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String entityType : derivedTypes) {
            List<Map<String, Object>> cardinality = new ArrayList<>();
            List<String> sourceCandidateIds = new ArrayList<>();
            String provenance = schemaTypes.contains(entityType) ? "ORIGINAL_OBSERVED" : "UNKNOWN";
            for (Map<String, Object> claim : catalogRows) {
                if (!entityType.equals(String.valueOf(claim.get("entityType")))) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("sourceId", claim.get("sourceId"));
                item.put("status", claim.get("status"));
                item.put("count", claim.get("count"));
                item.put("membershipStatus", claim.get("membershipStatus"));
                item.put("members", claim.getOrDefault("members", new ArrayList<>()));
                item.put("evidence", claim.get("evidence"));
                cardinality.add(item);
                sourceCandidateIds.add(String.valueOf(claim.get("candidateId")));
                if (!schemaTypes.contains(entityType)) provenance = String.valueOf(claim.get("status"));
            }
            boolean authoredPlaceholder = provenance.equals("AUTHORED_PLACEHOLDER");
            result.add(baseRow(
                "ENTITY_TYPE:" + entityType, "ENTITY_TYPE", entityType, null,
                entityType.toLowerCase(Locale.ROOT), provenance, "UNKNOWN",
                authoredPlaceholder ? "AUTHORING_REQUIRED" : "RECOVERABLE_STATIC",
                authoredPlaceholder ? "ORIGINAL_ENTITY_EXISTENCE" : "ID_NAMESPACE",
                cardinality, sourceCandidateIds
            ));
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));
        return result;
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 14) {
            throw new IllegalArgumentException(
                "usage: <output> <exe-sha> <exporter-sha> <db-sha> <manual-text> <manual-text-sha> " +
                "<manual-pdf> <manual-pdf-sha> <manual-page-xml> <manual-page-xml-sha> " +
                "<catalog-candidates> <catalog-sha> <protocol-raw> <protocol-sha>"
            );
        }
        String executableSha = currentProgram.getExecutableSHA256().toUpperCase(Locale.ROOT);
        if (!executableSha.equals(args[1].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("program executable SHA-256 mismatch");
        }
        File manualFile = new File(args[4]);
        File manualPdfFile = new File(args[6]);
        File manualPageXmlFile = new File(args[8]);
        File catalogFile = new File(args[10]);
        File protocolFile = new File(args[12]);
        if (!sha256(Files.readAllBytes(manualFile.toPath())).equals(args[5].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(manualPdfFile.toPath())).equals(args[7].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(manualPageXmlFile.toPath())).equals(args[9].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(catalogFile.toPath())).equals(args[11].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(protocolFile.toPath())).equals(args[13].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("records input hash mismatch");
        }
        JsonObject catalog = readJson(catalogFile);
        JsonObject protocolRaw = readJson(protocolFile);
        if (catalog.get("schemaVersion").getAsInt() != 1 || protocolRaw.get("schemaVersion").getAsInt() != 1) {
            throw new IllegalStateException("records input schema mismatch");
        }

        List<Map<String, Object>> fields = scanStreamFields(protocolRaw);
        Set<Long> recordFunctions = new LinkedHashSet<>(List.of(PARSER, DISPATCHER, OUTBOUND));
        List<Map<String, Object>> parsers = recordParserCandidates(fields, recordFunctions);
        List<Map<String, Object>> schemas = recordSchemas(fields);
        List<Map<String, Object>> catalogRows = catalogCandidates(
            catalog,
            manualPageXmlFile,
            args[7].toUpperCase(Locale.ROOT),
            args[9].toUpperCase(Locale.ROOT)
        );
        List<Map<String, Object>> manualRows = manualEntityRows(
            catalog,
            Files.readString(manualFile.toPath(), StandardCharsets.UTF_8),
            manualPageXmlFile,
            args[7].toUpperCase(Locale.ROOT),
            args[9].toUpperCase(Locale.ROOT),
            schemas,
            catalogRows
        );
        List<Map<String, Object>> wire = wireProjectionCandidates(protocolRaw);
        List<Map<String, Object>> strideCaps = strideCapCandidates(protocolRaw);
        List<Map<String, Object>> registries = registryAndCacheCandidates(protocolRaw, true);
        List<Map<String, Object>> caches = registryAndCacheCandidates(protocolRaw, false);
        List<Map<String, Object>> labels = labelCandidates(protocolRaw);
        List<Map<String, Object>> comparisons = instructionCandidates(recordFunctions, "CMP", "ID_COMPARISON");
        List<Map<String, Object>> lifecycle = instructionCandidates(recordFunctions, "CALL", "LIFECYCLE_CALL");
        List<Map<String, Object>> relations = relationshipCandidates(fields);
        List<Map<String, Object>> renderers = rendererConsumerCandidates();
        List<Map<String, Object>> entityTypes = derivedEntityTypeRows(schemas, manualRows, catalogRows);

        Set<String> families = new LinkedHashSet<>();
        Set<String> familyFields = new LinkedHashSet<>();
        for (Map<String, Object> field : fields) {
            String family = String.valueOf(field.get("recordType"));
            families.add(family);
            familyFields.add(family + "\u0000" + field.get("fieldName"));
        }
        int parserCodes = 0;
        for (JsonElement element : protocolRaw.getAsJsonArray("parserCases")) parserCodes += element.getAsJsonObject().getAsJsonArray("codes").size();
        int dispatcherCodes = 0;
        int destinations = 0;
        int helpers = 0;
        for (JsonElement element : protocolRaw.getAsJsonArray("dispatcherCases")) {
            JsonObject item = element.getAsJsonObject();
            dispatcherCodes += item.getAsJsonArray("codes").size();
            destinations += item.getAsJsonArray("destinationExpressions").size();
            helpers += item.getAsJsonArray("helperCalls").size();
        }
        if (fields.size() != EXPECTED_STREAM_CONTRACTS || families.size() != EXPECTED_RECORD_FAMILIES ||
            familyFields.size() != EXPECTED_FAMILY_FIELDS || parserCodes != EXPECTED_PARSER_CODES ||
            dispatcherCodes != EXPECTED_DISPATCHER_CODES || destinations != EXPECTED_DESTINATIONS ||
            helpers != EXPECTED_HELPERS || protocolRaw.getAsJsonArray("protocolStrings").size() != EXPECTED_PROTOCOL_LABELS) {
            throw new IllegalStateException("record surface conservation mismatch");
        }

        Map<String, Object> surface = new LinkedHashMap<>();
        surface.put("entityTypeCandidates", entityTypes);
        surface.put("recordSchemaCandidates", schemas);
        surface.put("recordFieldCandidates", fields);
        surface.put("recordParserCandidates", parsers);
        surface.put("recordRegistryCandidates", registries);
        surface.put("strideCapCandidates", strideCaps);
        surface.put("idComparisonCandidates", comparisons);
        surface.put("relationshipCandidates", relations);
        surface.put("lifecycleCandidates", lifecycle);
        surface.put("wireProjectionCandidates", wire);
        surface.put("cacheConsumerCandidates", caches);
        surface.put("rendererConsumerCandidates", renderers);
        surface.put("catalogCandidates", catalogRows);
        surface.put("manualEntityCandidates", manualRows);
        surface.put("labelCandidates", labels);

        Gson compact = new GsonBuilder().serializeNulls().create();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 1);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("program", currentProgram.getName());
        source.put("executableSha256", executableSha);
        source.put("language", currentProgram.getLanguageID().toString());
        source.put("compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        source.put("imageBase", currentProgram.getImageBase().toString());
        source.put("manualTextSha256", args[5].toUpperCase(Locale.ROOT));
        source.put("manualPdfSha256", args[7].toUpperCase(Locale.ROOT));
        source.put("manualPageXmlSha256", args[9].toUpperCase(Locale.ROOT));
        source.put("catalogCandidateSha256", args[11].toUpperCase(Locale.ROOT));
        source.put("protocolRawSha256", args[13].toUpperCase(Locale.ROOT));
        output.put("source", source);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("class", getClass().getSimpleName());
        exporter.put("sha256", args[2].toUpperCase(Locale.ROOT));
        exporter.put("ghidraRepositorySha256", args[3].toUpperCase(Locale.ROOT));
        output.put("exporter", exporter);
        output.put("surfaceSha256", sha256(compact.toJson(surface).getBytes(StandardCharsets.UTF_8)));
        output.put("successMarker", "EXPORT_EXHAUSTIVE_RECORDS_OK");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("scope", "COMPILED_RECORD_ANCHORS");
        audit.put("capsArePopulationCounts", false);
        audit.put("catalogParentIsRuntimeJoin", false);
        audit.put("authorityPersistenceCovered", false);
        audit.put("limitations", List.of(
            "original live populations remain unknown",
            "legacy catalogs remain candidates until independently reproduced from original sources",
            "field offsets and stable identity joins remain unresolved unless operand-level evidence exists"
        ));
        output.put("audit", audit);
        Map<String, Object> conservation = new LinkedHashMap<>();
        conservation.put("streamContracts", fields.size());
        conservation.put("recordFamilies", families.size());
        conservation.put("familyFields", familyFields.size());
        conservation.put("parserCodes", parserCodes);
        conservation.put("dispatcherCodes", dispatcherCodes);
        conservation.put("destinationExpressions", destinations);
        conservation.put("helperReferences", helpers);
        conservation.put("protocolLabels", labels.size());
        conservation.put("manualEntityTypes", manualRows.size());
        conservation.put("catalogClaims", catalogRows.size());
        output.put("conservation", conservation);
        output.putAll(surface);

        File outputFile = new File(args[0]);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory()) throw new IllegalStateException("output directory does not exist");
        Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
            writer.print(gson.toJson(output));
            writer.print("\n");
        }
        println("EXPORT_EXHAUSTIVE_RECORDS_OK output=" + outputFile.getAbsolutePath());
    }
}
