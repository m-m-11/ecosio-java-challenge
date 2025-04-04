import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;

public class Main {
    private static HttpClient client;

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        if (args.length != 1) {
            System.out.println("Usage: java Main <URL>");
            return;
        }

        String url = args[0];
        if (!url.startsWith("http")) {
            System.err.println("Invalid URL. Please provide a valid HTTP or HTTPS URL.");
            return;
        }

        String html = getHtmlByUrl(url);
        if (html == null) {
            System.err.println("Failed to retrieve HTML content.");
            return;
        }

        List<String> initialLinks = extractLinksFromHtmlFilteredByUrlDomain(html, url);
        if (initialLinks.isEmpty()) {
            System.out.println("No links found in the HTML content.");
            return;
        }

        Set<String> discoverySet = getAllLinksDiscoveredBy(initialLinks);

        printResultSetSorted(discoverySet);


        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        System.out.println("Time spent: " + duration / 1000 + " seconds");
    }

    /**
     * Dynamically discovers all links from the initial set of links.
     *
     * @param initialLinks The initial list of links to start the discovery process.
     * @return A Set of all unique links discovered.
     */
    public static Set<String> getAllLinksDiscoveredBy(List<String> initialLinks) {
        Set<String> discoverySet = ConcurrentHashMap.newKeySet();
        ConcurrentLinkedQueue<String> linksQueue = new ConcurrentLinkedQueue<>(initialLinks);
        List<Thread> virtualThreads = new ArrayList<>();
        Semaphore semaphore = new Semaphore(100); // Limit the number of concurrent threads as https://ecosio.com/en/ seems to block requests if too many are made at once.

        while (!linksQueue.isEmpty() || virtualThreads.stream().anyMatch(Thread::isAlive)) {
            String link = linksQueue.poll();
            if (link != null && discoverySet.add(link)) {
                try {
                    semaphore.acquire();
                    Thread virtualThread = Thread.ofVirtual().start(() -> {
                        try {
                            String linkHtml = getHtmlByUrl(link);
                            if (linkHtml != null) {
                                List<String> newLinks = extractLinksFromHtmlFilteredByUrlDomain(linkHtml, link);
                                for (String newLink : newLinks) {
                                    if (discoverySet.add(newLink)) {
                                        linksQueue.add(newLink);
                                    }
                                }
                            } else {
                                System.out.println("Failed to retrieve HTML content for: " + link);
                            }
                        } finally {
                            semaphore.release();
                        }
                    });
                    virtualThreads.add(virtualThread);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        for (Thread virtualThread : virtualThreads) {
            try {
                virtualThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        return discoverySet;
    }
    /**
     * Prints the sorted result set of links.
     *
     * @param links The Set of links to print.
     */
    public static void printResultSetSorted(Set<String> links) {
        System.out.println("------------------");
        System.out.println(links.stream().sorted().toList());
        System.out.println("------------------");
    }

    /**
     * Fetches the HTML content from the given URL.
     *
     * @param url The URL to fetch HTML from.
     * @return The HTML content as a String, or null if an error occurs.
     */
    public static String getHtmlByUrl(String url) {
        try {
            HttpClient client = getHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Extracts all links having same domain as the URL from the HTML content.
     *
     * @param html The HTML content as a String.
     * @param url  The base URL to resolve relative links.
     * @return A Set of unique links found in the HTML content.
     */
    public static List<String> extractLinksFromHtmlFilteredByUrlDomain(String html, String url) {
        List<String> links = new ArrayList<>();
        String baseDomain = URI.create(url).getHost();
        String[] anchors = html.split("<a ");

        for (String anchor : anchors) {
            int hrefIndex = anchor.indexOf("href=\"");
            if (hrefIndex != -1) {
                int start = hrefIndex + 6;
                int end = anchor.indexOf("\"", start);
                if (end != -1) {
                    String link = anchor.substring(start, end);
                    if (link.startsWith("http")) {
                        URI linkUri = URI.create(link);
                        if (linkUri.getHost().equals(baseDomain)) {
                            links.add(link);
                        }
                    }
                }
            }
        }
        return links;
    }

    private static HttpClient getHttpClient() {
        if (client == null) {
            client = HttpClient.newHttpClient();
        }
        return client;
    }
}