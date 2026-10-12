import org.polyzon.Lang;
import org.polyzon.Limits;
import org.polyzon.Model;
import org.polyzon.PolyzonClient;
import org.polyzon.SearchItem;
import org.polyzon.SearchPage;
import org.polyzon.SearchParams;
import org.polyzon.errors.PolyzonException;

/**
 * Searches for free dragon models, opens the first one and shows the remaining quota.
 *
 * <pre>
 * export POLYZON_API_KEY=pzk_...
 * mvn -q compile
 * java -cp target/classes examples/Quickstart.java
 * </pre>
 */
public class Quickstart {
    public static void main(String[] args) {
        try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {
            SearchPage page = polyzon.search(SearchParams.builder("дракон").free(true).size(5).build());
            System.out.printf("%d models (%s), request id %s%n",
                    page.total(), page.totalRelation(), page.meta().requestId());
            for (SearchItem item : page.items()) {
                System.out.printf("  %s/%s  %s  %s%n", item.source(), item.id(), item.title(), item.url());
            }

            if (!page.items().isEmpty()) {
                SearchItem first = page.items().get(0);
                Model model = polyzon.getModel(first.source(), first.id(), Lang.EN);
                System.out.printf("%n%s%n%s%n", model.title(), model.description());
            }

            Limits limits = polyzon.limits();
            System.out.printf("%nDaily quota: %d of %d left%n", limits.daily().remaining(), limits.daily().limit());
        } catch (PolyzonException e) {
            System.err.println("Polyzon API error: " + e.getMessage());
            System.exit(1);
        }
    }
}
