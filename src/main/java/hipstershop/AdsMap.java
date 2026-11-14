package hipstershop;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Static view of the bundled default catalog ({@code ads.json}, see {@link AdCatalog}).
 *
 * <p>The running service uses the catalog it loaded from {@code ADS_CONFIG} (or this bundled one) and
 * passes it to {@link AdServiceImpl}; this class is the shortcut to the bundled default, used by the
 * one-argument {@code AdServiceImpl} constructor and by the tests that check the shipped ads.
 */
final class AdsMap {

    static final String CATEGORY_LINK_PREFIX = "/?category=";

    private static final class Holder {
        static final AdCatalog BUNDLED = AdCatalog.loadBundled();
    }

    private AdsMap() {}

    static AdCatalog bundled() {
        return Holder.BUNDLED;
    }

    static Set<String> departments() {
        return bundled().departments();
    }

    /** Keywords of the bundled ads (type words / source categories of the product catalog, search words). */
    static Set<String> aliases() {
        return bundled().keywords();
    }

    /** All ads for one context key (a department id or a keyword); empty for unknown keys. */
    static Collection<Ad> getByCategory(String key) {
        return bundled().match(List.of(key == null ? "" : key));
    }

    /** All ads matching the keys, without duplicates (see {@link AdCatalog#match}). */
    static List<Ad> getForContext(List<String> keys) {
        return bundled().match(keys);
    }

    /** Two different ads picked at random from the whole catalog. */
    static List<Ad> getRandom() {
        return bundled().random();
    }
}
