import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.Reference;

public class ExportExhaustiveProtocol extends GhidraScript {
    private static final long PARSER = 0x004B8B00L;
    private static final long DISPATCHER = 0x004BA2B0L;
    private static final long OUTBOUND = 0x004B78A0L;
    private static final long MESSAGE32_STARTUP = 0x004AD120L;
    private static final long MESSAGE32_SYSTEM_FACTORY = 0x00403A80L;
    private static final long MESSAGE32_REGISTRY = 0x00404800L;
    private static final long MESSAGE32_SEND = 0x00403C60L;
    private static final long MESSAGE32_PARSE = 0x00403E30L;
    private static final long MESSAGE32_PRODUCE = 0x00403F70L;
    private static final long MESSAGE32_INPUT = 0x00404210L;
    private static final Pattern CASE_PATTERN = Pattern.compile("^\\s*case\\s+(0x[0-9a-fA-F]+|[0-9]+)\\s*:");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("FUN_[0-9a-fA-F]{8}");
    private static final Pattern STRING_SYMBOL_PATTERN = Pattern.compile("s_[A-Za-z0-9_]+_([0-9a-fA-F]{8})");
    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile("(?m)^\\s*([*A-Za-z0-9_+()\\[\\] .-]+)\\s*=\\s*([^;]+);");
    private static final Pattern STREAM_PATTERN = Pattern.compile(
        "^\\[(Input|Output)_(.+?)::(input_from_stream|get_length|output_to_stream)\\]\\s+" +
        "(.+?)_size\\[%d\\]\\s+is over than\\s+([0-9]+|%d)\\.?$"
    );
    private static final Pattern PROTOCOL_STRING_PATTERN = Pattern.compile(
        "^(Command|Notify|Request|Response|Lobby|LG|MailSv|NPC|GlobalChat|SS|Sys(?!tem)|Transaction).*$"
    );

    private static class CaseGroup {
        final List<String> codes = new ArrayList<>();
        final List<String> body = new ArrayList<>();
    }

    private static class HandlerFamily {
        final long registrationCallsite;
        final long registryCallsite;
        final long factory;
        final long constructor;
        final long vtable;
        final long baseCode;
        final long count;
        final Set<Long> clientToServerOffsets;
        final Set<Long> serverToClientOffsets;

        HandlerFamily(long registrationCallsite, long factory, long constructor, long vtable,
                      long baseCode, long count, String clientToServer, String serverToClient) {
            this(registrationCallsite, 0, factory, constructor, vtable, baseCode, count,
                 offsetSet(clientToServer), offsetSet(serverToClient));
        }

        HandlerFamily(long registrationCallsite, long registryCallsite, long factory,
                      long constructor, long vtable, long baseCode, long count,
                      Set<Long> clientToServerOffsets, Set<Long> serverToClientOffsets) {
            this.registrationCallsite = registrationCallsite;
            this.registryCallsite = registryCallsite;
            this.factory = factory;
            this.constructor = constructor;
            this.vtable = vtable;
            this.baseCode = baseCode;
            this.count = count;
            this.clientToServerOffsets = new LinkedHashSet<>(clientToServerOffsets);
            this.serverToClientOffsets = new LinkedHashSet<>(serverToClientOffsets);
        }
    }

    private List<HandlerFamily> derivedMessage32Families;

    private static Set<Long> offsetSet(String specification) {
        Set<Long> result = new LinkedHashSet<>();
        if (specification.isEmpty()) return result;
        for (String rawToken : specification.split(",")) {
            String token = rawToken.trim();
            long step = 1;
            if (token.contains("/")) {
                String[] pieces = token.split("/", 2);
                token = pieces[0];
                step = Long.parseLong(pieces[1], 16);
            }
            if (token.contains("-")) {
                String[] bounds = token.split("-", 2);
                long start = Long.parseLong(bounds[0], 16);
                long end = Long.parseLong(bounds[1], 16);
                for (long value = start; value <= end; value += step) result.add(value);
            }
            else result.add(Long.parseLong(token, 16));
        }
        return result;
    }

    private static final HandlerFamily[] MESSAGE32_FAMILIES = new HandlerFamily[] {
        new HandlerFamily(0x004AD1F8L, 0x0044E440L, 0x0044E4B0L, 0x0066D120L, 0x0200, 0x08, "0,3,5,7", "1,2,4,6,7"),
        new HandlerFamily(0x004AD213L, 0x0040A700L, 0x0040A770L, 0x0066C298L, 0x0300, 0x5B, "0-54/2", "1-55/2,56-5A"),
        new HandlerFamily(0x004AD22DL, 0x004A49C0L, 0x004A4A30L, 0x0066DBA4L, 0x0400, 0x43, "0-22,30", "0-2F,31-42"),
        new HandlerFamily(0x004AD247L, 0x00439130L, 0x004391A0L, 0x0066CB20L, 0x0500, 0x02, "", "0-1"),
        new HandlerFamily(0x004AD262L, 0x00481D40L, 0x00481DB0L, 0x0066D498L, 0x0F00, 0x20, "0,2,4,6,8,B-14,16-1E", "1,3,5,7,8-1F"),
        new HandlerFamily(0x004AD27CL, 0x0044D8F0L, 0x0044D960L, 0x0066D0D4L, 0x0E00, 0x01, "0", "0"),
        new HandlerFamily(0x004AD296L, 0x0044B170L, 0x0044B1E0L, 0x0066CFFCL, 0x0B00, 0x0E, "0-6", "0-D"),
        new HandlerFamily(0x004AD2B1L, 0x00491E10L, 0x00491E80L, 0x0066D654L, 0x0900, 0x09, "0-3,6", "0-6,8"),
        new HandlerFamily(0x004AD2CBL, 0x0055A800L, 0x0055A870L, 0x006747C4L, 0x0C00, 0x0D, "0-2,5,8,B-C", "0-C"),
        new HandlerFamily(0x004AD2E5L, 0x0055B790L, 0x0055B800L, 0x00674888L, 0x1200, 0x10, "0", "0-F"),
        new HandlerFamily(0x004AD300L, 0x0043E590L, 0x0043E600L, 0x0066CC90L, 0x0700, 0x0C, "0,2,4-9", "0,2,4-B"),
        new HandlerFamily(0x004AD31AL, 0x0043F0C0L, 0x0043F130L, 0x0066CD28L, 0x2000, 0x0C, "0,3,5,7-9", "1,2,4,6-8,A-B"),
        new HandlerFamily(0x004AD334L, 0x00407600L, 0x00407670L, 0x0066C204L, 0x1000, 0x09, "0,2,4,6-8", "1,3,5-8"),
        new HandlerFamily(0x004AD34FL, 0x00437E00L, 0x00437E70L, 0x0066CABCL, 0x0A00, 0x0F, "0-7,9-B", "0-7,9-E"),
        new HandlerFamily(0x004AD369L, 0x00447400L, 0x00447470L, 0x0066CE8CL, 0x1100, 0x05, "0,3", "1-4"),
    };

