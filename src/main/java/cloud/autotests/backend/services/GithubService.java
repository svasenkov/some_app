package cloud.autotests.backend.services;

import cloud.autotests.backend.config.GithubConfig;
import cloud.autotests.backend.models.Order;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;

import static cloud.autotests.backend.utils.Utils.readStringFromFile;

public class GithubService {
    private static final Logger LOG = LoggerFactory.getLogger(GithubService.class);

    private final String TEMPLATE_REPOSITORY_URL = "https://api.github.com/repos/%s/%s/generate";
    private final String NEW_TEST_REPOSITORY_PATH = "https://api.github.com/repos/%s/%s/contents/" +
            "src/test/java/cloud/autotests/tests/AppTests.java";
    private final String TEST_CLASS_TEMPLATE_PATH = "./src/main/resources/github/AppTests.java.tpl";
    private final String TEST_STEP_TEMPLATE_PATH = "./src/main/resources/github/step.tpl";

    private String githubToken;
    private String githubTemplateRepositoryApiUrl;
    private String githubGeneratedOwner;

    @Autowired
    public GithubService(GithubConfig githubConfig) {
        this.githubToken = githubConfig.getGithubToken();
        this.githubTemplateRepositoryApiUrl = String.format(TEMPLATE_REPOSITORY_URL,
                githubConfig.getGithubTemplateOwner(), githubConfig.getGithubTemplateRepository());
        this.githubGeneratedOwner = githubConfig.getGithubGeneratedOwner();
    }

    public String createRepositoryFromTemplate(String jiraIssueKey) {
        String bodyTemplate = "{\"owner\": \"%s\", \"name\": \"%s\"}";
        String body = String.format(bodyTemplate, this.githubGeneratedOwner, jiraIssueKey);

        JSONObject createRepositoryResponse = Unirest
                .post(this.githubTemplateRepositoryApiUrl)
                .header("Accept", "application/vnd.github.baptiste-preview+json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "token " + this.githubToken)
                .body(body)
//                .field("owner", this.githubTemplateGenerateOwner)
//                .field("name", jiraIssueKey)
                .asJson()
                .ifFailure(response -> {
                    LOG.error("Oh No! Status" + response.getStatus());
                    LOG.error(response.getStatusText());
                    LOG.error(response.getBody().toPrettyString());
                    response.getParsingError().ifPresent(e -> {
                        LOG.error("Parsing Exception: ", e);
                        LOG.error("Original body: " + e.getOriginalBody());
                    });
                })
                .getBody()
                .getObject();

        if (createRepositoryResponse.has("html_url"))
            return createRepositoryResponse.getString("html_url");

        return null;

        // todo add exception for existing repo
        /*
        {
          "message": "Unprocessable Entity",
          "errors": [
            "Could not clone: Name already exists on this account"
          ],
          "documentation_url": "https://docs.github.com/rest/reference/repos#create-a-repository-using-a-template"
        }
         */
    }

    public String generateTests(Order order, String jiraIssueKey) {
        String testClassPath = String.format(NEW_TEST_REPOSITORY_PATH, this.githubGeneratedOwner, jiraIssueKey);
        String testClassContent = generateTestClass(order);
        String testClassContent64 = Base64.getEncoder().encodeToString(testClassContent.getBytes());

        String bodyTemplate = "{\"message\": \"Added test '%s'\", \"content\": \"%s\"}";
        String body = String.format(bodyTemplate, order.getTitle(), testClassContent64);

        JSONObject createTestsResponse = Unirest
                .put(testClassPath)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "token " + this.githubToken)
                .body(body)
                .asJson()
                .ifFailure(response -> {
                    LOG.error("Oh No! Status" + response.getStatus());
                    LOG.error(response.getStatusText());
                    LOG.error(response.getBody().toPrettyString());
                    response.getParsingError().ifPresent(e -> {
                        LOG.error("Parsing Exception: ", e);
                        LOG.error("Original body: " + e.getOriginalBody());
                    });
                })
                .getBody()
                .getObject();

        if (createTestsResponse.has("message"))
            if (createTestsResponse.getString("message").contains("Invalid request.\\n\\n\\\"sha\\\" wasn't supplied."))
                return null; // todo add normal exception

        if (createTestsResponse.has("content")) {
            JSONObject contentJson = createTestsResponse.getJSONObject("content");
            if (contentJson.has("html_url")) {
                return contentJson.getString("html_url");
            } else {
                return null; // todo add exception
            }
        }
        return null;
    }

    private String generateTestClass(Order order) { // todo add link to Jira issue
        String testTemplateContent = readStringFromFile(TEST_CLASS_TEMPLATE_PATH);
        StringBuilder generatedSteps = new StringBuilder();
        String orderSteps = order.getSteps() // todo move do model ?
                .replace("\r\n", "\n")
                .replace("\r", "\n");
        String[] steps = orderSteps.split("\n");
        for (String step: steps) {
            generatedSteps.append(generateStep(step));
        }

        return String.format(testTemplateContent, order.getTitle(), generatedSteps);
    }

    private String generateStep(String step) {
        String stepTemplate = readStringFromFile(TEST_STEP_TEMPLATE_PATH);

        return String.format(stepTemplate, step);
    }
}