package hipstershop;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The ads of the store, loaded and validated from JSON, plus the matching rules.
 *
 * <p>The catalog is immutable once built. Loading is strict: any problem raises an
 * {@link AdConfigException} and the process must not serve a partial configuration.
 */
final class AdCatalog {

    /** Name of the bundled default config on the classpath. */
    static final String BUNDLED_RESOURCE = "/ads.json";

    /** Department ids of the product catalog; the only values allowed in {@code departments}. */
    static final Set<String> KNOWN_DEPARTMENTS =
        Set.of("women", "men", "accessories", "beauty", "home", "kitchen", "sports");

    static final int MAX_ADS_TO_SERVE = 2;

    private static final Set<String> FIELDS =
        Set.of("id", "text", "redirect_url", "departments", "keywords");
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern KEYWORD = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Random RANDOM = new Random();

    /** One configured ad with the keys it answers to. */
    record Entry(Ad ad, Set<String> departments, Set<String> keywords) {}

    private final List<Entry> entries;
    private final List<Ad> ads;

    private AdCatalog(List<Entry> entries) {
        this.entries = List.copyOf(entries);
        this.ads = entries.stream().map(Entry::ad).toList();
    }

    // ---- loading ----

    /** Loads the file at {@code path}, or the bundled default if {@code path} is null or blank. */
    static AdCatalog load(String path) {
        if (path == null || path.isBlank()) {
            return loadBundled();
        }
        String json;
        try {
            json = Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            throw new AdConfigException(
                "cannot read ad config " + path + " (ADS_CONFIG): " + e, e);
        }
        return parse(json, path);
    }

    static AdCatalog loadBundled() {
        try (InputStream in = AdCatalog.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                throw new AdConfigException("bundled ad config " + BUNDLED_RESOURCE + " is missing from the image");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled " + BUNDLED_RESOURCE);
        } catch (IOException e) {
            throw new AdConfigException("cannot read bundled ad config " + BUNDLED_RESOURCE + ": " + e, e);
        }
    }

    /** Parses and validates a config document; {@code source} only names it in error messages. */
    static AdCatalog parse(String json, String source) {
        JsonNode root;
        try {
            JsonFactory factory = new JsonFactory();
            factory.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = new ObjectMapper(factory).readTree(json);
        } catch (JsonProcessingException e) {
            String at = e.getLocation() == null ? "" : " (line " + e.getLocation().getLineNr()
                + ", column " + e.getLocation().getColumnNr() + ")";
            throw new AdConfigException("ad config " + source + " is not valid JSON: " + e.getOriginalMessage() + at, e);
        }
        if (root == null || !root.isArray()) {
            throw new AdConfigException("ad config " + source + " must be a JSON array of ads");
        }
        if (root.isEmpty()) {
            throw new AdConfigException("ad config " + source + " contains no ads");
        }

        List<Entry> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        int index = 0;
        for (JsonNode node : root) {
            String where = "ad config " + source + ", ad #" + index;
            Entry entry = parseEntry(node, where);
            if (!seenIds.add(entry.ad().getId())) {
                throw new AdConfigException(where + ": duplicate id \"" + entry.ad().getId() + "\"");
            }
            result.add(entry);
            index++;
        }
        return new AdCatalog(result);
    }

    private static Entry parseEntry(JsonNode node, String where) {
        if (!node.isObject()) {
            throw new AdConfigException(where + ": must be a JSON object");
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!FIELDS.contains(name)) {
                throw new AdConfigException(where + ": unknown field \"" + name + "\"");
            }
        }
        String id = requiredString(node, "id", where);
        where = where + " (id \"" + id + "\")";
        if (!ID.matcher(id).matches()) {
            throw new AdConfigException(where + ": id must match " + ID.pattern());
        }
        String text = requiredString(node, "text", where);
        String redirectUrl = requiredString(node, "redirect_url", where);
        if (!(redirectUrl.startsWith("/") && !redirectUrl.startsWith("//"))
                && !redirectUrl.startsWith("http://") && !redirectUrl.startsWith("https://")) {
            throw new AdConfigException(
                where + ": redirect_url must be a path starting with / or an http(s) URL, got \"" + redirectUrl + "\"");
        }

