package hipstershop;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AdsMapTest {

    private static final Set<String> DEPARTMENTS =
        Set.of("women", "men", "accessories", "beauty", "home", "kitchen", "sports");

    @Test
    void departments_areTheSevenCatalogDepartments() {
        assertEquals(DEPARTMENTS, AdsMap.departments());
    }

    @ParameterizedTest
    @ValueSource(strings = {"women", "men", "accessories", "beauty", "home", "kitchen", "sports"})
    void getByCategory_everyDepartment_returnsTwoAdsLinkingToItself(String department) {
        Collection<Ad> ads = AdsMap.getByCategory(department);
        assertEquals(2, ads.size(), department + " should have exactly 2 ads");
        for (Ad ad : ads) {
            assertEquals(AdsMap.CATEGORY_LINK_PREFIX + department, ad.getRedirectUrl());
            assertFalse(ad.getText().isBlank());
        }
    }

    /** Every distinct value of {@code categories} in product-catalog-service data/seed/products.json (117 products). */
    @ParameterizedTest
    @ValueSource(strings = {
        "women", "men", "accessories", "beauty", "home", "kitchen", "sports",
        "clothing", "footwear", "tops", "bags", "womens-dresses", "womens-shoes", "womens-bags",
        "mens-shirts", "mens-shoes", "jewellery", "watches", "sunglasses", "mens-watches",
        "womens-watches", "womens-jewellery", "fragrances", "makeup", "skin-care", "decor",
        "furniture", "home-decoration", "kitchen-accessories", "sports-accessories"})
    void getByCategory_everyCategoryInTheSeedData_returnsAds(String key) {
        assertFalse(AdsMap.getByCategory(key).isEmpty(), key + " should yield at least one ad");
    }

    @Test
    void getByCategory_aliasResolvesToItsDepartment() {
        assertEquals(AdsMap.getByCategory("beauty"), AdsMap.getByCategory("fragrances"));
        assertEquals(AdsMap.getByCategory("kitchen"), AdsMap.getByCategory("kitchen-accessories"));
    }

    @Test
    void getByCategory_isCaseAndWhitespaceInsensitive() {
        assertEquals(AdsMap.getByCategory("home"), AdsMap.getByCategory(" Home "));
    }

    @Test
    void getByCategory_unknownCategory_returnsEmpty() {
        assertTrue(AdsMap.getByCategory("nonexistent-category").isEmpty());
        assertTrue(AdsMap.getByCategory("").isEmpty());
        assertTrue(AdsMap.getByCategory(null).isEmpty());
    }

    @Test
    void everyRedirectUrl_isACategoryLinkToAnExistingDepartment() {
        Set<String> keys = new HashSet<>(AdsMap.departments());
        keys.addAll(AdsMap.aliases());
        for (String key : keys) {
            for (Ad ad : AdsMap.getByCategory(key)) {
                String url = ad.getRedirectUrl();
                assertTrue(url.startsWith(AdsMap.CATEGORY_LINK_PREFIX), url);
                String department = url.substring(AdsMap.CATEGORY_LINK_PREFIX.length());
                assertTrue(DEPARTMENTS.contains(department), url + " names an unknown department");
            }
        }
    }

    @Test
    void getForContext_productPage_usesOnlyTheDepartment() {
        // a men's shoe: the shared word "footwear" must not pull in the women's ads
        List<Ad> ads = AdsMap.getForContext(List.of("men", "footwear", "mens-shoes"));
        assertEquals(List.copyOf(AdsMap.getByCategory("men")), ads);
    }

    @Test
    void getForContext_cartWithTwoDepartments_returnsBothWithoutDuplicates() {
        List<Ad> ads = AdsMap.getForContext(
            List.of("beauty", "fragrances", "kitchen", "kitchen-accessories", "beauty", "fragrances"));
        assertEquals(4, ads.size());
        assertEquals(4, new HashSet<>(ads).size());
    }

    @Test
    void getForContext_onlyAliases_resolvesThem() {
        List<Ad> ads = AdsMap.getForContext(List.of("footwear"));
        assertEquals(4, ads.size(), "footwear occurs in women and men");
    }

    @Test
    void getForContext_unknownOrEmpty_returnsEmpty() {
        assertTrue(AdsMap.getForContext(List.of()).isEmpty());
        assertTrue(AdsMap.getForContext(List.of("nonexistent-category")).isEmpty());
    }

    @RepeatedTest(50)
    void getRandom_returnsTwoDifferentAds() {
        List<Ad> ads = AdsMap.getRandom();
        assertEquals(2, ads.size(), "getRandom should always return exactly 2 ads");
        assertNotEquals(ads.get(0), ads.get(1), "the two random ads should differ");
        for (Ad ad : ads) {
            assertTrue(ad.getRedirectUrl().startsWith(AdsMap.CATEGORY_LINK_PREFIX));
            assertFalse(ad.getText().isBlank());
        }
    }
}
