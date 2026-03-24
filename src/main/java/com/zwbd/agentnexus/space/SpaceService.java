package com.zwbd.agentnexus.space;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:19
 * @Desc:
 */
@Service
public class SpaceService {
    @Autowired
    private SpaceRepository spaceRepo;

    @Transactional
    public BusinessSpace createSpace(BusinessSpace space) {
        if (StringUtils.hasText(space.getId()) && spaceRepo.existsById(space.getId())) {
            throw new IllegalArgumentException("Space ID exists");
        }
        BusinessSpace saved = spaceRepo.save(space);
        return saved;
    }

    @Transactional
    public void deleteSpace(String id) {
        spaceRepo.deleteById(id);
        // 发布事件：通知其他模块清理数据
    }

    public List<BusinessSpace> getSpaces() {
        return spaceRepo.findAll();
    }

    public BusinessSpace getSpace(String id) {
        return spaceRepo.findById(id).orElseThrow(() -> new RuntimeException("Space not found"));
    }
}