        Set<String> departments = stringSet(node, "departments", where);
        for (String department : departments) {
            if (!KNOWN_DEPARTMENTS.contains(department)) {
                throw new AdConfigException(
                    where + ": unknown department \"" + department + "\" (allowed: " + new TreeSet<>(KNOWN_DEPARTMENTS) + ")");
            }
        }
        Set<String> keywords = stringSet(node, "keywords", where);
        for (String keyword : keywords) {
            if (!KEYWORD.matcher(keyword).matches()) {
                throw new AdConfigException(
                    where + ": keyword \"" + keyword + "\" must be lower-case, without spaces, matching " + KEYWORD.pattern());
            }
        }
        if (departments.isEmpty() && keywords.isEmpty()) {
            throw new AdConfigException(where + ": needs at least one department or keyword");
        }

        Ad ad = Ad.newBuilder().setId(id).setText(text).setRedirectUrl(redirectUrl).build();
        return new Entry(ad, Collections.unmodifiableSet(departments), Collections.unmodifiableSet(keywords));
    }

    private static String requiredString(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new AdConfigException(where + ": missing required field \"" + field + "\"");
        }
        if (!value.isTextual()) {
            throw new AdConfigException(where + ": field \"" + field + "\" must be a string");
        }
        String s = value.textValue();
        if (s.isBlank()) {
            throw new AdConfigException(where + ": field \"" + field + "\" must not be blank");
        }
        return s;
    }

    /** Optional array of strings (absent = empty), no duplicates. */
    private static Set<String> stringSet(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        Set<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        if (!value.isArray()) {
            throw new AdConfigException(where + ": field \"" + field + "\" must be an array of strings");
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new AdConfigException(where + ": field \"" + field + "\" must contain only non-blank strings");
            }
            if (!result.add(item.textValue())) {
                throw new AdConfigException(where + ": duplicate entry \"" + item.textValue() + "\" in \"" + field + "\"");
            }
        }
        return result;
    }

    // ---- queries ----

    /** All ads in config order. */
    List<Ad> ads() {
        return ads;
    }

    List<Entry> entries() {
        return entries;
    }

    boolean contains(String adId) {
        return ads.stream().anyMatch(a -> a.getId().equals(adId));
    }

    /** Department ids that at least one ad is configured for. */
    Set<String> departments() {
        Set<String> result = new LinkedHashSet<>();
        entries.forEach(e -> result.addAll(e.departments()));
        return result;
    }

    /** Keywords that at least one ad is configured for. */
    Set<String> keywords() {
        Set<String> result = new LinkedHashSet<>();
        entries.forEach(e -> result.addAll(e.keywords()));
        return result;
    }

    /**
     * All ads matching the context keys, without duplicates, in config order.
     *
     * <p>Keys are trimmed and compared case-insensitively. An ad matches a key if the key is one of
     * its departments or one of its keywords. A key that names a department takes precedence: if at
     * least one ad matches by department, only those ads are returned. A men's shoe sends
     * {@code men, footwear, mens-shoes}; the word {@code footwear} is a keyword of the women's ads as
     * well and must not add them. Only when no key names a department are the keyword matches used
     * (search pages send plain words).
     */
    List<Ad> match(List<String> keys) {
        Set<String> normalized = new HashSet<>();
        for (String key : keys) {
            String k = normalize(key);
            if (!k.isEmpty()) {
                normalized.add(k);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<Ad> byDepartment = new ArrayList<>();
        List<Ad> byKeyword = new ArrayList<>();
        for (Entry e : entries) {
            if (!Collections.disjoint(e.departments(), normalized)) {
                byDepartment.add(e.ad());
            } else if (!Collections.disjoint(e.keywords(), normalized)) {
                byKeyword.add(e.ad());
            }
        }
        return byDepartment.isEmpty() ? byKeyword : byDepartment;
    }

    /**
     * Picks up to {@link #MAX_ADS_TO_SERVE} ads from {@code matches}, starting at
     * {@code counter mod size} and wrapping around, so that successive counter values show
     * different ads. Pure and deterministic for a given counter.
     */
    static List<Ad> rotate(List<Ad> matches, long counter) {
        int n = matches.size();
        if (n <= 1) {
            return matches;
        }
        int start = (int) Math.floorMod(counter, (long) n);
        int count = Math.min(MAX_ADS_TO_SERVE, n);
        List<Ad> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(matches.get((start + i) % n));
        }
        return result;
    }

    /** Two different ads picked at random from the whole catalog (fewer if the catalog is smaller). */
    List<Ad> random() {
        List<Ad> pool = new ArrayList<>(ads);
        List<Ad> result = new ArrayList<>(MAX_ADS_TO_SERVE);
        for (int i = 0; i < MAX_ADS_TO_SERVE && !pool.isEmpty(); i++) {
            result.add(pool.remove(RANDOM.nextInt(pool.size())));
        }
        return result;
    }

    static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }
}
