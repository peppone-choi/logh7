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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;

public class ExportExhaustiveResources extends GhidraScript {
    private static final int EXPECTED_TREE_FILES = 2192;
    private static final int EXPECTED_RAW_RESOURCE_STRINGS = 806;
    private static final int EXPECTED_RAW_POINTER_CELLS = 924;

    private static final class ResourceString {
        final Address address;
        final String value;
        final String normalized;
        final List<String> matchedPaths;
        final String candidateId;

        ResourceString(Address address, String value, String normalized, List<String> matchedPaths) {
            this.address = address;
            this.value = value;
            this.normalized = normalized;
            this.matchedPaths = matchedPaths;
            this.candidateId = (value.contains("%") ? "RESOURCE_FORMATTER:" : "RESOURCE_LITERAL:")
                + address.toString().toUpperCase(Locale.ROOT);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest(bytes)) result.append(String.format(Locale.ROOT, "%02X", value));
        return result.toString();
    }

    private static boolean printable(int value) {
        return value >= 0x20 && value <= 0x7e;
    }

    private static String normalizePath(String value) {
        String result = value.replace('\\', '/');
        int data = result.toLowerCase(Locale.ROOT).indexOf("data/");
        if (data >= 0) result = result.substring(data);
        while (result.startsWith("/")) result = result.substring(1);
        return result;
    }

