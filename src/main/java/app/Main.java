package app;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
            String startUrl = "http://localhost:4000";
        new ThreadedLinkChecker(startUrl).start(startUrl, startUrl);

    }
}

