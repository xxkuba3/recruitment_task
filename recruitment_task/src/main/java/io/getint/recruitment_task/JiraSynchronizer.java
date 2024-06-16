package io.getint.recruitment_task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JiraSynchronizer {

    private static final String JIRA_URL = "https://jakubgaluszka03.atlassian.net";
    private static final String JIRA_REST_API_URL = "/rest/api/3";
    private static final String USERNAME = "jakub.galuszka03@gmail.com";
    private static final String API_TOKEN = "ATATT3xFfGF0umlwYEXV7uCGHUOd2nKzvHgQ6Q0LjICJkmB3HFH8-4e5V3AUnEIvBcuKL3jCCwkjdfBMgcw9M0tyP_B7q0WD2HowsHchAy4bhRDRhQl9k3UYLFZ0kvjM1Lf-C5iifdCcOqqYtKfZvjsNnRUiJlLiG_20fYUqvkbv2OLVjXo-g6A=12EB09FD";
    private static final String SOURCE_PROJECT_KEY = "PS";
    private static final String TARGET_PROJECT_KEY = "PT";
    private static final String SEARCH_URL = JIRA_URL + JIRA_REST_API_URL + "/search?jql=project=" + SOURCE_PROJECT_KEY + "&maxResults=5";
    private static final String ISSUE_TYPES_URL = JIRA_URL + JIRA_REST_API_URL + "/project/%s/statuses";
    private static final String CREATE_ISSUE_URL = JIRA_URL + JIRA_REST_API_URL + "/issue";
    private static final String TRANSITIONS_URL = JIRA_URL + JIRA_REST_API_URL + "/issue/%s/transitions";
    private static final String COMMENTS_URL = JIRA_URL + JIRA_REST_API_URL + "/issue/%s/comment";
    private static final String DELETE_ISSUE_URL = JIRA_URL + JIRA_REST_API_URL + "/issue/%s";

    public CloseableHttpClient httpClient;

    public JiraSynchronizer() {
        this.httpClient = HttpClients.custom()
                .setDefaultHeaders(createDefaultHeaders())
                .build();
    }

    public static void main(String[] args) {
        try {
            new JiraSynchronizer().moveTasksToOtherProject();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void moveTasksToOtherProject() throws IOException {
        final var sourceProjectIssues = searchIssues();
        final var issueTypeMapping = mapIssueTypes();
        final var issues = new JSONObject(sourceProjectIssues).getJSONArray("issues");

        for (int i = 0; i < issues.length(); i++) {
            final var issue = issues.getJSONObject(i);
            final var issueKey = issues.getJSONObject(i).getString("key");
            final var newIssue = createIssueInTargetProject(issue, issueTypeMapping);
            updateIssueStatus(newIssue.getString("key"), issue);
            syncComments(newIssue.getString("key"), issueKey);
            // Optional: Delete the original issue from the source project
            // deleteIssue(issueKey);
        }
        httpClient.close();
    }

    private String searchIssues() throws IOException {
        return sendGetRequest(SEARCH_URL);
    }

    private Map<String, String> mapIssueTypes() throws IOException {
        final var sourceIssueTypes = getIssueTypes(SOURCE_PROJECT_KEY);
        final var targetIssueTypes = getIssueTypes(TARGET_PROJECT_KEY);
        final var issueTypeMapping = new HashMap<String, String>();

        sourceIssueTypes.forEach((issueTypeName, sourceIssueTypeId) -> {
            final var targetIssueTypeId = targetIssueTypes.get(issueTypeName);
            if (targetIssueTypeId != null) {
                issueTypeMapping.put(sourceIssueTypeId, targetIssueTypeId);
            }
        });

        return issueTypeMapping;
    }

    private Map<String, String> getIssueTypes(String projectKey) throws IOException {
        final var issueTypesUrl = String.format(ISSUE_TYPES_URL, projectKey);
        final var jsonNode = getJsonNodeFromRequest(issueTypesUrl);
        return extractIssueTypes(jsonNode);
    }

    private Map<String, String> extractIssueTypes(JsonNode jsonNode) {
        final var map = new HashMap<String, String>();
        for (JsonNode node : jsonNode) {
            final var name = node.get("name").asText();
            final var id = node.get("id").asText();
            map.put(name, id);
        }
        return map;
    }

    private JSONObject createIssueInTargetProject(JSONObject issue, Map<String, String> issueTypeMapping) throws IOException {
        final var fields = issue.getJSONObject("fields");
        final var targetIssueFields = new JSONObject();

        targetIssueFields.put("project", new JSONObject().put("key", TARGET_PROJECT_KEY));
        targetIssueFields.put("summary", fields.getString("summary"));
        targetIssueFields.put("priority", new JSONObject().put("id", fields.getJSONObject("priority").getString("id")));

        final var sourceIssueTypeId = fields.getJSONObject("issuetype").getString("id");
        final var targetIssueTypeId = issueTypeMapping.get(sourceIssueTypeId);

        if (targetIssueTypeId == null) {
            throw new RuntimeException("No matching issue type found for ID " + sourceIssueTypeId);
        }

        targetIssueFields.put("issuetype", new JSONObject().put("id", targetIssueTypeId));

        if (!fields.isNull("description")) {
            targetIssueFields.put("description", fields.getJSONObject("description"));
        }

        final var newIssue = new JSONObject();
        newIssue.put("fields", targetIssueFields);
        final var response = sendPostRequest(CREATE_ISSUE_URL, newIssue.toString());

        return new JSONObject(response);
    }


    private void updateIssueStatus(String targetIssueKey, JSONObject sourceIssue) throws IOException {
        final var sourceStatusName = sourceIssue.getJSONObject("fields").getJSONObject("status").getString("name").toUpperCase();
        final var transitionId = getTransitionId(targetIssueKey, sourceStatusName);

        if (transitionId != null) {
            final var updateIssueUrl = String.format(TRANSITIONS_URL, targetIssueKey);
            final var transition = new JSONObject();
            transition.put("id", transitionId);

            final var updateStatus = new JSONObject();
            updateStatus.put("transition", transition);

            sendPostRequestWithoutResponse(updateIssueUrl, updateStatus.toString());
        } else {
            throw new RuntimeException("No matching transition found for status name " + sourceStatusName);
        }
    }

    private String getTransitionId(String targetIssueKey, String sourceStatusName) throws IOException {
        final var getTransitionsUrl = String.format(TRANSITIONS_URL, targetIssueKey);
        final var transitionsNode = getJsonNodeFromRequest(getTransitionsUrl);
        return findTransition(transitionsNode, sourceStatusName);
    }

    private String findTransition(JsonNode transitionsNode, String statusName) {
        for (JsonNode transitionNode : transitionsNode.get("transitions")) {
            if (transitionNode.get("name").asText().toUpperCase().equals(statusName)) {
                return transitionNode.get("id").asText();
            }
        }
        return null;
    }

    private void syncComments(String targetIssueKey, String sourceIssueKey) throws IOException {
        final var sourceIssueCommentsUrl = String.format(COMMENTS_URL, sourceIssueKey);
        final var sourceIssueComments = sendGetRequest(sourceIssueCommentsUrl);
        final var commentsArray = new JSONObject(sourceIssueComments).getJSONArray("comments");

        for (int i = 0; i < commentsArray.length(); i++) {
            final var comment = commentsArray.getJSONObject(i);
            final var addCommentUrl = String.format(COMMENTS_URL, targetIssueKey);
            sendPostRequest(addCommentUrl, comment.toString());
        }
    }

    private void deleteIssue(String issueKey) throws IOException {
        final var deleteIssueURL = String.format(DELETE_ISSUE_URL, issueKey);
        sendDeleteRequest(deleteIssueURL);
    }

    private JsonNode getJsonNodeFromRequest(String url) throws IOException {
        final var objectMapper = new ObjectMapper();
        return objectMapper.readTree(sendGetRequest(url));
    }

    private String sendGetRequest(String url) throws IOException {
        return executeRequest(new HttpGet(url));
    }


    private String sendPostRequest(String url, String json) throws IOException {
        final var request = new HttpPost(url);
        request.setEntity(new StringEntity(json));
        return executeRequest(request);
    }

    private void sendPostRequestWithoutResponse(String url, String json) throws IOException {
        final var request = new HttpPost(url);
        request.setEntity(new StringEntity(json));
        executeRequestWithoutResponse(request);
    }

    private void sendDeleteRequest(String url) throws IOException {
        executeRequestWithoutResponse(new HttpDelete(url));
    }

    private String executeRequest(HttpUriRequest request) throws IOException {
        return EntityUtils.toString(httpClient.execute(request).getEntity());
    }

    private void executeRequestWithoutResponse(HttpUriRequest request) throws IOException {
        httpClient.execute(request);
    }

    private Collection<Header> createDefaultHeaders() {
        return List.of(createAuthorizationHeader(), createContentTypeHeader());
    }

    private Header createAuthorizationHeader() {
        return new BasicHeader(HttpHeaders.AUTHORIZATION, "Basic " + encodeCredentials());
    }

    private Header createContentTypeHeader() {
        return new BasicHeader(HttpHeaders.CONTENT_TYPE, "application/json");
    }

    private String encodeCredentials() {
        final var auth = USERNAME + ":" + API_TOKEN;
        return Base64.getEncoder().encodeToString(auth.getBytes());
    }
}
