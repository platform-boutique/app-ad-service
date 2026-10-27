package hipstershop;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Static ad catalog of the Everly demo store.
 *
 * <p>Two ads per department. Every ad links to its department page ({@code /?category=<department id>}).
 * Ads are reachable through the department id itself and through the type words / source categories
 * that the catalog puts into a product's {@code categories} list (what the frontend sends as context keys).
 */
final class AdsMap {

    static final String CATEGORY_LINK_PREFIX = "/?category=";

    private static final int MAX_ADS_TO_SERVE = 2;
    private static final Random RANDOM = new Random();

    /** Department id -> its two ads. */
    private static final ImmutableMap<String, ImmutableList<Ad>> DEPARTMENT_ADS =
        ImmutableMap.<String, ImmutableList<Ad>>builder()
            .put("women", ImmutableList.of(
                ad("women", "New season dresses and tops at Everly. Fresh colours, easy fits, 20% off this week."),
                ad("women", "Shoes and bags to finish the look. Free returns on all women's footwear.")))
            .put("men", ImmutableList.of(
                ad("men", "Crisp shirts for work and weekend. Buy two, save 15% at Everly."),
                ad("men", "Step out in comfort: men's shoes from everyday sneakers to smart leather.")))
            .put("accessories", ImmutableList.of(
                ad("accessories", "Watches for every wrist. Discover the Everly accessories edit."),
                ad("accessories", "Sunglasses and jewellery that make the outfit. Free gift wrap on every order.")))
            .put("beauty", ImmutableList.of(
                ad("beauty", "Find your signature scent. Fragrances for him and her at Everly."),
                ad("beauty", "Glow on: skin care and makeup essentials, 3 for 2 this month.")))
            .put("home", ImmutableList.of(
                ad("home", "Make it home: decor pieces that bring warmth to every room."),
                ad("home", "Furniture that fits real life. Browse the Everly home collection.")))
            .put("kitchen", ImmutableList.of(
                ad("kitchen", "Cook like you mean it. Kitchen tools and accessories for every recipe."),
                ad("kitchen", "Upgrade your kitchen basics: pans, boards and gadgets, 10% off.")))
            .put("sports", ImmutableList.of(
                ad("sports", "Gear up for the season. Balls, rackets and kit for every game."),
                ad("sports", "Train harder, play longer: sports accessories at Everly prices.")))
            .build();

    /**
     * Type words and source categories (as found in the catalog seed data) -> department ids.
     * {@code clothing} and {@code footwear} occur in both women and men, so they map to both.
     */
    private static final ImmutableListMultimap<String, String> ALIASES =
        ImmutableListMultimap.<String, String>builder()
            .putAll("clothing", "women", "men")
            .putAll("footwear", "women", "men")
            .put("tops", "women")
            .put("bags", "women")
            .put("womens-dresses", "women")
            .put("womens-shoes", "women")
            .put("womens-bags", "women")
            .put("mens-shirts", "men")
            .put("mens-shoes", "men")
            .put("jewellery", "accessories")
            .put("watches", "accessories")
            .put("sunglasses", "accessories")
            .put("mens-watches", "accessories")
            .put("womens-watches", "accessories")
            .put("womens-jewellery", "accessories")
            .put("fragrances", "beauty")
            .put("makeup", "beauty")
            .put("skin-care", "beauty")
            .put("decor", "home")
            .put("furniture", "home")
            .put("home-decoration", "home")
            .put("kitchen-accessories", "kitchen")
            .put("sports-accessories", "sports")
            .build();

    private static final ImmutableList<Ad> ALL_ADS =
        DEPARTMENT_ADS.values().stream().flatMap(List::stream).collect(ImmutableList.toImmutableList());

    private AdsMap() {}

    static Set<String> departments() {
        return DEPARTMENT_ADS.keySet();
    }

    static Set<String> aliases() {
        return ALIASES.keySet();
    }

    /** All ads for one context key (a department id or an alias); empty for unknown keys. */
    static Collection<Ad> getByCategory(String key) {
        return adsFor(departmentsFor(normalize(key)));
    }

    /**
     * Ads for a request's context keys, without duplicates. If at least one key is a department id,
     * only the department ids are used (a men's shoe sends {@code men, footwear, mens-shoes}: the
     * shared word {@code footwear} must not add the women's ads). Otherwise the aliases are resolved.
     */
    static List<Ad> getForContext(List<String> keys) {
        Set<String> direct = new LinkedHashSet<>();
        Set<String> viaAlias = new LinkedHashSet<>();
        for (String raw : keys) {
            String key = normalize(raw);
            if (DEPARTMENT_ADS.containsKey(key)) {
                direct.add(key);
            } else {
                viaAlias.addAll(ALIASES.get(key));
            }
        }
        return adsFor(direct.isEmpty() ? viaAlias : direct);
    }

    /** Two different ads picked at random from the whole catalog. */
    static List<Ad> getRandom() {
        List<Ad> pool = new ArrayList<>(ALL_ADS);
        List<Ad> result = new ArrayList<>(MAX_ADS_TO_SERVE);
        for (int i = 0; i < MAX_ADS_TO_SERVE && !pool.isEmpty(); i++) {
            result.add(pool.remove(RANDOM.nextInt(pool.size())));
        }
        return result;
    }

    private static Set<String> departmentsFor(String key) {
        if (DEPARTMENT_ADS.containsKey(key)) {
            return ImmutableSet.of(key);
        }
        return ImmutableSet.copyOf(ALIASES.get(key));
    }

    private static List<Ad> adsFor(Collection<String> departmentIds) {
        List<Ad> result = new ArrayList<>();
        for (String id : departmentIds) {
            result.addAll(DEPARTMENT_ADS.getOrDefault(id, ImmutableList.of()));
        }
        return result;
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static Ad ad(String departmentId, String text) {
        return Ad.newBuilder()
            .setRedirectUrl(CATEGORY_LINK_PREFIX + departmentId)
            .setText(text)
            .build();
    }
}
