package validators;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import models.LinkResult;

public class ApiValidator {

    public static LinkResult validate(String url) {

        long start = System.currentTimeMillis();

        try {
            Response response = RestAssured
                    .given()
                    .relaxedHTTPSValidation()
                    .when()
                    .get(url);

            int status = response.getStatusCode();
            long time = System.currentTimeMillis() - start;

            String result;

            if (status >= 200 && status < 400) {
                result = "PASS";
            } else if (status == 401 || status == 403) {
                result = "PROTECTED";
            } else {
                result = "FAIL";
            }

            return new LinkResult(url, "", status, time, result);

        } catch (Exception e) {
            return new LinkResult(url, "", -1, 0, "ERROR");
        }
    }
}