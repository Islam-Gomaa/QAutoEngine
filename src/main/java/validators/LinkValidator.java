package validators;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import models.LinkResult;

import java.net.HttpURLConnection;
import java.net.URL;

public class LinkValidator {

    public static int getStatusCode(String url) {
        try {
            Response response = RestAssured
                    .given()
                    .relaxedHTTPSValidation()
                    .when()
                    .get(url);

            return response.getStatusCode();

        } catch (Exception e) {
            return -1;
        }
    }

    public static LinkResult validate(String url, String text) {

        int retries = 3;
        int status = -1;
        long start = System.currentTimeMillis();

        while (retries-- > 0) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.connect();

                status = connection.getResponseCode();
                break;

            } catch (Exception e) {
                if (retries == 0) {
                    return new LinkResult(url, text, -1, 0, "ERROR");
                }
            }
        }

        long time = System.currentTimeMillis() - start;

        String result;

        if (status == 200) {
            result = (time > 3000) ? "SLOW" : "PASS";
        } else if (status == -1) {
            result = "ERROR"; // network issue
        } else {
            result = "FAIL"; // actual broken link
        }

        return new LinkResult(url, text, status, time, result);
    }
}