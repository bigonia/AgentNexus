package com.zwbd.agentnexus.sdui.statemachine.repo;

import com.zwbd.agentnexus.sdui.statemachine.model.StateMachineDeployment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface StateMachineDeploymentRepository extends JpaRepository<StateMachineDeployment, String> {

    List<StateMachineDeployment> findByStateMachineIdOrderByDeployedAtDesc(String stateMachineId);

    @Transactional
    void deleteByStateMachineId(String stateMachineId);

    /**
     * Find all deployments that include the given device ID.
     * Uses PostgreSQL JSON containment operator.
     */
    @Query(value = "SELECT * FROM sdui_state_machine_deployment d WHERE d.devices \\:\\:jsonb @> to_jsonb(:deviceId\\:\\:text)", nativeQuery = true)
    List<StateMachineDeployment> findByDeviceId(@Param("deviceId") String deviceId);
}
