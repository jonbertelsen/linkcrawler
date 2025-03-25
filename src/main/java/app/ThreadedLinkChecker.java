package app;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadedLinkChecker {

    private final int POLITENESS_THRESHOLD = 200;
    private long startTimeMillis;
    private long endTimeMillis;
    private final Set<String> visitedInternalLinks = ConcurrentHashMap.newKeySet();
    private final Set<String> checkedExternalLinks = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(10);
    private final String baseDomain;
    private final AtomicInteger activeTaskCount = new AtomicInteger(0);
    private final CountDownLatch doneSignal = new CountDownLatch(1);

    private final Map<String, List<BrokenLink>> brokenLinksByPage = new ConcurrentHashMap<>();

    public ThreadedLinkChecker(String baseUrl) {
        this.baseDomain = getDomain(baseUrl);
    }

    public void start(String startUrl, String fromPage) {
        this.startTimeMillis = System.currentTimeMillis();
        submitTask(startUrl, fromPage);

        try {
            doneSignal.await();
            executor.shutdown();
            this.endTimeMillis = System.currentTimeMillis(); // ⏱️ End timer
            generateHtmlReport(startUrl);
            System.out.println("\u2705 Crawl finished. See broken-links.html for results.");
        } catch (InterruptedException e) {
            System.out.println("\u274C Crawl interrupted: " + e.getMessage());
        }
    }

    private void submitTask(String url, String fromPage) {
        activeTaskCount.incrementAndGet();
        executor.submit(() -> {
            try {
                crawl(url, fromPage);
            } finally {
                if (activeTaskCount.decrementAndGet() == 0) {
                    doneSignal.countDown();
                }
            }
        });
    }

    private void crawl(String url, String fromPage) {
        if (url == null || url.isEmpty()) return;

        boolean isInternal = isInternalLink(url);

        if (isInternal) {
            if (!visitedInternalLinks.add(url)) return;
            System.out.println("Visiting internal: " + url);
        } else {
            if (!checkedExternalLinks.add(url)) return;
            System.out.println("Checking external: " + url);
        }

        int status = checkLink(url);
        if (status >= 400) {
            System.out.println("\u274C Broken link: " + url + " (Status: " + status + ")");
            writeBrokenLink(url, fromPage, status);
            return;
        }

        if (isInternal) {
            try {
                Thread.sleep(POLITENESS_THRESHOLD); // Politeness delay before fetching internal HTML content
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");

                String contentType = connection.getContentType();
                if (contentType != null && contentType.contains("text/html")) {
                    Document doc = Jsoup.parse(connection.getInputStream(), null, url);

                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String absHref = link.absUrl("href");
                        if (absHref.startsWith("mailto:") || absHref.startsWith("tel:")) continue;
                        submitTask(absHref, url);
                    }

                    Elements images = doc.select("img[src]");
                    for (Element img : images) {
                        String absSrc = img.absUrl("src");
                        if (!absSrc.isEmpty()) {
                            int imgStatus = checkLink(absSrc);
                            if (imgStatus >= 400) {
                                System.out.println("\u274C Broken image: " + absSrc + " (Status: " + imgStatus + ")");
                                writeBrokenLink(absSrc, url, imgStatus);
                            }
                        }
                    }

                } else {
                    System.out.println("Skipping content (not HTML): " + url + " (" + contentType + ")");
                }
            } catch (IOException | InterruptedException e) {
                System.out.println("\u26A0\uFE0F Error parsing: " + url + " - " + e.getMessage());
                // Optionally restore the interrupted status if caught
                Thread.currentThread().interrupt();
            }
        }
    }

    private int checkLink(String urlStr) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");

            int status = connection.getResponseCode();

            if (status == 403 || status == 405) {
                connection = (HttpURLConnection) new URL(urlStr).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                status = connection.getResponseCode();
            }

            return status;
        } catch (Exception e) {
            System.out.println("\u26A0\uFE0F Could not check: " + urlStr + " - " + e.getMessage());
            return 500;
        }
    }

    private void writeBrokenLink(String brokenUrl, String fromPage, int status) {
        boolean internal = isInternalLink(brokenUrl);
        BrokenLink link = new BrokenLink(brokenUrl, status, internal);
        brokenLinksByPage
                .computeIfAbsent(fromPage, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(link);
    }

    private void generateHtmlReport(String startUrl) {

        long durationMillis = endTimeMillis - startTimeMillis;
        long seconds = durationMillis / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;

        try (BufferedWriter writer = new BufferedWriter(new FileWriter("broken-links.html"))) {
            writer.write("<!DOCTYPE html><html><head><meta charset='UTF-8'>");
            writer.write("<title>Broken Links Report</title>");
            writer.write("<style>body { font-family: sans-serif; } li { margin: 4px 0; }</style>");
            writer.write("</head><body>");
            writer.write("<h2>Broken Links Report</h2>");
            writer.write("<table><tr><td>Start-url</td><td>" + startUrl + "</td></tr>");
            writer.write("<tr><td>Internal links crawled</td><td>" + visitedInternalLinks.size() + "</td></tr>");
            writer.write("<tr><td>External links crawled</td><td>" + checkedExternalLinks.size() + "</td></tr>");
            writer.write("<tr><td>Number of Broken pages</td><td>" + brokenLinksByPage.size() + "</td></tr>");
            writer.write("<tr><td>Number of broken links</td><td>" + brokenLinksByPage.values().stream().mapToLong(List::size).sum() + "</td></tr>");
            writer.write("<tr><td>Total crawl time</td><td>" + minutes + "m " + seconds + "s</td></tr>");
            writer.write("</table>");

            for (String sourcePage : brokenLinksByPage.keySet()) {
                writer.write("<h3>Source: <a href='" + sourcePage + "'>" + sourcePage + "</a></h3>\n");
                writer.write("<ul>\n");
                for (BrokenLink link : brokenLinksByPage.get(sourcePage)) {
                    String type = link.internal ? "Internal" : "External";
                    writer.write("<li>\uD83D\uDD17 <a href='" + link.url + "'>" + link.url + "</a> " +
                                         "(Status: " + link.status + ", Type: " + type + ")</li>\n");
                }
                writer.write("</ul>\n");
            }

            writer.write("</body></html>");
        } catch (IOException e) {
            System.out.println("\u26A0\uFE0F Could not write HTML report: " + e.getMessage());
        }
    }

    private boolean isInternalLink(String url) {
        return getDomain(url).equalsIgnoreCase(baseDomain);
    }

    private String getDomain(String url) {
        try {
            return new URL(url).getHost().replaceFirst("^www\\.", "");
        } catch (Exception e) {
            return "";
        }
    }

    static class BrokenLink {
        String url;
        int status;
        boolean internal;

        BrokenLink(String url, int status, boolean internal) {
            this.url = url;
            this.status = status;
            this.internal = internal;
        }
    }

}