    private static String hex(long value, int width) {
        return String.format(Locale.ROOT, "0x%0" + width + "X", value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] result = digest.digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : result) text.append(String.format(Locale.ROOT, "%02X", value));
        return text.toString();
    }

    private static String normalizeCode(String value) {
        long parsed = value.toLowerCase(Locale.ROOT).startsWith("0x")
            ? Long.parseLong(value.substring(2), 16)
            : Long.parseLong(value, 10);
        return hex(parsed, 4);
    }

    private static List<CaseGroup> parseCaseGroups(String decompile) {
        List<CaseGroup> result = new ArrayList<>();
        CaseGroup pending = null;
        boolean hasBody = false;
        for (String line : decompile.split("\\R", -1)) {
            Matcher matcher = CASE_PATTERN.matcher(line);
            if (matcher.find()) {
                if (pending != null && hasBody) {
                    result.add(pending);
                    pending = null;
                    hasBody = false;
                }
                if (pending == null) pending = new CaseGroup();
                pending.codes.add(normalizeCode(matcher.group(1)));
                continue;
            }
            if (pending != null) {
                pending.body.add(line);
                if (!line.trim().isEmpty() && !line.trim().equals("{")) hasBody = true;
            }
        }
        if (pending != null) result.add(pending);
        return result;
    }

    private String decompile(DecompInterface decompiler, long entry) throws Exception {
        Function function = getFunctionAt(toAddr(entry));
        if (function == null) throw new IllegalStateException("missing function at " + hex(entry, 8));
        DecompileResults result = decompiler.decompileFunction(function, 300, monitor);
        if (!result.decompileCompleted()) {
            throw new IllegalStateException("decompile failed at " + hex(entry, 8) + ": " + result.getErrorMessage());
        }
        return result.getDecompiledFunction().getC();
    }

    private String stringAt(long address) {
        Data data = getDataAt(toAddr(address));
        if (data == null || !data.hasStringValue()) return null;
        Object value = data.getValue();
        return value instanceof String ? (String)value : null;
    }

    private String directMessageName(String body) {
        Matcher matcher = STRING_SYMBOL_PATTERN.matcher(body);
        while (matcher.find()) {
            long address = Long.parseLong(matcher.group(1), 16);
            String value = stringAt(address);
            if (value == null) continue;
            String cleaned = value.replaceFirst("^>+", "").trim().replaceFirst(" OK$", "");
            if (PROTOCOL_STRING_PATTERN.matcher(cleaned).matches()) return cleaned;
        }
        return null;
    }

    private static List<String> helperCalls(String body) {
        Set<String> helpers = new LinkedHashSet<>();
        Matcher matcher = FUNCTION_PATTERN.matcher(body);
        while (matcher.find()) helpers.add(matcher.group().toUpperCase(Locale.ROOT));
        return new ArrayList<>(helpers);
    }

    private static String assignment(String body, String target) {
        Matcher matcher = ASSIGNMENT_PATTERN.matcher(body);
        while (matcher.find()) {
            if (matcher.group(1).replace(" ", "").equals(target.replace(" ", ""))) {
                return matcher.group(2).trim();
            }
        }
        return null;
    }

    private static Long literal(String expression) {
        if (expression == null) return null;
        String value = expression.trim();
        if (value.matches("0x[0-9a-fA-F]+")) return Long.parseLong(value.substring(2), 16);
        if (value.matches("[0-9]+")) return Long.parseLong(value, 10);
        return null;
    }

    private List<Map<String, Object>> parserCases(String decompile) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CaseGroup group : parseCaseGroups(decompile)) {
            String body = String.join("\n", group.body);
            String allocationExpression = assignment(body, "*param_4");
            Long allocationBytes = literal(allocationExpression);
            Map<String, Object> allocation = new LinkedHashMap<>();
            if (allocationBytes != null) {
                allocation.put("status", "FIXED");
                allocation.put("bytes", allocationBytes);
            }
            else if (allocationExpression != null) {
                allocation.put("status", "DYNAMIC");
                allocation.put("bytes", null);
            }
            else if (body.contains("goto switch")) {
                allocation.put("status", "SHARED_CASE");
                allocation.put("bytes", null);
            }
            else {
                allocation.put("status", "UNKNOWN");
                allocation.put("bytes", null);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("codes", group.codes);
            record.put("allocationSize", allocation);
            record.put("allocationExpression", allocationExpression);
            record.put("dynamicSizeExpression", assignment(body, "*param_3"));
            record.put("messageName", directMessageName(body));
            record.put("helperCalls", helperCalls(body));
            record.put("rawCase", body);
            result.add(record);
        }
        return result;
    }

    private static List<Map<String, Object>> conditionCodes(
        String decompile, String variable, List<Map<String, Object>> caseRecords
    ) {
        Set<String> caseCodes = new LinkedHashSet<>();
        for (Map<String, Object> record : caseRecords) {
            @SuppressWarnings("unchecked")
            List<String> codes = (List<String>)record.get("codes");
            caseCodes.addAll(codes);
        }
        Pattern conditionPattern = Pattern.compile(
            "(?m)^\\s*(?:else\\s+)?if\\s*\\([^\\r\\n]*\\b" + Pattern.quote(variable) +
            "\\s*==\\s*(0x[0-9a-fA-F]+|[0-9]+)[^\\r\\n]*"
        );
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        Matcher matcher = conditionPattern.matcher(decompile);
        while (matcher.find()) {
            String code = normalizeCode(matcher.group(1));
            if (caseCodes.contains(code)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("condition", matcher.group().trim());
            item.put("status", "DIRECT_EQUALITY_BRANCH");
            byCode.putIfAbsent(code, item);
        }
        List<Map<String, Object>> result = new ArrayList<>(byCode.values());
        result.sort(Comparator.comparingLong(item -> Long.parseLong(
            String.valueOf(item.get("code")).substring(2), 16
        )));
        return result;
    }

    private static List<String> destinationExpressions(String body) {
        Set<String> result = new LinkedHashSet<>();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.contains("=") || trimmed.startsWith("if ") || trimmed.startsWith("case ")) continue;
            if (trimmed.contains("param_1 +") || trimmed.contains("local_18 +") || trimmed.contains("DAT_")) {
                result.add(trimmed);
            }
        }
        return new ArrayList<>(result);
    }

    private List<Map<String, Object>> dispatcherCases(String decompile) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CaseGroup group : parseCaseGroups(decompile)) {
            String body = String.join("\n", group.body);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("codes", group.codes);
            record.put("messageName", directMessageName(body));
            record.put("destinationExpressions", destinationExpressions(body));
            record.put("helperCalls", helperCalls(body));
            record.put("rawCase", body);
            result.add(record);
        }
        return result;
    }

    private List<Map<String, Object>> outboundCases(String decompile) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CaseGroup group : parseCaseGroups(decompile)) {
            String body = String.join("\n", group.body);
            if (group.codes.size() != 1) throw new IllegalStateException("outbound case aliases are unexpected");
            long switchIndex = Long.parseLong(group.codes.get(0).substring(2), 16);
            String requestExpression = assignment(body, "iVar1");
            String responseExpression = assignment(body, "iVar5");
            Long responseCode = literal(responseExpression);
            Long requestCode = literal(requestExpression);
            if (requestCode == null && "iVar5".equals(requestExpression) && responseCode != null) {
                requestCode = responseCode;
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("switchIndex", hex(switchIndex, 4));
            record.put("localKind", hex(switchIndex + 1, 4));
            record.put("bindingStatus", requestCode == null ? "UNKNOWN" : "DIRECT_LITERAL");
            record.put("requestCode", requestCode == null ? null : hex(requestCode, 4));
            record.put("expectedResponseCode", responseCode == null ? null : hex(responseCode, 4));
            record.put("requestExpression", requestExpression);
            record.put("expectedResponseExpression", responseExpression);
            record.put("gateExpressions", destinationExpressions(body));
            record.put("rawCase", body);
            result.add(record);
        }
        return result;
    }

    private List<Map<String, Object>> xrefs(Address address) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Reference reference : getReferencesTo(address)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("from", reference.getFromAddress().toString());
            item.put("type", reference.getReferenceType().toString());
            Function function = getFunctionContaining(reference.getFromAddress());
            item.put("functionStatus", function == null ? "UNDEFINED_FUNCTION" : "DEFINED_FUNCTION");
            item.put("function", function == null ? null : function.getName().toUpperCase(Locale.ROOT));
            item.put("functionEntry", function == null ? null : function.getEntryPoint().toString());
            if (function == null) {
                Function prior = getFunctionBefore(reference.getFromAddress());
                item.put("nearestPriorFunction", prior == null ? null : prior.getName().toUpperCase(Locale.ROOT));
                item.put("nearestPriorFunctionEntry", prior == null ? null : prior.getEntryPoint().toString());
            }
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("from"))));
        return result;
    }

    private List<Map<String, Object>> protocolStrings() throws Exception {
        Map<String, Map<String, Object>> byAddress = new LinkedHashMap<>();
        for (Data data : currentProgram.getListing().getDefinedData(true)) {
            if (!data.hasStringValue() || !(data.getValue() instanceof String)) continue;
            String value = ((String)data.getValue()).stripTrailing();
            if (!PROTOCOL_STRING_PATTERN.matcher(value).matches()) continue;
            byAddress.put(data.getAddress().toString(), protocolStringRecord(
                data.getAddress(), value, "DEFINED_STRING"
            ));
        }
        Memory memory = currentProgram.getMemory();
        for (MemoryBlock block : memory.getBlocks()) {
            if (!block.isInitialized() || block.getSize() > Integer.MAX_VALUE) continue;
            byte[] bytes = new byte[(int)block.getSize()];
            int read = memory.getBytes(block.getStart(), bytes);
            int index = 0;
            while (index < read) {
                int start = index;
                while (index < read && bytes[index] >= 0x20 && bytes[index] <= 0x7e) index++;
                if (index < read && bytes[index] == 0 && index - start >= 2) {
                    String value = new String(bytes, start, index - start, StandardCharsets.US_ASCII).stripTrailing();
                    if (value.length() >= 9 && PROTOCOL_STRING_PATTERN.matcher(value).matches()) {
                        Address address = block.getStart().add(start);
                        byAddress.putIfAbsent(address.toString(), protocolStringRecord(
                            address, value, "RAW_MEMORY_ASCII"
                        ));
                    }
                }
                index = index == start ? index + 1 : index + 1;
            }
        }
        List<Map<String, Object>> result = new ArrayList<>(byAddress.values());
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("address"))));
        return result;
    }

    private Map<String, Object> protocolStringRecord(Address address, String value, String discovery) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("address", address.toString());
        item.put("rva", hex(address.subtract(currentProgram.getImageBase()), 8));
        item.put("value", value);
        item.put("status", value.endsWith(" OK") ? "STATUS_LOG" : "NAME_CANDIDATE");
        item.put("discovery", discovery);
        item.put("xrefs", xrefs(address));
        return item;
    }

    private List<Map<String, Object>> streamContracts() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Data data : currentProgram.getListing().getDefinedData(true)) {
            if (!data.hasStringValue() || !(data.getValue() instanceof String)) continue;
            String value = (String)data.getValue();
            String normalizedValue = value.stripTrailing();
            Matcher matcher = STREAM_PATTERN.matcher(normalizedValue);
            if (!matcher.matches()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("address", data.getAddress().toString());
            item.put("rva", hex(data.getAddress().subtract(currentProgram.getImageBase()), 8));
            item.put("directionLabel", matcher.group(1));
            item.put("message", matcher.group(2));
            item.put("method", matcher.group(3));
            item.put("field", matcher.group(4));
            item.put("maxCountOrBytes", matcher.group(5).equals("%d") ? null : Long.parseLong(matcher.group(5)));
            item.put("maxExpression", matcher.group(5));
            item.put("limitStatus", matcher.group(5).equals("%d") ? "DYNAMIC" : "FIXED_CAP");
            item.put("measurementKind", "ARRAY_CAP");
            item.put("value", normalizedValue);
            item.put("xrefs", xrefs(data.getAddress()));
            result.add(item);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("address"))));
        return result;
    }

    private List<Map<String, Object>> functionInstructions(long entry) {
        Function function = getFunctionAt(toAddr(entry));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("address", instruction.getAddress().toString());
            item.put("text", instruction.toString());
            List<String> scalars = new ArrayList<>();
            for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                for (Object object : instruction.getOpObjects(operand)) {
                    if (object instanceof Scalar) {
                        scalars.add(hex(((Scalar)object).getUnsignedValue(), 1));
                    }
                }
            }
            item.put("scalars", scalars);
            List<String> flows = new ArrayList<>();
            for (Address flow : instruction.getFlows()) flows.add(flow.toString());
            item.put("flows", flows);
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> functionGraph(long entry) {
        Function function = getFunctionAt(toAddr(entry));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", function.getName().toUpperCase(Locale.ROOT));
        result.put("entry", function.getEntryPoint().toString());
        List<String> callers = new ArrayList<>();
        for (Reference reference : getReferencesTo(function.getEntryPoint())) {
            Function caller = getFunctionContaining(reference.getFromAddress());
            callers.add(reference.getFromAddress() + "|" + (caller == null ? "UNDEFINED_FUNCTION" : caller.getName()));
        }
        Collections.sort(callers);
        result.put("callers", callers);
        Set<String> callees = new LinkedHashSet<>();
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            for (Address flow : instruction.getFlows()) {
                Function callee = getFunctionAt(flow);
                if (callee != null) callees.add(callee.getName().toUpperCase(Locale.ROOT) + "@" + flow);
            }
        }
        List<String> sortedCallees = new ArrayList<>(callees);
        Collections.sort(sortedCallees);
        result.put("callees", sortedCallees);
        return result;
    }

    private long pointerAt(long address) throws Exception {
        return Integer.toUnsignedLong(currentProgram.getMemory().getInt(toAddr(address)));
    }

    private void requireEntryScalar(long entry, long expected, String label) {
        Instruction instruction = getInstructionAt(toAddr(entry));
        if (instruction == null) throw new IllegalStateException("missing " + label + " instruction");
        for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
            for (Object object : instruction.getOpObjects(operand)) {
                if (object instanceof Scalar && ((Scalar)object).getUnsignedValue() == expected) return;
            }
        }
        throw new IllegalStateException(label + " does not return expected scalar " + hex(expected, 1));
    }

    private void requireDirectCall(long callsite, long target, String label) {
        Instruction instruction = getInstructionAt(toAddr(callsite));
        if (instruction == null || !instruction.getMnemonicString().equalsIgnoreCase("CALL")) {
            throw new IllegalStateException("missing " + label + " call at " + hex(callsite, 8));
        }
        for (Address flow : instruction.getFlows()) {
            if (flow.getOffset() == target) return;
        }
        throw new IllegalStateException(label + " call target mismatch at " + hex(callsite, 8));
    }

    private Long directCallTarget(Instruction instruction) {
        if (instruction == null || !instruction.getMnemonicString().equalsIgnoreCase("CALL")) return null;
        Address[] flows = instruction.getFlows();
        return flows.length == 1 ? flows[0].getOffset() : null;
    }

    private long returnedEntryScalar(long entry, String label) {
        Instruction instruction = getInstructionAt(toAddr(entry));
        if (instruction == null) throw new IllegalStateException("missing " + label + " instruction");
        for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
            for (Object object : instruction.getOpObjects(operand)) {
                if (object instanceof Scalar) return ((Scalar)object).getUnsignedValue();
            }
        }
        throw new IllegalStateException(label + " does not return a scalar at entry");
    }

    private boolean isHandlerVtable(long candidate) {
        try {
            long baseCode = returnedEntryScalar(pointerAt(candidate + 0x10), "candidate base-code function");
            long count = returnedEntryScalar(pointerAt(candidate + 0x14), "candidate count function");
            return baseCode <= 0xffff && count > 0 && count <= 0x100;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private long deriveTopVtable(long constructor) {
        Function function = getFunctionAt(toAddr(constructor));
        if (function == null) throw new IllegalStateException("missing Message32 constructor");
        Set<Long> candidates = new LinkedHashSet<>();
        Map<String, Long> aliases = new LinkedHashMap<>();
        aliases.put("ECX", 0L);
        Pattern registerMove = Pattern.compile("^MOV([A-Z]{3}),([A-Z]{3})$");
        Pattern zeroOffsetStore = Pattern.compile("^MOVDWORDPTR\\[([A-Z]{3})\\],.*$");
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            String compact = instruction.toString().toUpperCase(Locale.ROOT).replace(" ", "");
            Matcher move = registerMove.matcher(compact);
            if (move.matches()) {
                Long value = aliases.get(move.group(2));
                if (value == null) aliases.remove(move.group(1));
                else aliases.put(move.group(1), value);
                continue;
            }
            Matcher store = zeroOffsetStore.matcher(compact);
            if (!store.matches() || !Long.valueOf(0).equals(aliases.get(store.group(1)))) continue;
            for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                for (Object object : instruction.getOpObjects(operand)) {
                    if (!(object instanceof Scalar)) continue;
                    long scalar = ((Scalar)object).getUnsignedValue();
                    if (isHandlerVtable(scalar)) candidates.add(scalar);
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException("constructor does not expose exactly one top handler vtable: " + candidates);
        }
        return candidates.iterator().next();
    }

    private long deriveFactoryConstructor(long factory) {
        Function function = getFunctionAt(toAddr(factory));
        List<Long> candidates = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            Long target = directCallTarget(instruction);
            if (target == null) continue;
            try {
                deriveTopVtable(target);
                candidates.add(target);
            }
            catch (IllegalStateException error) {
                rejected.add(hex(target, 8) + ":" + error.getMessage());
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException(
                "factory does not expose exactly one Message32 constructor: candidates=" + candidates +
                " rejected=" + rejected
            );
        }
        return candidates.get(0);
    }

    private long deriveRegistryVtable() throws Exception {
        Function factory = getFunctionAt(toAddr(MESSAGE32_SYSTEM_FACTORY));
        Set<Long> candidates = new LinkedHashSet<>();
        for (Instruction call : currentProgram.getListing().getInstructions(factory.getBody(), true)) {
            Long target = directCallTarget(call);
            Function constructor = target == null ? null : getFunctionAt(toAddr(target));
            if (constructor == null) continue;
            for (Instruction instruction : currentProgram.getListing().getInstructions(constructor.getBody(), true)) {
                if (!instruction.getMnemonicString().equalsIgnoreCase("MOV")) continue;
                for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                    for (Object object : instruction.getOpObjects(operand)) {
                        if (!(object instanceof Scalar)) continue;
                        long vtable = ((Scalar)object).getUnsignedValue();
                        try {
                            if (pointerAt(vtable + 4) == MESSAGE32_REGISTRY) candidates.add(vtable);
                        }
                        catch (Exception ignored) {
                            // Non-pointer immediates are not vtable candidates.
                        }
                    }
                }
            }
        }
        if (candidates.size() != 1) {
            throw new IllegalStateException("could not derive unique Message32 registry vtable: " + candidates);
        }
        return candidates.iterator().next();
    }

    private List<HandlerFamily> deriveMessage32Families() throws Exception {
        if (derivedMessage32Families != null) return derivedMessage32Families;
        long registryVtable = deriveRegistryVtable();
        if (pointerAt(registryVtable + 4) != MESSAGE32_REGISTRY) {
            throw new IllegalStateException("Message32 registry vslot mismatch");
        }
        Function startup = getFunctionAt(toAddr(MESSAGE32_STARTUP));
        List<Instruction> instructions = new ArrayList<>();
        for (Instruction instruction : currentProgram.getListing().getInstructions(startup.getBody(), true)) {
            instructions.add(instruction);
        }
        List<long[]> registrations = new ArrayList<>();
        Pattern registryCall = Pattern.compile(
            "^CALL\\s+DWORD PTR \\[ESI\\s*\\+\\s*(?:0x)?4\\]$", Pattern.CASE_INSENSITIVE
        );
        for (int index = 0; index < instructions.size(); index++) {
            Instruction factoryCall = instructions.get(index);
            Long factory = directCallTarget(factoryCall);
            if (factory == null) continue;
            boolean pushesFactoryResult = false;
            Long registryCallsite = null;
            for (int cursor = index + 1; cursor < instructions.size() && cursor <= index + 6; cursor++) {
                Instruction next = instructions.get(cursor);
                if (directCallTarget(next) != null) break;
                String text = next.toString().toUpperCase(Locale.ROOT);
                if (text.equals("PUSH EAX")) pushesFactoryResult = true;
                if (pushesFactoryResult && registryCall.matcher(text).matches()) {
                    registryCallsite = next.getAddress().getOffset();
                    break;
                }
            }
            if (registryCallsite != null) {
                registrations.add(new long[] {
                    factoryCall.getAddress().getOffset(), registryCallsite, factory
                });
            }
        }
        if (registrations.size() != MESSAGE32_FAMILIES.length) {
            throw new IllegalStateException("startup Message32 registration count mismatch: " + registrations.size());
        }
        Map<Long, HandlerFamily> annotations = new LinkedHashMap<>();
        for (HandlerFamily annotation : MESSAGE32_FAMILIES) {
            annotations.put(annotation.registrationCallsite, annotation);
        }
        List<HandlerFamily> result = new ArrayList<>();
        for (long[] registration : registrations) {
            HandlerFamily annotation = annotations.remove(registration[0]);
            if (annotation == null || annotation.factory != registration[2]) {
                throw new IllegalStateException("unannotated or mismatched startup Message32 registration");
            }
            long constructor = deriveFactoryConstructor(registration[2]);
            long vtable = deriveTopVtable(constructor);
            long baseCode = returnedEntryScalar(pointerAt(vtable + 0x10), "Message32 base-code function");
            long count = returnedEntryScalar(pointerAt(vtable + 0x14), "Message32 count function");
            if (constructor != annotation.constructor || vtable != annotation.vtable ||
                baseCode != annotation.baseCode || count != annotation.count) {
                throw new IllegalStateException("derived Message32 family differs from annotation");
            }
            result.add(new HandlerFamily(
                registration[0], registration[1], registration[2], constructor, vtable, baseCode, count,
                annotation.clientToServerOffsets, annotation.serverToClientOffsets
            ));
        }
        if (!annotations.isEmpty()) {
            throw new IllegalStateException("annotated Message32 registration absent from startup: " + annotations.keySet());
        }
        derivedMessage32Families = result;
        return result;
    }

    private void requireConstructorVtable(HandlerFamily family) {
        Function function = getFunctionAt(toAddr(family.constructor));
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            if (!instruction.getMnemonicString().equalsIgnoreCase("MOV")) continue;
            for (int operand = 0; operand < instruction.getNumOperands(); operand++) {
                for (Object object : instruction.getOpObjects(operand)) {
                    if (object instanceof Scalar && ((Scalar)object).getUnsignedValue() == family.vtable) return;
                }
            }
        }
        throw new IllegalStateException("constructor does not assign expected top vtable");
    }

    private long lookupArrayBase(long lookupFunction) {
        Function function = getFunctionAt(toAddr(lookupFunction));
        Pattern indexed = Pattern.compile(
            "\\[ECX\\s*\\+\\s*EDX\\*0x4\\s*\\+\\s*(0x[0-9A-F]+|[0-9]+)\\]",
            Pattern.CASE_INSENSITIVE
        );
        for (Instruction instruction : currentProgram.getListing().getInstructions(function.getBody(), true)) {
            Matcher matcher = indexed.matcher(instruction.toString());
            if (matcher.find()) return Long.decode(matcher.group(1));
        }
        throw new IllegalStateException("lookup function does not expose an indexed pointer array");
    }

    private Map<Long, String> deriveDirectionAssignments(
        HandlerFamily family, long lookupFunction, String direction
    ) {
        long arrayBase = lookupArrayBase(lookupFunction);
        Map<String, Long> symbolicThisOffsets = new LinkedHashMap<>();
        symbolicThisOffsets.put("ECX", 0L);
        Map<Long, String> result = new LinkedHashMap<>();
        Pattern registerMove = Pattern.compile(
            "^MOV\\s+([A-Z]{3}),([A-Z]{3})$", Pattern.CASE_INSENSITIVE
        );
        Pattern registerLea = Pattern.compile(
            "^LEA\\s+([A-Z]{3}),\\[([A-Z]{3})(?:\\s*\\+\\s*(0x[0-9A-F]+|[0-9]+))?\\]$",
            Pattern.CASE_INSENSITIVE
        );
        Pattern pointerStore = Pattern.compile(
            "^MOV\\s+DWORD PTR \\[([A-Z]{3})(?:\\s*\\+\\s*(0x[0-9A-F]+|[0-9]+))?\\],([A-Z]{3})$",
            Pattern.CASE_INSENSITIVE
        );
        Pattern registerWrite = Pattern.compile(
            "^(?:MOV|LEA|XOR|POP|ADD|SUB|AND|OR)\\s+([A-Z]{3})(?:,.*)?$",
            Pattern.CASE_INSENSITIVE
        );
        Function constructor = getFunctionAt(toAddr(family.constructor));
        for (Instruction instruction : currentProgram.getListing().getInstructions(constructor.getBody(), true)) {
            String text = instruction.toString().toUpperCase(Locale.ROOT);
            Matcher store = pointerStore.matcher(text);
            if (store.matches()) {
                Long base = symbolicThisOffsets.get(store.group(1));
                Long value = symbolicThisOffsets.get(store.group(3));
                long displacement = store.group(2) == null ? 0 : Long.decode(store.group(2));
                if (base != null && value != null) {
                    long destination = base + displacement;
                    long relative = destination - arrayBase;
                    if (relative >= 0 && relative < family.count * 4 && relative % 4 == 0) {
                        long offset = relative / 4;
                        if (result.put(offset, instruction.getAddress().toString()) != null) {
                            throw new IllegalStateException(
                                "duplicate Message32 array-slot assignment base=" +
                                hex(family.baseCode, 4) + " direction=" + direction +
                                " offset=" + offset
                            );
                        }
                    }
                }
            }
            Matcher move = registerMove.matcher(text);
            if (move.matches()) {
                Long value = symbolicThisOffsets.get(move.group(2));
                if (value == null) symbolicThisOffsets.remove(move.group(1));
                else symbolicThisOffsets.put(move.group(1), value);
                continue;
            }
            Matcher lea = registerLea.matcher(text);
            if (lea.matches()) {
                Long base = symbolicThisOffsets.get(lea.group(2));
                long displacement = lea.group(3) == null ? 0 : Long.decode(lea.group(3));
                if (base == null) symbolicThisOffsets.remove(lea.group(1));
                else symbolicThisOffsets.put(lea.group(1), base + displacement);
                continue;
            }
            Matcher write = registerWrite.matcher(text);
            if (write.matches()) symbolicThisOffsets.remove(write.group(1));
            if (instruction.getMnemonicString().equalsIgnoreCase("CALL")) {
                symbolicThisOffsets.remove("EAX");
                symbolicThisOffsets.remove("ECX");
                symbolicThisOffsets.remove("EDX");
            }
        }
        Set<Long> expected = direction.equals("CLIENT_TO_SERVER")
            ? family.clientToServerOffsets : family.serverToClientOffsets;
        if (!result.keySet().equals(expected)) {
            throw new IllegalStateException(
                "derived Message32 " + direction + " slots differ for base " + hex(family.baseCode, 4) +
                ": derived=" + result.keySet() + " expected=" + expected
            );
        }
        return result;
    }

    private String functionInstructionSha256(long entry) throws Exception {
        return sha256(new Gson().toJson(functionInstructions(entry)).getBytes(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> message32HandlerFamilies() throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (HandlerFamily family : deriveMessage32Families()) {
            requireDirectCall(family.registrationCallsite, family.factory, "Message32 factory");
            Function factoryFunction = getFunctionAt(toAddr(family.factory));
            boolean constructorCalled = false;
            for (Instruction instruction : currentProgram.getListing().getInstructions(factoryFunction.getBody(), true)) {
                for (Address flow : instruction.getFlows()) {
                    if (instruction.getMnemonicString().equalsIgnoreCase("CALL") &&
                        flow.getOffset() == family.constructor) constructorCalled = true;
                }
            }
            if (!constructorCalled) throw new IllegalStateException("Message32 factory does not call constructor");
            requireConstructorVtable(family);
            long maxLengthFunction = pointerAt(family.vtable + 0x0c);
            long baseCodeFunction = pointerAt(family.vtable + 0x10);
            long countFunction = pointerAt(family.vtable + 0x14);
            long clientToServerLookup = pointerAt(family.vtable + 0x1c);
            long serverToClientLookup = pointerAt(family.vtable + 0x24);
            requireEntryScalar(baseCodeFunction, family.baseCode, "Message32 base-code function");
            requireEntryScalar(countFunction, family.count, "Message32 count function");
            Map<Long, String> clientToServerAssignments = deriveDirectionAssignments(
                family, clientToServerLookup, "CLIENT_TO_SERVER"
            );
            Map<Long, String> serverToClientAssignments = deriveDirectionAssignments(
                family, serverToClientLookup, "SERVER_TO_CLIENT"
            );
            Set<Long> union = new LinkedHashSet<>(clientToServerAssignments.keySet());
            union.addAll(serverToClientAssignments.keySet());
            for (long offset : union) {
                if (offset < 0 || offset >= family.count) {
                    throw new IllegalStateException("Message32 registered offset outside family range");
                }
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("registrationCallsite", hex(family.registrationCallsite, 8));
            item.put("registryCallsite", hex(family.registryCallsite, 8));
            item.put("factory", hex(family.factory, 8));
            item.put("constructor", hex(family.constructor, 8));
            item.put("constructorInstructionSha256", functionInstructionSha256(family.constructor));
            item.put("vtable", hex(family.vtable, 8));
            item.put("baseCode", hex(family.baseCode, 4));
            item.put("count", family.count);
            item.put("maxLengthFunction", hex(maxLengthFunction, 8));
            item.put("baseCodeFunction", hex(baseCodeFunction, 8));
            item.put("countFunction", hex(countFunction, 8));
            item.put("clientToServerLookup", hex(clientToServerLookup, 8));
            item.put("serverToClientLookup", hex(serverToClientLookup, 8));
            item.put("clientToServerArrayBase", hex(lookupArrayBase(clientToServerLookup), 1));
            item.put("serverToClientArrayBase", hex(lookupArrayBase(serverToClientLookup), 1));
            item.put("clientToServerCount", clientToServerAssignments.size());
            item.put("serverToClientCount", serverToClientAssignments.size());
            item.put("unionCount", union.size());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> message32HandlerCodes() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<HandlerFamily> families = deriveMessage32Families();
            for (int familyIndex = 0; familyIndex < families.size(); familyIndex++) {
                HandlerFamily family = families.get(familyIndex);
                long clientToServerLookup = pointerAt(family.vtable + 0x1c);
                long serverToClientLookup = pointerAt(family.vtable + 0x24);
                Map<Long, String> clientToServerAssignments = deriveDirectionAssignments(
                    family, clientToServerLookup, "CLIENT_TO_SERVER"
                );
                Map<Long, String> serverToClientAssignments = deriveDirectionAssignments(
                    family, serverToClientLookup, "SERVER_TO_CLIENT"
                );
                Set<Long> union = new LinkedHashSet<>(clientToServerAssignments.keySet());
                union.addAll(serverToClientAssignments.keySet());
            List<Long> offsets = new ArrayList<>(union);
            Collections.sort(offsets);
            for (long offset : offsets) {
                boolean clientToServer = clientToServerAssignments.containsKey(offset);
                boolean serverToClient = serverToClientAssignments.containsKey(offset);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", hex(family.baseCode + offset, 4));
                item.put("familyIndex", familyIndex);
                item.put("offset", offset);
                item.put("direction", clientToServer && serverToClient ? "BIDIRECTIONAL" :
                    clientToServer ? "CLIENT_TO_SERVER" : "SERVER_TO_CLIENT");
                item.put("clientToServerRegistered", clientToServer);
                item.put("serverToClientRegistered", serverToClient);
                item.put("clientToServerSlot", clientToServer ? offset + 1 : null);
                item.put("serverToClientSlot", serverToClient ? family.count + offset + 1 : null);
                item.put("clientToServerAssignment", clientToServerAssignments.get(offset));
                item.put("serverToClientAssignment", serverToClientAssignments.get(offset));
                item.put("factory", hex(family.factory, 8));
                item.put("constructor", hex(family.constructor, 8));
                item.put("vtable", hex(family.vtable, 8));
                result.add(item);
            }
            }
        }
        catch (Exception error) {
            throw new IllegalStateException("failed to derive Message32 family", error);
        }
        result.sort(Comparator.comparing(item -> String.valueOf(item.get("code"))));
        long clientToServerCount = result.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("clientToServerRegistered"))).count();
        long serverToClientCount = result.stream()
            .filter(item -> Boolean.TRUE.equals(item.get("serverToClientRegistered"))).count();
        long bidirectionalCount = result.stream()
            .filter(item -> "BIDIRECTIONAL".equals(item.get("direction"))).count();
        if (result.size() != 302 || clientToServerCount != 161 ||
            serverToClientCount != 243 || bidirectionalCount != 102) {
            throw new IllegalStateException("Message32 registry conservation mismatch");
        }
        return result;
    }

    private Map<String, Object> message32Framework() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "REGISTERED_AT_SHIPPED_STARTUP_PATH");
        result.put("containerName", "mpsCTMsg32ParseSystem");
        result.put("messageTypeWidthBits", 16);
        result.put("startupFunction", hex(MESSAGE32_STARTUP, 8));
        result.put("registryFunction", hex(MESSAGE32_REGISTRY, 8));
        result.put("produceFunction", hex(MESSAGE32_PRODUCE, 8));
        result.put("sendFunction", hex(MESSAGE32_SEND, 8));
        result.put("parseFunction", hex(MESSAGE32_PARSE, 8));
        result.put("messageInputFunction", hex(MESSAGE32_INPUT, 8));
        result.put("frameworkStrings", List.of(
            hex(0x0075EA50L, 8), hex(0x0075EA94L, 8), hex(0x0075EAD4L, 8),
            hex(0x0075EB24L, 8), hex(0x0075EB78L, 8), hex(0x0075EBB4L, 8)
        ));
        result.put("opcodeWidthDisposition", "UINT16_MESSAGE_TYPE_IN_MESSAGE32_CONTAINER");
        return result;
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 4) {
            throw new IllegalArgumentException(
                "usage: <output> <expected-executable-sha256> <exporter-sha256> <ghidra-repository-sha256>"
            );
        }
        String actualExecutableSha = currentProgram.getExecutableSHA256().toUpperCase(Locale.ROOT);
        if (!actualExecutableSha.equals(args[1].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("program executable SHA-256 mismatch");
        }

        DecompInterface decompiler = new DecompInterface();
        decompiler.openProgram(currentProgram);
        String parserText;
        String dispatcherText;
        String outboundText;
        try {
            parserText = decompile(decompiler, PARSER);
            dispatcherText = decompile(decompiler, DISPATCHER);
            outboundText = decompile(decompiler, OUTBOUND);
        }
        finally {
            decompiler.dispose();
        }

        List<Map<String, Object>> names = protocolStrings();
        List<Map<String, Object>> streams = streamContracts();
        List<Map<String, Object>> message32Families = message32HandlerFamilies();
        List<Map<String, Object>> message32Codes = message32HandlerCodes();
        String surfaceMaterial = parserText + "\n" + dispatcherText + "\n" + outboundText + "\n" +
            new Gson().toJson(names) + "\n" + new Gson().toJson(streams) + "\n" +
            new Gson().toJson(message32Families) + "\n" + new Gson().toJson(message32Codes);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("program", currentProgram.getName());
        source.put("executableSha256", actualExecutableSha);
        source.put("language", currentProgram.getLanguageID().toString());
        source.put("compiler", currentProgram.getCompilerSpec().getCompilerSpecID().toString());
        source.put("imageBase", currentProgram.getImageBase().toString());
        root.put("source", source);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("class", getClass().getSimpleName());
        exporter.put("sha256", args[2].toUpperCase(Locale.ROOT));
        exporter.put("ghidraRepositorySha256", args[3].toUpperCase(Locale.ROOT));
        root.put("exporter", exporter);
        root.put("surfaceSha256", sha256(surfaceMaterial.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> functions = new LinkedHashMap<>();
        functions.put("parser", "FUN_004B8B00");
        functions.put("dispatcher", "FUN_004BA2B0");
        functions.put("outbound", "FUN_004B78A0");
        root.put("functions", functions);
        List<Map<String, Object>> parserCaseRecords = parserCases(parserText);
        List<Map<String, Object>> dispatcherCaseRecords = dispatcherCases(dispatcherText);
        root.put("parserCases", parserCaseRecords);
        root.put("parserConditionCodes", conditionCodes(parserText, "param_1", parserCaseRecords));
        root.put("dispatcherCases", dispatcherCaseRecords);
        root.put("dispatcherConditionCodes", conditionCodes(dispatcherText, "local_3c", dispatcherCaseRecords));
        root.put("outboundCases", outboundCases(outboundText));
        root.put("message32Framework", message32Framework());
        root.put("message32HandlerFamilies", message32Families);
        root.put("message32HandlerCodes", message32Codes);
        root.put("protocolStrings", names);
        root.put("streamContracts", streams);
        Map<String, Object> graphs = new LinkedHashMap<>();
        graphs.put("FUN_004B8B00", functionGraph(PARSER));
        graphs.put("FUN_004BA2B0", functionGraph(DISPATCHER));
        graphs.put("FUN_004B78A0", functionGraph(OUTBOUND));
        root.put("functionGraphs", graphs);
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("FUN_004B8B00", functionInstructions(PARSER));
        instructions.put("FUN_004BA2B0", functionInstructions(DISPATCHER));
        instructions.put("FUN_004B78A0", functionInstructions(OUTBOUND));
        root.put("functionInstructions", instructions);
        root.put("successMarker", "EXPORT_EXHAUSTIVE_PROTOCOL_OK");

        File output = new File(args[0]);
        if (output.getParentFile() == null || !output.getParentFile().isDirectory()) {
            throw new IllegalStateException("output directory does not exist: " + output.getParent());
        }
        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        try (PrintWriter writer = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8)
        )) {
            gson.toJson(root, writer);
            writer.println();
        }
        println("EXPORT_EXHAUSTIVE_PROTOCOL_OK output=" + output.getAbsolutePath());
    }
}
