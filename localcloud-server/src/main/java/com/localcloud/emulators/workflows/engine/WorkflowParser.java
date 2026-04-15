package com.localcloud.emulators.workflows.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.*;

/**
 * Parses Cloud Workflows YAML definition into a WorkflowDefinition object.
 */
public class WorkflowParser {
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @SuppressWarnings("unchecked")
    public static WorkflowDefinition parse(String yamlSource) {
        try {
            Map<String, Object> raw = yamlMapper.readValue(yamlSource, Map.class);
            Map<String, WorkflowDefinition.SubworkflowDef> subworkflows = new LinkedHashMap<>();

            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> subDef = (Map<String, Object>) entry.getValue();

                List<String> params = null;
                if (subDef.containsKey("params")) {
                    Object paramsObj = subDef.get("params");
                    if (paramsObj instanceof List<?> paramList) {
                        params = new ArrayList<>();
                        for (Object p : paramList) params.add(String.valueOf(p));
                    }
                }

                List<WorkflowDefinition.StepDef> steps = parseSteps(subDef.get("steps"));
                subworkflows.put(name, new WorkflowDefinition.SubworkflowDef(name, params, steps));
            }

            if (!subworkflows.containsKey("main")) {
                throw new WorkflowException("Workflow must have a 'main' entry point");
            }

            return new WorkflowDefinition(subworkflows);
        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowException("Failed to parse workflow YAML: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<WorkflowDefinition.StepDef> parseSteps(Object stepsObj) {
        if (stepsObj == null) return Collections.emptyList();
        List<WorkflowDefinition.StepDef> steps = new ArrayList<>();

        if (stepsObj instanceof List<?> stepList) {
            for (Object item : stepList) {
                if (item instanceof Map<?, ?> stepMap) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) stepMap).entrySet()) {
                        String stepName = String.valueOf(entry.getKey());
                        Object stepBody = entry.getValue();

                        if (stepBody instanceof Map<?, ?> bodyMap) {
                            Map<String, Object> config = (Map<String, Object>) bodyMap;
                            String type = detectStepType(config);
                            steps.add(new WorkflowDefinition.StepDef(stepName, type, config));
                        }
                    }
                }
            }
        }
        return steps;
    }

    static String detectStepType(Map<String, Object> config) {
        if (config.containsKey("assign")) return "assign";
        if (config.containsKey("call")) return "call";
        if (config.containsKey("switch")) return "switch";
        if (config.containsKey("for")) return "for";
        if (config.containsKey("parallel")) return "parallel";
        if (config.containsKey("try")) return "try";
        if (config.containsKey("raise")) return "raise";
        if (config.containsKey("return")) return "return";
        if (config.containsKey("next")) return "next";
        if (config.containsKey("steps")) return "steps";
        return "unknown";
    }
}
