package com.zwbd.agentnexus.sdui.statemachine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drops legacy tables from the old workflow model and the old
 * split Definition/Deployment model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StateMachineLegacyWorkflowCleanup implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        // Old workflow model
        drop("sdui_workflow_run_step");
        drop("sdui_workflow_run");
        drop("sdui_workflow_instance");
        drop("sdui_workflow_binding");
        drop("sdui_workflow_definition");

        // Old split definition/deployment model
        drop("sdui_state_machine_definition");

        // Old run history table
        drop("sdui_state_machine_run");
    }

    private void drop(String tableName) {
        jdbcTemplate.execute("drop table if exists " + tableName);
        log.info("Legacy table cleanup checked: {}", tableName);
    }
}
