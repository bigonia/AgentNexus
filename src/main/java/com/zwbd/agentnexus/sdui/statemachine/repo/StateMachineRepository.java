package com.zwbd.agentnexus.sdui.statemachine.repo;

import com.zwbd.agentnexus.sdui.statemachine.model.StateMachine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StateMachineRepository extends JpaRepository<StateMachine, String> {

    Page<StateMachine> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
