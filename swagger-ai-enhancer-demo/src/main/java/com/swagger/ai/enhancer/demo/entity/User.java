package com.swagger.ai.enhancer.demo.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;

    private String name;

    private String email;

    private String phone;

    private String status;

    private LocalDateTime createdAt;
}
