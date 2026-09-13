import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DataType;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionIterator;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.Symbol;

public class ExportExhaustiveFunctions extends GhidraScript {
    private static final Pattern FUNCTION_TOKEN = Pattern.compile(
        "^FUN_([0-9A-Fa-f]{8})(?:@.*)?$"
    );
    private static final Pattern STATIC_ADDRESS_TOKEN = Pattern.compile(
        "^(?:0x)?([0-9A-Fa-f]{8})$"
    );
    private static final Set<String> STRUCTURED_FUNCTION_ADDRESS_FIELDS = Set.of(
        "startupFunction", "registryFunction", "produceFunction", "sendFunction",
        "parseFunction", "messageInputFunction", "baseCodeFunction", "countFunction",
        "maxLengthFunction", "constructor", "factory", "clientToServerLookup",
        "serverToClientLookup", "functionEntry", "entry"
    );
    private static final Set<String> HEURISTIC_FUNCTION_FIELDS = Set.of(
        "nearestPriorFunction", "nearestPriorFunctionEntry"
    );
    private static final long[] REQUIRED_INTERNAL_ANCHORS = new long[] {
        0x004B78A0L, 0x004B8B00L, 0x004BA2B0L,
        0x004B68F0L, 0x004C32A0L, 0x004D3BD0L, 0x004D68D0L,
        0x004FF3C0L, 0x00500580L, 0x00500B70L, 0x005015F0L,
        0x005024E0L, 0x00503910L, 0x00503A10L, 0x00504450L,
        0x00507100L, 0x0050BB40L, 0x0050C880L, 0x0050D230L,
        0x005123B0L, 0x0051CA30L, 0x00525C80L, 0x0054E570L
    };

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(bytes)) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static String address(Address value) {
        return value.toString().replace("0x", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeStaticAddress(String value) {
        String result = value.trim().toUpperCase(Locale.ROOT);
        if (result.startsWith("0X")) result = result.substring(2);
        if (!result.matches("[0-9A-F]{8}")) throw new IllegalStateException("invalid static address: " + value);
        return result;
    }

    private static String pointerEscape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static Map<String, Object> directCall(
        String addressField, String functionAddress, String callsite
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(addressField, functionAddress);
        result.put("callsite", callsite);
        result.put("kind", "DIRECT_CALL");
        result.put("evidence", List.of("ghidra:call:" + callsite));
        return result;
    }

    private String symbolName(Address target) {
        Symbol symbol = currentProgram.getSymbolTable().getPrimarySymbol(target);
        return symbol == null ? "DAT_" + address(target) : symbol.getName(true);
    }

    private Map<String, Object> dataReference(Reference reference, String kind) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetAddress", address(reference.getToAddress()));
        result.put("targetSymbol", symbolName(reference.getToAddress()));
        result.put("refType", reference.getReferenceType().toString());
        result.put("referenceAddress", address(reference.getFromAddress()));
        result.put("evidence", List.of(
            "ghidra:data-" + kind.toLowerCase(Locale.ROOT) + ":" + address(reference.getFromAddress())
        ));
        return result;
    }

    private Map<String, Object> stringReference(Reference reference, Data data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stringAddress", address(data.getAddress()));
        result.put("referenceAddress", address(reference.getFromAddress()));
        result.put("value", String.valueOf(data.getValue()));
        result.put("evidence", List.of("ghidra:string-ref:" + address(reference.getFromAddress())));
        return result;
    }

    private Map<String, Object> indirectCall(Instruction instruction, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("callsite", address(instruction.getAddress()));
        result.put("operand", instruction.getNumOperands() == 0
            ? instruction.toString()
            : instruction.getDefaultOperandRepresentation(0));
        result.put("status", "UNRESOLVED");
        result.put("reason", status);
        result.put("evidence", List.of("ghidra:indirect-call:" + address(instruction.getAddress())));
        return result;
    }

    private static List<Map<String, Object>> sortedRecords(
        List<Map<String, Object>> records, String primary, String secondary
    ) {
        records.sort(Comparator
            .comparing((Map<String, Object> item) -> String.valueOf(item.get(primary)))
            .thenComparing(item -> String.valueOf(item.getOrDefault(secondary, ""))));
        return records;
    }

    private static Set<String> rawImportAddresses(JsonObject peImports) {
        Set<String> result = new TreeSet<>();
        for (JsonElement element : peImports.getAsJsonArray("imports")) {
            result.add(normalizeStaticAddress(element.getAsJsonObject().get("iatVa").getAsString()));
        }
        return result;
    }

    private static List<Map<String, Object>> externalMembers(JsonObject peImports) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonElement element : peImports.getAsJsonArray("imports")) {
            JsonObject item = element.getAsJsonObject();
            String dll = item.get("dll").getAsString().toUpperCase(Locale.ROOT);
            String symbol;
            if (item.has("name")) symbol = item.get("name").getAsString();
            else if (item.has("resolvedName")) symbol = item.get("resolvedName").getAsString();
            else symbol = "ordinal_" + item.get("ordinal").getAsLong();
            String iat = normalizeStaticAddress(item.get("iatVa").getAsString());
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("address", iat);
            member.put("name", dll + "::" + symbol);
            member.put("namespace", "EXTERNAL");
            member.put("evidence", List.of("pe-imports:" + dll + "::" + symbol + ":" + iat));
            result.add(member);
        }
        result.sort(Comparator.comparing(member -> String.valueOf(member.get("address"))));
        return result;
    }

    private Map<String, Object> signature(Function function) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN");
        result.put("callingConvention", function.getCallingConventionName());
        DataType returnType = function.getReturnType();
        result.put("returnType", returnType == null ? "undefined" : returnType.getDisplayName());
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter parameter : function.getParameters()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ordinal", parameter.getOrdinal());
            item.put("name", parameter.getName());
            item.put("dataType", parameter.getDataType().getDisplayName());
            item.put("storage", parameter.getVariableStorage().toString());
            parameters.add(item);
        }
        result.put("parameters", parameters);
        result.put("evidence", List.of("ghidra:signature:" + address(function.getEntryPoint())));
        return result;
    }

    private static void addUniqueRecord(
        List<Map<String, Object>> records, Set<String> seen, String key, Map<String, Object> record
    ) {
        if (seen.add(key)) records.add(record);
    }

    private List<Map<String, Object>> scanInternalFunctions(
        Map<String, Function> internalByAddress,
        Set<String> thunkAddresses,
        Set<String> importAddresses,
        Map<String, List<Map<String, Object>>> callerRecords,
        Map<String, Set<String>> unresolvedTargets
    ) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Function> entry : internalByAddress.entrySet()) {
            String functionAddress = entry.getKey();
            Function function = entry.getValue();
            if (thunkAddresses.contains(functionAddress)) continue;
            List<Map<String, Object>> callees = new ArrayList<>();
            List<Map<String, Object>> reads = new ArrayList<>();
            List<Map<String, Object>> writes = new ArrayList<>();
            List<Map<String, Object>> strings = new ArrayList<>();
            List<Map<String, Object>> indirect = new ArrayList<>();
            Set<String> calleeSeen = new LinkedHashSet<>();
            Set<String> readSeen = new LinkedHashSet<>();
            Set<String> writeSeen = new LinkedHashSet<>();
            Set<String> stringSeen = new LinkedHashSet<>();
            Set<String> indirectSeen = new LinkedHashSet<>();
            Set<String> sideEffects = new TreeSet<>();
            int instructionCount = 0;
            for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
                instructionCount++;
                String instructionAddress = address(instruction.getAddress());
                String mnemonic = instruction.getMnemonicString().toUpperCase(Locale.ROOT);
                if (mnemonic.startsWith("RET")) sideEffects.add("RETURNS");
                if (mnemonic.equals("CALL")) {
                    boolean resolvedInternal = false;
                    for (Address flow : instruction.getFlows()) {
                        if (flow.getAddressSpace().isExternalSpace()) continue;
                        String target = address(flow);
                        if (internalByAddress.containsKey(target)) {
                            Map<String, Object> record = directCall("targetAddress", target, instructionAddress);
                            addUniqueRecord(callees, calleeSeen, instructionAddress + ":" + target, record);
                            callerRecords.computeIfAbsent(target, ignored -> new ArrayList<>()).add(
                                directCall("sourceAddress", functionAddress, instructionAddress)
                            );
                            sideEffects.add("CALLS_INTERNAL");
                            resolvedInternal = true;
                        }
                        else if (currentProgram.getMemory().contains(flow)) {
                            Map<String, Object> record = directCall("targetAddress", target, instructionAddress);
                            addUniqueRecord(callees, calleeSeen, instructionAddress + ":" + target, record);
                            unresolvedTargets.computeIfAbsent(target, ignored -> new TreeSet<>()).add(instructionAddress);
                            sideEffects.add("CALLS_INDIRECT");
                            resolvedInternal = true;
                        }
                    }
                    boolean imported = false;
                    for (Reference reference : instruction.getReferencesFrom()) {
                        if (!reference.getToAddress().getAddressSpace().isMemorySpace()) continue;
                        String target = address(reference.getToAddress());
                        if (importAddresses.contains(target)) imported = true;
                    }
                    if (imported) sideEffects.add("CALLS_EXTERNAL");
                    if (!resolvedInternal) {
                        addUniqueRecord(
                            indirect, indirectSeen, instructionAddress,
                            indirectCall(instruction, imported ? "IMPORT_IAT_INDIRECT" : "COMPUTED_TARGET")
                        );
                        sideEffects.add("CALLS_INDIRECT");
                    }
                }
                for (Reference reference : instruction.getReferencesFrom()) {
                    Address target = reference.getToAddress();
                    if (!target.getAddressSpace().isMemorySpace() || !currentProgram.getMemory().contains(target)) continue;
                    if (reference.getReferenceType().isCall() || reference.getReferenceType().isJump()) continue;
                    String key = instructionAddress + ":" + address(target) + ":" + reference.getOperandIndex();
                    boolean isWrite = reference.getReferenceType().isWrite();
                    boolean isRead = reference.getReferenceType().isRead() || !isWrite;
                    if (isRead) {
                        addUniqueRecord(reads, readSeen, key, dataReference(reference, "READ"));
                        sideEffects.add("READS_GLOBAL");
                    }
                    if (isWrite) {
                        addUniqueRecord(writes, writeSeen, key, dataReference(reference, "WRITE"));
                        sideEffects.add("WRITES_GLOBAL");
                    }
                    Data data = currentProgram.getListing().getDataContaining(target);
                    if (data != null && data.hasStringValue()) {
                        addUniqueRecord(strings, stringSeen, key, stringReference(reference, data));
                    }
                }
            }
            if (instructionCount <= 0) throw new IllegalStateException("internal function has no instructions: " + functionAddress);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "FUNCTION:" + functionAddress);
            item.put("address", functionAddress);
            item.put("ghidraName", function.getName().toUpperCase(Locale.ROOT));
            item.put("namespace", "INTERNAL");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("minAddress", address(function.getBody().getMinAddress()));
            body.put("maxAddress", address(function.getBody().getMaxAddress()));
            body.put("instructionCount", instructionCount);
            item.put("body", body);
            item.put("signature", signature(function));
            item.put("callers", callerRecords.computeIfAbsent(functionAddress, ignored -> new ArrayList<>()));
            item.put("callees", sortedRecords(callees, "callsite", "targetAddress"));
            Map<String, Object> dataReferences = new LinkedHashMap<>();
            dataReferences.put("reads", sortedRecords(reads, "referenceAddress", "targetAddress"));
            dataReferences.put("writes", sortedRecords(writes, "referenceAddress", "targetAddress"));
            item.put("dataReferences", dataReferences);
            item.put("stringReferences", sortedRecords(strings, "referenceAddress", "stringAddress"));
            item.put("indirectCallsites", sortedRecords(indirect, "callsite", "operand"));
            item.put("sideEffects", new ArrayList<>(sideEffects));
            Map<String, Object> classification = new LinkedHashMap<>();
            classification.put("status", "UNADJUDICATED_INTERNAL");
            classification.put("reasons", new ArrayList<>());
            item.put("classification", classification);
            item.put("evidence", List.of("ghidra:function:" + functionAddress));
            result.add(item);
        }
        // Caller lists were populated while later functions were scanned; sort only after the full pass.
        for (Map<String, Object> item : result) {
            String functionAddress = String.valueOf(item.get("address"));
            item.put("callers", sortedRecords(
                callerRecords.getOrDefault(functionAddress, new ArrayList<>()), "callsite", "sourceAddress"
            ));
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("address"))));
        return result;
    }

    private static void collectUpstreamTokens(
        String artifact,
        String artifactSha,
        JsonElement element,
        String pointer,
        String leafKey,
        Set<String> individualIds,
        List<Map<String, Object>> result,
        Set<String> candidateIds
    ) throws Exception {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectUpstreamTokens(
                    artifact, artifactSha, entry.getValue(), pointer + "/" + pointerEscape(entry.getKey()),
                    entry.getKey(), individualIds, result, candidateIds
                );
            }
            return;
        }
        if (element.isJsonArray()) {
            int index = 0;
            for (JsonElement child : element.getAsJsonArray()) {
                collectUpstreamTokens(
                    artifact, artifactSha, child, pointer + "/" + index,
                    leafKey, individualIds, result, candidateIds
                );
                index++;
            }
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) return;
        if (HEURISTIC_FUNCTION_FIELDS.contains(leafKey)) return;
        String token = element.getAsString();
        Matcher matcher = FUNCTION_TOKEN.matcher(token);
        Matcher addressMatcher = STATIC_ADDRESS_TOKEN.matcher(token);
        String functionAddress;
        String referenceKind;
        if (matcher.matches()) {
            functionAddress = matcher.group(1).toUpperCase(Locale.ROOT);
            referenceKind = "STRUCTURED_FUNCTION_TOKEN";
        }
        else if (STRUCTURED_FUNCTION_ADDRESS_FIELDS.contains(leafKey) && addressMatcher.matches()) {
            functionAddress = addressMatcher.group(1).toUpperCase(Locale.ROOT);
            referenceKind = "STRUCTURED_FUNCTION_ADDRESS";
        }
        else return;
        String functionId = "FUNCTION:" + functionAddress;
        String fingerprint = sha256((artifact + "\u0000" + pointer + "\u0000" + token).getBytes(StandardCharsets.UTF_8));
        String candidateId = "UPSTREAM_REF:" + artifact.toUpperCase(Locale.ROOT) + ":" + fingerprint.substring(0, 24);
        if (!candidateIds.add(candidateId)) throw new IllegalStateException("duplicate upstream reference ID");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("candidateId", candidateId);
        item.put("artifact", artifact);
        item.put("artifactSha256", artifactSha);
        item.put("jsonPointer", pointer.isEmpty() ? "/" : pointer);
        item.put("token", token);
        item.put("referenceKind", referenceKind);
        if (individualIds.contains(functionId)) {
            item.put("resolvedFunctionCandidateId", functionId);
            item.put("status", "MENTION");
        }
        else {
            item.put("resolvedFunctionCandidateId", null);
            item.put("status", "UNRESOLVED");
            item.put("firstMissingBoundary", "INDIVIDUAL_FUNCTION_DEFINITION");
        }
        item.put("evidence", List.of(artifact + ":" + (pointer.isEmpty() ? "/" : pointer)));
        result.add(item);
    }

    private static Map<String, Object> group(
        String candidateId, String kind, String rule, List<Map<String, Object>> members
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidateId", candidateId);
        result.put("groupKind", kind);
        result.put("groupingRule", rule);
        result.put("members", members);
        result.put("evidence", List.of("ghidra:function-group:" + kind));
        return result;
    }

    private static JsonObject readJson(File file) throws Exception {
        return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 16) {
            throw new IllegalArgumentException(
                "usage: <output> <exe-sha> <exporter-sha> <db-sha> <source-manifest> <source-sha> " +
                "<pe-imports> <pe-sha> <protocol-raw> <protocol-sha> <ui-raw> <ui-sha> " +
                "<records-raw> <records-sha> <resources-raw> <resources-sha>"
            );
        }
        String executableSha = currentProgram.getExecutableSHA256().toUpperCase(Locale.ROOT);
        if (!executableSha.equals(args[1].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("program executable SHA-256 mismatch");
        }
        File sourceManifest = new File(args[4]);
        File peImportsFile = new File(args[6]);
        File protocolFile = new File(args[8]);
        File uiFile = new File(args[10]);
        File recordsFile = new File(args[12]);
        File resourcesFile = new File(args[14]);
        File[] inputFiles = new File[] {
            sourceManifest, peImportsFile, protocolFile, uiFile, recordsFile, resourcesFile
        };
        int[] hashIndexes = new int[] {5, 7, 9, 11, 13, 15};
        for (int index = 0; index < inputFiles.length; index++) {
            if (!sha256(Files.readAllBytes(inputFiles[index].toPath())).equals(
                args[hashIndexes[index]].toUpperCase(Locale.ROOT)
            )) throw new IllegalStateException("functions input hash mismatch: " + inputFiles[index]);
        }
        JsonObject peImports = readJson(peImportsFile);
        JsonObject protocol = readJson(protocolFile);
        JsonObject ui = readJson(uiFile);
        JsonObject records = readJson(recordsFile);
        JsonObject resources = readJson(resourcesFile);

        Map<String, Function> internalByAddress = new TreeMap<>();
        Set<String> thunkAddresses = new TreeSet<>();
        List<Map<String, Object>> thunkMembers = new ArrayList<>();
        int ghidraExternalFunctions = 0;
        FunctionIterator iterator = currentProgram.getFunctionManager().getFunctions(true);
        while (iterator.hasNext()) {
            Function function = iterator.next();
            if (function.isExternal()) {
                ghidraExternalFunctions++;
                continue;
            }
            String entry = address(function.getEntryPoint());
            internalByAddress.put(entry, function);
            if (function.isThunk()) {
                thunkAddresses.add(entry);
                Function target = function.getThunkedFunction(false);
                Map<String, Object> member = new LinkedHashMap<>();
                member.put("address", entry);
                member.put("name", function.getName().toUpperCase(Locale.ROOT));
                member.put("namespace", "INTERNAL");
                member.put("thunkTarget", target == null ? "UNRESOLVED" : target.getEntryPoint().toString().toUpperCase(Locale.ROOT));
                member.put("evidence", List.of("ghidra:thunk:" + entry));
                thunkMembers.add(member);
            }
        }
        // Some Ghidra versions do not include externals in getFunctions(true).
        int explicitExternalCount = 0;
        FunctionIterator externalIterator = currentProgram.getFunctionManager().getExternalFunctions();
        while (externalIterator.hasNext()) {
            externalIterator.next();
            explicitExternalCount++;
        }
        if (ghidraExternalFunctions == 0) ghidraExternalFunctions = explicitExternalCount;
        else if (explicitExternalCount != 0 && ghidraExternalFunctions != explicitExternalCount) {
            throw new IllegalStateException("Ghidra external function iteration differs");
        }
        for (long anchor : REQUIRED_INTERNAL_ANCHORS) {
            String key = String.format(Locale.ROOT, "%08X", anchor);
            if (!internalByAddress.containsKey(key)) {
                throw new IllegalStateException("required function anchor missing: " + key);
            }
        }

        List<Map<String, Object>> externalMembers = externalMembers(peImports);
        if (externalMembers.size() != peImports.get("importCount").getAsInt()) {
            throw new IllegalStateException("raw PE import count differs");
        }
        Set<String> importAddresses = rawImportAddresses(peImports);
        Map<String, List<Map<String, Object>>> callerRecords = new TreeMap<>();
        Map<String, Set<String>> unresolvedTargets = new TreeMap<>();
        List<Map<String, Object>> functions = scanInternalFunctions(
            internalByAddress, thunkAddresses, importAddresses, callerRecords, unresolvedTargets
        );
        thunkMembers.sort(Comparator.comparing(member -> String.valueOf(member.get("address"))));
        if (thunkMembers.isEmpty()) throw new IllegalStateException("expected at least one internal thunk");
        List<Map<String, Object>> groups = new ArrayList<>();
        groups.add(group(
            "FUNCTION_GROUP:EXTERNAL_IMPORT", "EXTERNAL_IMPORT",
            "namespace=EXTERNAL and source=raw-pe-imports", externalMembers
        ));
        groups.add(group(
            "FUNCTION_GROUP:THUNK", "THUNK", "isThunk=true", thunkMembers
        ));

        Set<String> individualIds = new TreeSet<>();
        for (Map<String, Object> function : functions) individualIds.add(String.valueOf(function.get("candidateId")));
        List<Map<String, Object>> upstream = new ArrayList<>();
        Set<String> upstreamIds = new LinkedHashSet<>();
        collectUpstreamTokens("protocol-ghidra", args[9].toUpperCase(Locale.ROOT), protocol, "", "", individualIds, upstream, upstreamIds);
        collectUpstreamTokens("ui-ghidra", args[11].toUpperCase(Locale.ROOT), ui, "", "", individualIds, upstream, upstreamIds);
        collectUpstreamTokens("records-ghidra", args[13].toUpperCase(Locale.ROOT), records, "", "", individualIds, upstream, upstreamIds);
        collectUpstreamTokens("resources-ghidra", args[15].toUpperCase(Locale.ROOT), resources, "", "", individualIds, upstream, upstreamIds);
        upstream.sort(Comparator.comparing(item -> String.valueOf(item.get("candidateId"))));

        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : unresolvedTargets.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("candidateId", "UNRESOLVED_TARGET:" + entry.getKey());
            item.put("targetAddress", entry.getKey());
            item.put("callsites", new ArrayList<>(entry.getValue()));
            item.put("status", "UNRESOLVED");
            item.put("firstMissingBoundary", "FUNCTION_DEFINITION");
            List<String> evidence = new ArrayList<>();
            for (String callsite : entry.getValue()) evidence.add("ghidra:call:" + callsite);
            item.put("evidence", evidence);
            unresolved.add(item);
        }

        Map<String, Object> surface = new LinkedHashMap<>();
        surface.put("functionCandidates", functions);
        surface.put("functionGroupCandidates", groups);
        surface.put("upstreamReferenceCandidates", upstream);
        surface.put("unresolvedTargetCandidates", unresolved);
        Gson compact = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("schemaVersion", 1);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("program", currentProgram.getName());
        source.put("executableSha256", executableSha);
        source.put("language", currentProgram.getLanguageID().toString());
        source.put("compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        source.put("imageBase", currentProgram.getImageBase().toString());
        source.put("sourceManifestSha256", args[5].toUpperCase(Locale.ROOT));
        source.put("peImportsSha256", args[7].toUpperCase(Locale.ROOT));
        source.put("protocolRawSha256", args[9].toUpperCase(Locale.ROOT));
        source.put("uiRawSha256", args[11].toUpperCase(Locale.ROOT));
        source.put("recordsRawSha256", args[13].toUpperCase(Locale.ROOT));
        source.put("resourcesRawSha256", args[15].toUpperCase(Locale.ROOT));
        output.put("source", source);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("class", getClass().getSimpleName());
        exporter.put("sha256", args[2].toUpperCase(Locale.ROOT));
        exporter.put("ghidraRepositorySha256", args[3].toUpperCase(Locale.ROOT));
        output.put("exporter", exporter);
        output.put("surfaceSha256", sha256(compact.toJson(surface).getBytes(StandardCharsets.UTF_8)));
        output.put("successMarker", "EXPORT_EXHAUSTIVE_FUNCTIONS_OK");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("scope", "FUNCTION_SURFACE_UNIVERSE");
        audit.put("sizeAloneClassifiesPlumbing", false);
        audit.put("upstreamMentionIsSemanticIdentity", false);
        audit.put("staticCallgraphIsRuntimeReachability", false);
        audit.put("groupedTargetReciprocity", "INDIVIDUAL_ONLY_GROUP_INBOUND_RETAINED_IN_CALLER");
        audit.put("limitations", List.of(
            "internal non-thunk functions remain individual even without upstream mentions",
            "Ghidra function boundaries and signatures remain static analysis candidates",
            "computed call targets, runtime reachability, authority, persistence, and presentation remain unresolved"
        ));
        output.put("audit", audit);
        Map<String, Object> conservation = new LinkedHashMap<>();
        conservation.put("functionSurfaceMembers", functions.size() + thunkMembers.size() + externalMembers.size());
        conservation.put("ghidraDefinedFunctions", currentProgram.getFunctionManager().getFunctionCount());
        conservation.put("ghidraInternalFunctions", internalByAddress.size());
        conservation.put("individualFunctions", functions.size());
        conservation.put("groupedMembers", thunkMembers.size() + externalMembers.size());
        conservation.put("externalFunctions", externalMembers.size());
        conservation.put("thunkFunctions", thunkMembers.size());
        conservation.put("ghidraExternalFunctions", ghidraExternalFunctions);
        conservation.put("rawPeImports", externalMembers.size());
        conservation.put("upstreamReferences", upstream.size());
        conservation.put("unresolvedTargets", unresolved.size());
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
        println("EXPORT_EXHAUSTIVE_FUNCTIONS_OK output=" + outputFile.getAbsolutePath());
    }
}
