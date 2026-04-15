package com.localcloud.emulators.workflows.engine;

import java.util.*;

/**
 * Parsed representation of a Cloud Workflows YAML definition.
 */
public class WorkflowDefinition {
    private final Map<String, SubworkflowDef> subworkflows;

    public WorkflowDefinition(Map<String, SubworkflowDef> subworkflows) {
        this.subworkflows = subworkflows;
    }

    public SubworkflowDef getMain() {
        return subworkflows.get("main");
    }

    public SubworkflowDef getSubworkflow(String name) {
        return subworkflows.get(name);
    }

    public boolean hasSubworkflow(String name) {
        return subworkflows.containsKey(name);
    }

    public Map<String, SubworkflowDef> getAllSubworkflows() {
        return Collections.unmodifiableMap(subworkflows);
    }

    public static class SubworkflowDef {
        private final String name;
        private final List<String> params;
        private final List<StepDef> steps;

        public SubworkflowDef(String name, List<String> params, List<StepDef> steps) {
            this.name = name;
            this.params = params != null ? params : Collections.emptyList();
            this.steps = steps;
        }

        public String getName() { return name; }
        public List<String> getParams() { return params; }
        public List<StepDef> getSteps() { return steps; }
    }

    public static class StepDef {
        private final String name;
        private final String type; // assign, call, switch, for, parallel, try, raise, return, next, steps
        private final Map<String, Object> config;

        public StepDef(String name, String type, Map<String, Object> config) {
            this.name = name;
            this.type = type;
            this.config = config != null ? config : Collections.emptyMap();
        }

        public String getName() { return name; }
        public String getType() { return type; }
        public Map<String, Object> getConfig() { return config; }
        public Object get(String key) { return config.get(key); }
    }
}
