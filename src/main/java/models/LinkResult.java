package models;

public class LinkResult {
    public String url;
    public String text;
    public int status;
    public long timeMs;
    public String result;

    public LinkResult(String url, String text, int status, long timeMs, String result) {
        this.url = url;
        this.text = text;
        this.status = status;
        this.timeMs = timeMs;
        this.result = result;
    }
}