    private static Map<String, String> readTreeManifest(File file) throws Exception {
        Map<String, String> paths = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            if (line.length() < 67 || !line.substring(64, 66).equals(" *")) {
                throw new IllegalStateException("invalid tree manifest line");
            }
            String path = line.substring(66).replace('\\', '/');
            if (!path.startsWith("LOGH7/")) throw new IllegalStateException("tree path prefix mismatch");
            path = path.substring("LOGH7/".length());
            String folded = path.toLowerCase(Locale.ROOT);
            if (paths.put(folded, path) != null) throw new IllegalStateException("casefold tree path collision");
        }
        if (paths.size() != EXPECTED_TREE_FILES) throw new IllegalStateException("tree file conservation mismatch");
        return paths;
    }

    private List<ResourceString> scanResourceStrings(Map<String, String> treePaths) throws Exception {
        List<ResourceString> result = new ArrayList<>();
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.getSize() > Integer.MAX_VALUE) continue;
            byte[] bytes = new byte[(int) block.getSize()];
            block.getBytes(block.getStart(), bytes);
            int index = 0;
            while (index < bytes.length) {
                int start = index;
                while (index < bytes.length && printable(bytes[index] & 0xff)) index++;
                int length = index - start;
                if (length >= 4) {
                    String value = new String(bytes, start, length, StandardCharsets.US_ASCII);
                    String lower = value.toLowerCase(Locale.ROOT);
                    if (lower.contains("data/") || lower.contains("data\\")) {
                        String normalized = normalizePath(value);
                        List<String> matched = new ArrayList<>();
                        String exact = treePaths.get(normalized.toLowerCase(Locale.ROOT));
                        if (exact != null) matched.add(exact);
                        result.add(new ResourceString(block.getStart().add(start), value, normalized, matched));
                    }
                }
                if (length == 0) index++;
            }
        }
        result.sort(Comparator.comparing(item -> item.address));
        if (result.size() != EXPECTED_RAW_RESOURCE_STRINGS) {
            throw new IllegalStateException("raw resource string conservation mismatch: " + result.size());
        }
        return result;
    }

    private String containingFunction(Address address) {
        Function function = getFunctionContaining(address);
        return function == null ? null : function.getName().toUpperCase(Locale.ROOT);
    }

    private List<Map<String, Object>> pathCandidates(List<ResourceString> strings, boolean formatters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ResourceString item : strings) {
            if (item.value.contains("%") != formatters) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidateId", item.candidateId);
            row.put("address", item.address.toString().toUpperCase(Locale.ROOT));
            if (formatters) {
                row.put("template", item.normalized);
                row.put("function", containingFunction(item.address));
                row.put("argumentDomain", "UNKNOWN");
                row.put("status", "UNRESOLVED");
                row.put("firstMissingBoundary", "ARGUMENT_DOMAIN");
            } else {
                row.put("value", item.value);
                row.put("normalizedValue", item.normalized);
                row.put("status", item.matchedPaths.isEmpty() ? "CANDIDATE" : "EXACT_PATH_MATCH");
            }
            row.put("matchedPaths", item.matchedPaths);
            row.put("evidence", List.of("ghidra:raw-string:" + item.address.toString().toUpperCase(Locale.ROOT)));
            result.add(row);
        }
        return result;
    }

    private List<Map<String, Object>> loaderCandidates(List<ResourceString> strings) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (ResourceString item : strings) {
            for (Reference reference : getReferencesTo(item.address)) {
                String id = "RESOURCE_XREF:" + item.address.toString().toUpperCase(Locale.ROOT) + ":"
                    + reference.getFromAddress().toString().toUpperCase(Locale.ROOT);
                if (!ids.add(id)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidateId", id);
                if (item.matchedPaths.size() == 1) row.put("resourcePath", item.matchedPaths.get(0));
                row.put("pathCandidateIds", List.of(item.candidateId));
                row.put("status", "CANDIDATE");
                String function = containingFunction(reference.getFromAddress());
                row.put("functions", function == null ? new ArrayList<>() : List.of(function));
                row.put("api", "UNKNOWN");
                row.put("acceptedFormats", new ArrayList<>());
                row.put("referenceKind", reference.getReferenceType().toString());
                row.put("evidence", List.of("ghidra:xref:" + reference.getFromAddress().toString().toUpperCase(Locale.ROOT)));
                result.add(row);
            }
        }

        Set<Long> starts = new LinkedHashSet<>();
        Map<Long, ResourceString> byAddress = new LinkedHashMap<>();
        for (ResourceString item : strings) {
            starts.add(item.address.getOffset());
            byAddress.put(item.address.getOffset(), item);
        }
        int pointerCount = 0;
        for (MemoryBlock block : currentProgram.getMemory().getBlocks()) {
            if (!block.isInitialized() || block.getSize() > Integer.MAX_VALUE) continue;
            byte[] bytes = new byte[(int) block.getSize()];
            block.getBytes(block.getStart(), bytes);
            long base = block.getStart().getOffset();
            int first = (int)((4 - (base & 3)) & 3);
            for (int offset = first; offset + 4 <= bytes.length; offset += 4) {
                long value = (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                    | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
                if (!starts.contains(value)) continue;
                pointerCount++;
                ResourceString item = byAddress.get(value);
                Address cell = block.getStart().add(offset);
                String id = "RESOURCE_POINTER:" + cell.toString().toUpperCase(Locale.ROOT);
                if (!ids.add(id)) throw new IllegalStateException("duplicate resource pointer candidate");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("candidateId", id);
                if (item.matchedPaths.size() == 1) row.put("resourcePath", item.matchedPaths.get(0));
                row.put("pathCandidateIds", List.of(item.candidateId));
                row.put("status", "CANDIDATE");
                String function = containingFunction(cell);
                row.put("functions", function == null ? new ArrayList<>() : List.of(function));
                row.put("api", "UNKNOWN");
                row.put("acceptedFormats", new ArrayList<>());
                row.put("referenceKind", "INITIALIZED_DWORD_POINTER");
                row.put("evidence", List.of("ghidra:pointer-cell:" + cell.toString().toUpperCase(Locale.ROOT)));
                result.add(row);
            }
        }
        if (pointerCount != EXPECTED_RAW_POINTER_CELLS) {
            throw new IllegalStateException("raw resource pointer conservation mismatch: " + pointerCount);
        }
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("candidateId"))));
        return result;
    }

    private static Map<String, Object> surface(
        List<Map<String, Object>> literals,
        List<Map<String, Object>> formatters,
        List<Map<String, Object>> loaders,
        List<Map<String, Object>> externalDependencies
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("literalPathCandidates", literals);
        result.put("pathFormatterCandidates", formatters);
        result.put("loaderCandidates", loaders);
        for (String name : List.of(
            "decodeTransformCandidates", "runtimeKeyCandidates", "cacheRegistryCandidates",
            "ownerCandidates", "renderSubmissionCandidates", "audioSubmissionCandidates",
            "uiSubmissionCandidates", "presentationReceiptCandidates", "manualResourceCandidates"
        )) result.put(name, new ArrayList<>());
        result.put("externalDependencyCandidates", externalDependencies);
        return result;
    }

    private static List<Map<String, Object>> externalFontDependencies(File peImports) throws Exception {
        JsonObject root = JsonParser.parseString(
            Files.readString(peImports.toPath(), StandardCharsets.UTF_8)
        ).getAsJsonObject();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> expected = Set.of("CreateFontA", "OleCreateFontIndirect");
        Set<String> found = new LinkedHashSet<>();
        for (JsonElement element : root.getAsJsonArray("imports")) {
            JsonObject item = element.getAsJsonObject();
            String api = item.has("name") ? item.get("name").getAsString()
                : item.get("resolvedName").getAsString();
            if (!expected.contains(api)) continue;
            String dll = item.get("dll").getAsString().toUpperCase(Locale.ROOT);
            String fullName = dll + "::" + api;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidateId", "EXTERNAL_DEPENDENCY:FONT:" + fullName);
            row.put("status", "CANDIDATE");
            row.put("dependencyKind", "OS_FONT_API");
            row.put("name", fullName);
            row.put("category", "FONT");
            row.put("evidence", List.of("pe-imports:" + fullName + ":" + item.get("iatVa").getAsString()));
            result.add(row);
            found.add(api);
        }
        if (!found.equals(expected)) throw new IllegalStateException("font dependency imports differ");
        result.sort(Comparator.comparing(row -> String.valueOf(row.get("candidateId"))));
        return result;
    }

    @Override
    protected void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length != 10) {
            throw new IllegalArgumentException(
                "usage: <output> <exe-sha> <exporter-sha> <db-sha> <source-manifest> <source-manifest-sha> " +
                "<tree-manifest> <tree-manifest-sha> <pe-imports> <pe-imports-sha>"
            );
        }
        String executableSha = currentProgram.getExecutableSHA256().toUpperCase(Locale.ROOT);
        if (!executableSha.equals(args[1].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("program executable SHA-256 mismatch");
        }
        File sourceManifest = new File(args[4]);
        File treeManifest = new File(args[6]);
        File peImports = new File(args[8]);
        if (!sha256(Files.readAllBytes(sourceManifest.toPath())).equals(args[5].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(treeManifest.toPath())).equals(args[7].toUpperCase(Locale.ROOT)) ||
            !sha256(Files.readAllBytes(peImports.toPath())).equals(args[9].toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("resources input hash mismatch");
        }
        Map<String, String> treePaths = readTreeManifest(treeManifest);
        List<ResourceString> strings = scanResourceStrings(treePaths);
        List<Map<String, Object>> literals = pathCandidates(strings, false);
        List<Map<String, Object>> formatters = pathCandidates(strings, true);
        List<Map<String, Object>> loaders = loaderCandidates(strings);
        List<Map<String, Object>> externalDependencies = externalFontDependencies(peImports);
        Map<String, Object> resourceSurface = surface(
            literals, formatters, loaders, externalDependencies
        );

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
        source.put("treeManifestSha256", args[7].toUpperCase(Locale.ROOT));
        source.put("peImportsSha256", args[9].toUpperCase(Locale.ROOT));
        output.put("source", source);
        Map<String, Object> exporter = new LinkedHashMap<>();
        exporter.put("class", getClass().getSimpleName());
        exporter.put("sha256", args[2].toUpperCase(Locale.ROOT));
        exporter.put("ghidraRepositorySha256", args[3].toUpperCase(Locale.ROOT));
        output.put("exporter", exporter);
        output.put("surfaceSha256", sha256(compact.toJson(resourceSurface).getBytes(StandardCharsets.UTF_8)));
        output.put("successMarker", "EXPORT_EXHAUSTIVE_RESOURCES_OK");
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("scope", "COMPILED_RESOURCE_ANCHORS");
        audit.put("filePresenceIsIntegration", false);
        audit.put("stringPresenceIsLoaderProof", false);
        audit.put("staticSubmissionIsPlayerVisible", false);
        audit.put("limitations", List.of(
            "raw printable strings and initialized pointers are candidates, not loader proof",
            "runtime file-open, decode, cache, GPU/audio submission, and presentation remain unobserved",
            "formatted argument domains and indirect consumer joins remain unresolved"
        ));
        output.put("audit", audit);
        Map<String, Object> conservation = new LinkedHashMap<>();
        conservation.put("treeFiles", treePaths.size());
        conservation.put("rawResourceStrings", strings.size());
        conservation.put("literalPathCandidates", literals.size());
        conservation.put("pathFormatterCandidates", formatters.size());
        conservation.put("loaderCandidates", loaders.size());
        conservation.put("rawPointerCells", EXPECTED_RAW_POINTER_CELLS);
        conservation.put("externalFontDependencies", externalDependencies.size());
        output.put("conservation", conservation);
        output.putAll(resourceSurface);

        File outputFile = new File(args[0]);
        File parent = outputFile.getParentFile();
        if (parent != null && !parent.isDirectory()) throw new IllegalStateException("output directory does not exist");
        Gson gson = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();
        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))) {
            writer.print(gson.toJson(output));
            writer.print("\n");
        }
        println("EXPORT_EXHAUSTIVE_RESOURCES_OK output=" + outputFile.getAbsolutePath());
    }
}
