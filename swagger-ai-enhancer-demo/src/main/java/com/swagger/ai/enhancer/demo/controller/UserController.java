package com.swagger.ai.enhancer.demo.controller;

import com.swagger.ai.enhancer.demo.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@Tag(name = "用户管理")
public class UserController {

    private final Map<Long, User> store = new HashMap<>();

    @Operation(summary = "获取用户详情")
    @GetMapping("/{id}")
    public User getById(@PathVariable Long id, @RequestParam(required = false) String fields) {
        return store.getOrDefault(id, new User());
    }

    @Operation(summary = "创建用户")
    @PostMapping
    public User create(@RequestBody User user) {
        long nextId = store.keySet().stream().mapToLong(Long::longValue).max().orElse(0L) + 1;
        user.setId(nextId);
        user.setCreatedAt(LocalDateTime.now());
        store.put(nextId, user);
        return user;
    }

    @Operation(summary = "更新用户信息")
    @PutMapping("/{id}")
    public User update(@PathVariable Long id, @RequestBody User user) {
        user.setId(id);
        store.put(id, user);
        return user;
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        store.remove(id);
    }

    @Operation(summary = "获取用户列表")
    @GetMapping
    public List<User> list(@RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "10") int size) {
        return new ArrayList<>(store.values());
    }
}
