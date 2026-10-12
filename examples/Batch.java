import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.polyzon.Limits;
import org.polyzon.Me;
import org.polyzon.ModelRef;
import org.polyzon.ModelResult;
import org.polyzon.PolyzonClient;
import org.polyzon.SearchItem;
import org.polyzon.SearchParams;

/**
 * Fetches the model cards of a search page with bounded parallelism, then runs two calls
 * concurrently with the async API.
 *
 * <pre>
 * java -cp target/classes examples/Batch.java
 * </pre>
 */
public class Batch {
    public static void main(String[] args) {
        try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {
            List<ModelRef> refs = polyzon.search(SearchParams.builder("vase").size(8).build())
                    .items().stream().map(SearchItem::ref).toList();

            // At most 4 requests in flight; results come back in input order, failures included.
            for (ModelResult result : polyzon.getModels(refs, 4)) {
                if (result.isSuccess()) {
                    System.out.printf("%-30s %d images%n", result.model().title(), result.model().images().size());
                } else {
                    System.out.printf("%-30s failed: %s%n", result.ref().id(), result.error().getMessage());
                }
            }

            // Async twins return CompletableFuture and never block a thread while waiting.
            CompletableFuture<Me> me = polyzon.meAsync();
            CompletableFuture<Limits> limits = polyzon.limitsAsync();
            CompletableFuture.allOf(me, limits).join();
            System.out.printf("%s: %d requests left today%n",
                    me.join().token().name(), limits.join().daily().remaining());
        }
    }
}
