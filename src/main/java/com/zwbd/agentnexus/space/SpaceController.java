package com.zwbd.agentnexus.space;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.common.web.ApiWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/11/24 10:20
 * @Desc:
 */
@ApiWrapper
@RestController
@RequestMapping("/api/spaces")
public class SpaceController {
    @Autowired
    private SpaceService spaceService;

    @PostMapping
    public ApiResponse<BusinessSpace> create(@RequestBody BusinessSpace space) {
        return ApiResponse.success(spaceService.createSpace(space));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        spaceService.deleteSpace(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/all")
    public ApiResponse<List<BusinessSpace>> list() {
        return ApiResponse.success(spaceService.getSpaces());
    }

}
