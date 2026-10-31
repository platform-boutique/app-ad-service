package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class AdCatalogTest {

    private static final String VALID_AD =
        "{\"id\":\"a-1\",\"text\":\"T\",\"redirect_url\":\"/?category=women\","
            + "\"departments\":[\"women\"],\"keywords\":[\"dress\"]}";

    private static AdCatalog parse(String json) {
        return AdCatalog.parse(json, "test");
    }

    private static String message(String json) {
        return assertThrows(AdConfigException.class, () -> parse(json)).getMessage();
    }

    /** Small fixed catalog for the matching and rotation tests. */
    private static AdCatalog fixture() {
        return parse("["
            + "{\"id\":\"w1\",\"text\":\"w1\",\"redirect_url\":\"/w\",\"departments\":[\"women\"],\"keywords\":[\"footwear\",\"shoes\"]},"
            + "{\"id\":\"w2\",\"text\":\"w2\",\"redirect_url\":\"/w\",\"departments\":[\"women\"],\"keywords\":[\"footwear\"]},"
            + "{\"id\":\"m1\",\"text\":\"m1\",\"redirect_url\":\"/m\",\"departments\":[\"men\"],\"keywords\":[\"footwear\"]},"
            + "{\"id\":\"k1\",\"text\":\"k1\",\"redirect_url\":\"/k\",\"keywords\":[\"dress\",\"skirt\"]},"
            + "{\"id\":\"k2\",\"text\":\"k2\",\"redirect_url\":\"/k\",\"keywords\":[\"dress\"]},"
            + "{\"id\":\"s1\",\"text\":\"s1\",\"redirect_url\":\"/s\",\"departments\":[\"sports\"]}"
            + "]");
    }

    private static List<String> ids(List<Ad> ads) {
        return ads.stream().map(Ad::getId).collect(Collectors.toList());
    }

    // ---- config loading ----

    @Test
    void parse_validConfig_keepsFieldsAndOrder() {
        AdCatalog c = parse("[" + VALID_AD + ",{\"id\":\"b.2_x\",\"text\":\"B\",\"redirect_url\":\"https://example.com/x\",\"keywords\":[\"run\"]}]");
        assertEquals(List.of("a-1", "b.2_x"), ids(c.ads()));
        Ad a = c.ads().get(0);
        assertEquals("T", a.getText());
        assertEquals("/?category=women", a.getRedirectUrl());
        assertEquals(Set.of("women"), c.entries().get(0).departments());
        assertEquals(Set.of("run"), c.entries().get(1).keywords());
        assertTrue(c.contains("b.2_x"));
        assertFalse(c.contains("nope"));
    }

    @Test
    void parse_duplicateId_isRejectedAndNamesTheId() {
        String m = message("[" + VALID_AD + "," + VALID_AD + "]");
        assertTrue(m.contains("duplicate id \"a-1\""), m);
    }

    @ParameterizedTest
    @MethodSource("missingFields")
    void parse_missingRequiredField_isRejected(String field, String json) {
        String m = message(json);
        assertTrue(m.contains("missing required field \"" + field + "\""), m);
        assertTrue(m.contains("ad #0"), m);
    }

    static Stream<Arguments> missingFields() {
        return Stream.of(
            Arguments.of("id", "[{\"text\":\"T\",\"redirect_url\":\"/x\",\"keywords\":[\"a\"]}]"),
            Arguments.of("text", "[{\"id\":\"a\",\"redirect_url\":\"/x\",\"keywords\":[\"a\"]}]"),
            Arguments.of("redirect_url", "[{\"id\":\"a\",\"text\":\"T\",\"keywords\":[\"a\"]}]"));
    }

    @ParameterizedTest
    @MethodSource("invalidConfigs")
    void parse_invalidConfig_isRejected(String expectedPart, String json) {
        String m = message(json);
        assertTrue(m.contains(expectedPart), "expected '" + expectedPart + "' in: " + m);
    }

    static Stream<Arguments> invalidConfigs() {
        String head = "{\"id\":\"a\",\"text\":\"T\",\"redirect_url\":\"/x\"";
        return Stream.of(
            Arguments.of("not valid JSON", "[{"),
            Arguments.of("not valid JSON", "not json"),
            Arguments.of("not valid JSON", "[" + head + ",\"id\":\"b\",\"keywords\":[\"a\"]}]"),
            Arguments.of("must be a JSON array", "{\"id\":\"a\"}"),
            Arguments.of("must be a JSON array", "\"x\""),
            Arguments.of("contains no ads", "[]"),
            Arguments.of("must be a JSON object", "[\"x\"]"),
            Arguments.of("unknown field \"color\"", "[" + head + ",\"keywords\":[\"a\"],\"color\":\"red\"}]"),
            Arguments.of("id must match", "[{\"id\":\"Bad Id\",\"text\":\"T\",\"redirect_url\":\"/x\",\"keywords\":[\"a\"]}]"),
            Arguments.of("must not be blank", "[{\"id\":\"a\",\"text\":\"  \",\"redirect_url\":\"/x\",\"keywords\":[\"a\"]}]"),
            Arguments.of("must be a string", "[{\"id\":\"a\",\"text\":5,\"redirect_url\":\"/x\",\"keywords\":[\"a\"]}]"),
            Arguments.of("redirect_url must be", "[{\"id\":\"a\",\"text\":\"T\",\"redirect_url\":\"javascript:alert(1)\",\"keywords\":[\"a\"]}]"),
            Arguments.of("redirect_url must be", "[{\"id\":\"a\",\"text\":\"T\",\"redirect_url\":\"//evil.example\",\"keywords\":[\"a\"]}]"),
            Arguments.of("unknown department \"toys\"", "[" + head + ",\"departments\":[\"toys\"]}]"),
            Arguments.of("must be an array of strings", "[" + head + ",\"departments\":\"women\"}]"),
            Arguments.of("non-blank strings", "[" + head + ",\"keywords\":[1]}]"),
            Arguments.of("keyword \"Dress\" must be lower-case", "[" + head + ",\"keywords\":[\"Dress\"]}]"),
            Arguments.of("keyword \"two words\"", "[" + head + ",\"keywords\":[\"two words\"]}]"),
            Arguments.of("duplicate entry \"a\"", "[" + head + ",\"keywords\":[\"a\",\"a\"]}]"),
            Arguments.of("at least one department or keyword", "[" + head + ",\"departments\":[],\"keywords\":[]}]"),
            Arguments.of("at least one department or keyword", "[" + head + "}]"));
    }

    @Test
    void load_missingFile_isRejectedWithThePath() {
        AdConfigException e = assertThrows(AdConfigException.class, () -> AdCatalog.load("/nonexistent/ads.json"));
        assertTrue(e.getMessage().contains("/nonexistent/ads.json"), e.getMessage());
        assertTrue(e.getMessage().contains("ADS_CONFIG"), e.getMessage());
    }

    @Test
    void load_directoryAsPath_isRejected(@TempDir Path dir) {
        assertThrows(AdConfigException.class, () -> AdCatalog.load(dir.toString()));
    }

    @Test
    void load_file_usesItInsteadOfTheBundledAds(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ads.json");
        Files.writeString(file, "[" + VALID_AD + "]");
        AdCatalog c = AdCatalog.load(file.toString());
        assertEquals(List.of("a-1"), ids(c.ads()));
    }

    @Test
    void load_invalidFile_isRejected(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("ads.json");
        Files.writeString(file, "[" + VALID_AD + "," + VALID_AD + "]");
        assertThrows(AdConfigException.class, () -> AdCatalog.load(file.toString()));
    }

    @Test
    void load_nullOrBlank_usesTheBundledAds() {
        assertEquals(ids(AdsMap.bundled().ads()), ids(AdCatalog.load(null).ads()));
        assertEquals(ids(AdsMap.bundled().ads()), ids(AdCatalog.load("  ").ads()));
    }

    @Test
    void bundledConfig_hasTheSevenDepartmentsAndKeywordAds() {
        AdCatalog c = AdsMap.bundled();
        assertEquals(AdCatalog.KNOWN_DEPARTMENTS, c.departments());
        assertEquals(14 + 3, c.ads().size());
        long keywordOnly = c.entries().stream().filter(e -> e.departments().isEmpty()).count();
        assertTrue(keywordOnly >= 1 && keywordOnly <= 3, "keyword-only ads: " + keywordOnly);
        for (Ad ad : c.ads()) {
            assertFalse(ad.getId().isBlank());
        }
    }

    // ---- matching ----

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("matchingTable")
    void match_table(List<String> keys, List<String> expectedIds) {
        assertEquals(expectedIds, ids(fixture().match(keys)));
    }

    static Stream<Arguments> matchingTable() {
        return Stream.of(
            Arguments.of(List.of("women"), List.of("w1", "w2")),
            Arguments.of(List.of("WOMEN"), List.of("w1", "w2")),
            Arguments.of(List.of(" Women "), List.of("w1", "w2")),
            Arguments.of(List.of("men"), List.of("m1")),
            Arguments.of(List.of("sports"), List.of("s1")),
            // department beats keywords: footwear must not pull in the other department's ads
            Arguments.of(List.of("men", "footwear"), List.of("m1")),
            Arguments.of(List.of("women", "footwear", "dress"), List.of("w1", "w2")),
            // two departments (cart), config order not request order
            Arguments.of(List.of("sports", "men"), List.of("m1", "s1")),
            // keywords only
            Arguments.of(List.of("footwear"), List.of("w1", "w2", "m1")),
            Arguments.of(List.of("dress"), List.of("k1", "k2")),
            Arguments.of(List.of("skirt"), List.of("k1")),
            Arguments.of(List.of("Skirt", "dress"), List.of("k1", "k2")),
            Arguments.of(List.of("shoes"), List.of("w1")),
            // no match
            Arguments.of(List.of(), List.of()),
            Arguments.of(List.of(""), List.of()),
            Arguments.of(List.of("  "), List.of()),
            Arguments.of(List.of("unknown"), List.of()),
            Arguments.of(List.of("wom"), List.of()),
            Arguments.of(List.of("dress shoes"), List.of()));
    }

    @Test
    void match_neverReturnsDuplicates() {
        List<Ad> ads = fixture().match(List.of("women", "women", "footwear", "shoes"));
        assertEquals(List.of("w1", "w2"), ids(ads));
    }

    // ---- rotation ----

    @Test
    void rotate_isDeterministicForAGivenCounter() {
        List<Ad> matches = fixture().match(List.of("footwear"));
        for (long counter = 0; counter < 10; counter++) {
            assertEquals(AdCatalog.rotate(matches, counter), AdCatalog.rotate(matches, counter));
        }
    }

    @Test
    void rotate_threeMatches_cyclesThroughPairsAndWraps() {
        List<Ad> m = fixture().match(List.of("footwear")); // w1 w2 m1
        assertEquals(List.of("w1", "w2"), ids(AdCatalog.rotate(m, 0)));
        assertEquals(List.of("w2", "m1"), ids(AdCatalog.rotate(m, 1)));
        assertEquals(List.of("m1", "w1"), ids(AdCatalog.rotate(m, 2)));
        assertEquals(List.of("w1", "w2"), ids(AdCatalog.rotate(m, 3)));
    }

    @Test
    void rotate_fourMatches_everyAdAppearsOverACycle() {
        List<Ad> m = AdsMap.getForContext(List.of("footwear")); // bundled: 4 ads
        assertEquals(4, m.size());
        Set<String> seen = IntStream.range(0, 4)
            .boxed().flatMap(i -> AdCatalog.rotate(m, i).stream()).map(Ad::getId).collect(Collectors.toSet());
        assertEquals(4, seen.size());
    }

    @Test
    void rotate_twoMatches_returnsBothAndAlternatesOrder() {
        List<Ad> m = fixture().match(List.of("women"));
        assertEquals(List.of("w1", "w2"), ids(AdCatalog.rotate(m, 0)));
        assertEquals(List.of("w2", "w1"), ids(AdCatalog.rotate(m, 1)));
    }

    @Test
    void rotate_oneMatch_returnsIt_andNegativeCountersDoNotBreak() {
        List<Ad> one = fixture().match(List.of("sports"));
        assertEquals(one, AdCatalog.rotate(one, 7));
        List<Ad> three = fixture().match(List.of("footwear"));
        assertEquals(2, AdCatalog.rotate(three, Long.MIN_VALUE).size());
        assertEquals(2, AdCatalog.rotate(three, -1).size());
        assertEquals(List.of(), AdCatalog.rotate(List.of(), 3));
    }

    @Test
    void random_returnsTwoDifferentAds_orFewerForASmallCatalog() {
        for (int i = 0; i < 50; i++) {
            List<Ad> r = fixture().random();
            assertEquals(2, r.size());
            assertNotEquals(r.get(0), r.get(1));
        }
        assertEquals(1, parse("[" + VALID_AD + "]").random().size());
    }
}
