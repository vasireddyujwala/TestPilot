package com.executionagent.context;

import java.util.ArrayList;
import java.util.List;

/** Repository context data collected during the preparation phase. Mirrors Python RepoContext. */
public class RepoContext {
    public String projectPath;
    public String projectUrl;
    public String language;

    public List<String> workflows = new ArrayList<>();
    public List<String[]> workflowContents = new ArrayList<>();  // [path, content]

    public List<String> dockerfiles = new ArrayList<>();
    public List<String[]> dockerfileContents = new ArrayList<>();  // [path, content]

    public List<Object> searchResults = new ArrayList<>();

    public List<String[]> requirementFiles = new ArrayList<>();  // [path, content]
    public String readmeContent;
    public String unifiedSummary;
    public String problemsMemory;
    public boolean localRepoAvailable = false;

    public RepoContext(String projectPath, String projectUrl, String language) {
        this.projectPath = projectPath;
        this.projectUrl = projectUrl;
        this.language = language;
    }
}
