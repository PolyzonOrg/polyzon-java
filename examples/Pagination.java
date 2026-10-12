import java.util.List;
import org.polyzon.PolyzonClient;
import org.polyzon.SearchItem;
import org.polyzon.SearchParams;
import org.polyzon.Sort;
import org.polyzon.Sources;

/**
 * Walks search results page by page with a lazy stream. Every page costs one request of the
 * daily quota, so the stream is bounded with limit(): 120 items at 50 per page = 3 requests.
 *
 * <pre>
 * java -cp target/classes examples/Pagination.java
 * </pre>
 */
public class Pagination {
    public static void main(String[] args) {
        SearchParams params = SearchParams.builder("chess set")
                .size(50)
                .sort(Sort.NEWEST)
                .sources(Sources.PRINTABLES, Sources.THINGIVERSE)
                .build();
        try (PolyzonClient polyzon = PolyzonClient.fromEnv()) {
            List<SearchItem> items = polyzon.searchAll(params).limit(120).toList();
            items.forEach(item -> System.out.printf("%-12s %-14s %s%n", item.source(), item.id(), item.title()));
            System.out.printf("%d items%n", items.size());
        }
    }
}
