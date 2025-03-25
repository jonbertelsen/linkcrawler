package app;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        //String startUrl = "http://localhost:4000";
       // String startUrl = "https://dat3cph.github.io/spring2025/";
         String startUrl = "https://dat2cph.github.io/spring2025/";


        new ThreadedLinkChecker(startUrl).start(startUrl, startUrl);

    }
}

