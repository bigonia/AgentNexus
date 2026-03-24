package com.zwbd.agentnexus.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/10/15 15:13
 * @Desc:
 */
//@NoApiWrapper
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager, JwtUtil jwtUtil, UserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    // 定义请求体
    public record AuthRequest(String username, String password) {
    }

    @PostMapping("/login")
    public ResponseEntity<?> createAuthenticationToken(@RequestBody AuthRequest authRequest) throws Exception {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(authRequest.username(), authRequest.password())
            );
        } catch (BadCredentialsException e) {
            throw new Exception("Incorrect username or password", e);
        }

        final UserDetails userDetails = userDetailsService.loadUserByUsername(authRequest.username());
        final String jwt = jwtUtil.generateToken(userDetails);

        // 返回 vue-element-admin 期望的格式
        return ResponseEntity.ok(Map.of(
                "code", 20000,
                "data", Map.of("token", jwt)
//                "data", jwt
        ));
    }

    /**
     * 获取用户信息接口
     * 对应前端 getInfo 方法
     *
     * @return 用户信息
     */
    @GetMapping("/info")
    public Map<String, Object> getUserInfo() {

        Map<String, Object> result = new HashMap<>();

        // 模拟返回用户信息
        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("roles", new String[]{"guest"});
        userInfo.put("introduction", "I am a super administrator");
        userInfo.put("avatar", "https://wpimg.wallstcn.com/f778738c-e4f8-4870-b634-56703b4acafe.gif");
        userInfo.put("name", "Super Admin");

        result.put("code", 20000);
        result.put("data", userInfo);

        return result;
    }

    /**
     * 用户登出接口
     * 对应前端 logout 方法
     *
     * @return 登出结果
     */
    @PostMapping("/logout")
    public Map<String, Object> logout() {

        // TODO: 实际开发中需要清除token或session

        Map<String, Object> result = new HashMap<>();
        result.put("code", 20000);
        result.put("data", "success");

        return result;
    }

}