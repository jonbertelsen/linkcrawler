package app;
public  class BrokenLink {
    String url;
    int status;
    boolean internal;

    BrokenLink(String url, int status, boolean internal) {
        this.url = url;
        this.status = status;
        this.internal = internal;
    }
}
